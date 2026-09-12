package com.juriscore.legalresearch.service;

import com.juriscore.common.storage.ObjectStorageException;
import com.juriscore.common.storage.ObjectStorageService;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.service.DocumentService;
import com.juriscore.legalresearch.config.LegalResearchProperties;
import com.juriscore.legalresearch.domain.EmbeddingSchema;
import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalChunk;
import com.juriscore.legalresearch.domain.LegalJudgment;
import com.juriscore.legalresearch.repository.LegalChunkRepository;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The judgment ingestion pipeline:
 * download the source object &rarr; extract text (Tika) &rarr; legal-aware chunking &rarr;
 * embed each chunk &rarr; persist &rarr; READY.
 *
 * <h2>Why this runs off the request thread</h2>
 *
 * <p>{@link #ingest} is {@code @Async} — text extraction, chunking and calling an
 * embedding provider are all too slow to do inside an HTTP request or inside the same
 * transaction that confirmed a document's upload. It is invoked only from
 * {@link JudgmentIngestionListener}'s {@code AFTER_COMMIT} handlers, never from a
 * controller directly, so a caller registering a judgment gets a fast response and the
 * work happens afterward.
 *
 * <h2>No single long transaction</h2>
 *
 * <p>Each step below reads and writes through the repositories directly rather than
 * wrapping the whole method in one {@code @Transactional} — {@code JpaRepository} methods
 * are already individually transactional, and holding one open transaction across an HTTP
 * call to an embedding provider is exactly the kind of long-held connection
 * {@code application.yml}'s {@code open-in-view: false} exists to avoid elsewhere in this
 * codebase. The cost is visible, not hidden: a crash between two steps leaves the judgment
 * at whatever the last successfully-saved status was (see the class-level limitation note
 * in the step 2 report), not silently marked further along than it got.
 *
 * <h2>Never READY without every step succeeding</h2>
 *
 * <p>{@link #transitionTo} for {@link JudgmentExtractionStatus#READY} is reached only by
 * falling through the whole try block with no exception. Any failure — a missing object,
 * an extraction that yields no text, an embedding call that fails or returns the wrong
 * shape — is caught once, at the top level, and turned into
 * {@link JudgmentExtractionStatus#FAILED} with a human-readable reason. Nothing here
 * catches an exception and continues; a step that cannot complete stops the pipeline.
 */
@Component
@RequiredArgsConstructor
public class JudgmentIngestionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JudgmentIngestionOrchestrator.class);

    private final LegalJudgmentRepository judgmentRepository;
    private final LegalChunkRepository chunkRepository;
    private final DocumentService documentService;
    private final ObjectStorageService storage;
    private final LegalDocumentTextExtractor textExtractor;
    private final LegalChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final LegalResearchProperties properties;

    @Async
    public void ingest(UUID judgmentId) {
        Optional<LegalJudgment> maybeJudgment = judgmentRepository.findById(judgmentId);
        if (maybeJudgment.isEmpty()) {
            log.warn("Judgment {} vanished before ingestion could start; skipping", judgmentId);
            return;
        }
        LegalJudgment judgment = maybeJudgment.get();

        if (judgment.getExtractionStatus() != JudgmentExtractionStatus.PENDING) {
            // Both event paths (registration and upload-completion) can race to trigger
            // ingestion for the same judgment when they land close together. Only the
            // first should do any work; the second finds it already past PENDING and
            // exits quietly rather than re-processing or racing on the same rows.
            log.debug("Judgment {} is already {}, not {}", judgmentId,
                    judgment.getExtractionStatus(), JudgmentExtractionStatus.PENDING);
            return;
        }

        try {
            transitionTo(judgmentId, JudgmentExtractionStatus.EXTRACTING, null, null);

            CaseDocument sourceDocument =
                    documentService.findLive(judgment.getSourceDocumentId(), judgment.getOrganizationId());
            byte[] content = downloadContent(sourceDocument);

            LegalDocumentTextExtractor.ExtractionResult extraction;
            try {
                extraction = textExtractor.extract(content, sourceDocument.getContentType());
            } catch (Exception e) {
                throw new JudgmentIngestionException("Text extraction failed: " + e.getMessage(), e);
            }
            if (extraction.fullText() == null || extraction.fullText().isBlank()) {
                throw new JudgmentIngestionException(
                        "No extractable text was found in the source document");
            }

            List<LegalChunker.ChunkCandidate> candidates =
                    chunker.chunk(extraction, properties.getChunking().getMaxChunkChars());
            if (candidates.isEmpty()) {
                throw new JudgmentIngestionException("Chunking produced no chunks");
            }

            persistChunks(judgment, candidates);
            transitionTo(judgmentId, JudgmentExtractionStatus.CHUNKED, null, extraction.pageCount());

            embedChunks(judgmentId);
            transitionTo(judgmentId, JudgmentExtractionStatus.EMBEDDED, null, null);

            transitionTo(judgmentId, JudgmentExtractionStatus.READY, null, null);
            log.info("Judgment {} ingestion complete: {} chunk(s)", judgmentId, candidates.size());
        } catch (Exception e) {
            log.error("Judgment {} ingestion failed", judgmentId, e);
            transitionTo(judgmentId, JudgmentExtractionStatus.FAILED, reasonFor(e), null);
        }
    }

    private byte[] downloadContent(CaseDocument sourceDocument) {
        Optional<byte[]> content;
        try {
            content = storage.getObject(sourceDocument.getStorageKey());
        } catch (ObjectStorageException e) {
            throw new JudgmentIngestionException(
                    "Could not download the source document from storage: " + e.getMessage(), e);
        }
        return content.orElseThrow(() -> new JudgmentIngestionException(
                "The source document's object is no longer present in storage"));
    }

    private void persistChunks(LegalJudgment judgment, List<LegalChunker.ChunkCandidate> candidates) {
        List<LegalChunk> entities = new ArrayList<>(candidates.size());
        for (LegalChunker.ChunkCandidate candidate : candidates) {
            LegalChunk chunk = new LegalChunk();
            chunk.setOrganizationId(judgment.getOrganizationId());
            chunk.setJudgmentId(judgment.getId());
            chunk.setChunkIndex(candidate.chunkIndex());
            chunk.setChunkText(candidate.text());
            chunk.setParagraphNumber(candidate.paragraphNumber());
            chunk.setPageNumber(candidate.pageNumber());
            chunk.setCharStart(candidate.charStart());
            chunk.setCharEnd(candidate.charEnd());
            entities.add(chunk);
        }
        chunkRepository.saveAll(entities);
    }

    private void embedChunks(UUID judgmentId) {
        List<LegalChunk> chunks = chunkRepository.findByJudgmentIdOrderByChunkIndexAsc(judgmentId);
        List<String> texts = chunks.stream().map(LegalChunk::getChunkText).toList();

        List<float[]> embeddings;
        try {
            embeddings = embeddingClient.embedBatch(texts);
        } catch (EmbeddingException e) {
            throw new JudgmentIngestionException("Embedding failed: " + e.getMessage(), e);
        }
        if (embeddings.size() != chunks.size()) {
            throw new JudgmentIngestionException("Embedding provider returned " + embeddings.size()
                    + " vector(s) for " + chunks.size() + " chunk(s)");
        }

        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = embeddings.get(i);
            if (vector == null || vector.length != EmbeddingSchema.VECTOR_DIMENSIONS) {
                throw new JudgmentIngestionException("Embedding for chunk " + chunks.get(i).getId()
                        + " has the wrong dimensionality");
            }
            chunks.get(i).setEmbedding(vector);
        }
        chunkRepository.saveAll(chunks);
    }

    /**
     * Reloads the judgment fresh before writing — required because a previous transition
     * in the same {@link #ingest} call already bumped its {@code @Version} in the
     * database, so the in-memory reference taken at the top of {@link #ingest} would
     * otherwise be stale by the time a later step tries to save it.
     *
     * @param pageCount only set when non-null, so intermediate transitions that have
     *                  nothing new to say about page count leave it untouched
     */
    private void transitionTo(UUID judgmentId, JudgmentExtractionStatus status, String failureReason,
                              Integer pageCount) {
        LegalJudgment judgment = judgmentRepository.findById(judgmentId).orElseThrow(
                () -> new IllegalStateException("Judgment " + judgmentId + " vanished mid-ingestion"));
        judgment.setExtractionStatus(status);
        judgment.setFailureReason(failureReason);
        if (pageCount != null) {
            judgment.setPageCount(pageCount);
        }
        judgmentRepository.save(judgment);
    }

    private static String reasonFor(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        // Bounded so a pathological error message cannot overflow failure_reason's column.
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }

    /** Any step of the pipeline failing in a way that should mark the judgment FAILED. */
    private static final class JudgmentIngestionException extends RuntimeException {
        JudgmentIngestionException(String message) {
            super(message);
        }

        JudgmentIngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
