package com.juriscore.legalresearch.service;

import com.juriscore.common.storage.ObjectStorageService;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.legalresearch.config.LegalResearchProperties;
import com.juriscore.legalresearch.domain.EmbeddingSchema;
import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalChunk;
import com.juriscore.legalresearch.domain.LegalJudgment;
import com.juriscore.legalresearch.repository.LegalChunkRepository;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgmentIngestionOrchestratorTest {

    @Mock
    private LegalJudgmentRepository judgmentRepository;
    @Mock
    private LegalChunkRepository chunkRepository;
    @Mock
    private com.juriscore.documents.service.DocumentService documentService;
    @Mock
    private ObjectStorageService storage;
    @Mock
    private LegalDocumentTextExtractor textExtractor;
    @Mock
    private LegalChunker chunker;
    @Mock
    private EmbeddingClient embeddingClient;

    private LegalResearchProperties properties;
    private JudgmentIngestionOrchestrator orchestrator;

    private final UUID judgmentId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID sourceDocumentId = UUID.randomUUID();
    private LegalJudgment judgment;
    private List<JudgmentExtractionStatus> recordedTransitions;

    @BeforeEach
    void setUp() {
        properties = new LegalResearchProperties();

        orchestrator = new JudgmentIngestionOrchestrator(judgmentRepository, chunkRepository,
                documentService, storage, textExtractor, chunker, embeddingClient, properties);

        judgment = new LegalJudgment();
        judgment.setId(judgmentId);
        judgment.setOrganizationId(organizationId);
        judgment.setSourceDocumentId(sourceDocumentId);
        judgment.setExtractionStatus(JudgmentExtractionStatus.PENDING);

        lenient().when(judgmentRepository.findById(judgmentId)).thenReturn(Optional.of(judgment));
        recordedTransitions = new ArrayList<>();
        lenient().when(judgmentRepository.save(any(LegalJudgment.class))).thenAnswer(inv -> {
            LegalJudgment saved = inv.getArgument(0);
            // Recorded immediately: the enum value is copied out now, not read later from
            // a shared mutable reference that keeps changing across the test.
            recordedTransitions.add(saved.getExtractionStatus());
            return saved;
        });
    }

    private CaseDocument aSourceDocument() {
        CaseDocument document = new CaseDocument();
        document.setId(sourceDocumentId);
        document.setOrganizationId(organizationId);
        document.setContentType("text/plain");
        document.setStorageKey("organizations/x/cases/y/documents/" + sourceDocumentId);
        return document;
    }

    @Test
    void happyPathTransitionsThroughEveryStatusInOrderAndPersistsEmbeddedChunks() throws Exception {
        CaseDocument sourceDocument = aSourceDocument();
        when(documentService.findLive(sourceDocumentId, organizationId)).thenReturn(sourceDocument);
        when(storage.getObject(sourceDocument.getStorageKey()))
                .thenReturn(Optional.of("judgment text".getBytes()));

        LegalDocumentTextExtractor.ExtractionResult extraction = new LegalDocumentTextExtractor.ExtractionResult(
                "judgment text",
                List.of(new LegalDocumentTextExtractor.ExtractedParagraph(1, 1, "judgment text", 0, 13)),
                1);
        when(textExtractor.extract(any(), eq("text/plain"))).thenReturn(extraction);

        List<LegalChunker.ChunkCandidate> candidates = List.of(
                new LegalChunker.ChunkCandidate(0, "judgment text", 1, 1, 0, 13));
        when(chunker.chunk(extraction, properties.getChunking().getMaxChunkChars())).thenReturn(candidates);

        LegalChunk persistedChunk = new LegalChunk();
        persistedChunk.setId(UUID.randomUUID());
        persistedChunk.setChunkText("judgment text");
        when(chunkRepository.findByJudgmentIdOrderByChunkIndexAsc(judgmentId))
                .thenReturn(List.of(persistedChunk));

        float[] vector = new float[EmbeddingSchema.VECTOR_DIMENSIONS];
        when(embeddingClient.embedBatch(List.of("judgment text"))).thenReturn(List.of(vector));

        orchestrator.ingest(judgmentId);

        assertThat(recordedTransitions).containsExactly(
                JudgmentExtractionStatus.EXTRACTING,
                JudgmentExtractionStatus.CHUNKED,
                JudgmentExtractionStatus.EMBEDDED,
                JudgmentExtractionStatus.READY);

        // Two saveAll calls are the intended design, not an accident: persistChunks()
        // durably writes the chunk rows (text, paragraph/page numbers, offsets) the
        // instant chunking succeeds, before any embedding call is even attempted — that
        // is what makes CHUNKED a real, inspectable, retryable state rather than a status
        // label with nothing behind it. embedChunks() then re-saves the same rows once
        // they carry vectors. Collapsing this into one saveAll would mean a chunk that
        // never gets embedded (a slow or failing provider) leaves no persisted chunk rows
        // at all — exactly the case the CHUNKED/EMBEDDED distinction in the schema exists
        // to make visible. So this asserts the two calls' actual effects rather than
        // just their count: no embedding before the first, every embedding present after
        // the second.
        var captor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(chunkRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());

        @SuppressWarnings("unchecked")
        List<LegalChunk> firstSave = (List<LegalChunk>) captor.getAllValues().get(0);
        assertThat(firstSave).hasSize(1);
        assertThat(firstSave.get(0).getChunkText()).isEqualTo("judgment text");
        assertThat(firstSave.get(0).getEmbedding())
                .as("chunks must be persisted before embedding is attempted, with no vector yet")
                .isNull();

        @SuppressWarnings("unchecked")
        List<LegalChunk> secondSave = (List<LegalChunk>) captor.getAllValues().get(1);
        assertThat(secondSave).hasSize(1);
        assertThat(secondSave.get(0).getEmbedding())
                .as("the second save is what actually attaches the embedding")
                .isEqualTo(vector);

        assertThat(persistedChunk.getEmbedding()).isEqualTo(vector);
    }

    @Test
    void aJudgmentThatIsNotPendingIsLeftAlone() {
        judgment.setExtractionStatus(JudgmentExtractionStatus.READY);

        orchestrator.ingest(judgmentId);

        verify(judgmentRepository, never()).save(any());
        verify(documentService, never()).findLive(any(), any());
    }

    @Test
    void missingSourceObjectFailsTheJudgmentRatherThanHanging() {
        CaseDocument sourceDocument = aSourceDocument();
        when(documentService.findLive(sourceDocumentId, organizationId)).thenReturn(sourceDocument);
        when(storage.getObject(sourceDocument.getStorageKey())).thenReturn(Optional.empty());

        orchestrator.ingest(judgmentId);

        assertThat(recordedTransitions).containsExactly(
                JudgmentExtractionStatus.EXTRACTING, JudgmentExtractionStatus.FAILED);
        assertThat(judgment.getFailureReason()).isNotBlank();
    }

    @Test
    void blankExtractedTextFailsTheJudgment() throws Exception {
        CaseDocument sourceDocument = aSourceDocument();
        when(documentService.findLive(sourceDocumentId, organizationId)).thenReturn(sourceDocument);
        when(storage.getObject(sourceDocument.getStorageKey())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        when(textExtractor.extract(any(), any()))
                .thenReturn(new LegalDocumentTextExtractor.ExtractionResult("   ", List.of(), null));

        orchestrator.ingest(judgmentId);

        assertThat(recordedTransitions).containsExactly(
                JudgmentExtractionStatus.EXTRACTING, JudgmentExtractionStatus.FAILED);
        assertThat(judgment.getFailureReason()).contains("No extractable text");
        verify(chunkRepository, never()).saveAll(anyList());
    }

    @Test
    void anEmbeddingFailureFailsTheJudgmentAfterChunksAreAlreadySaved() throws Exception {
        CaseDocument sourceDocument = aSourceDocument();
        when(documentService.findLive(sourceDocumentId, organizationId)).thenReturn(sourceDocument);
        when(storage.getObject(sourceDocument.getStorageKey())).thenReturn(Optional.of("text".getBytes()));

        LegalDocumentTextExtractor.ExtractionResult extraction = new LegalDocumentTextExtractor.ExtractionResult(
                "text", List.of(new LegalDocumentTextExtractor.ExtractedParagraph(1, null, "text", 0, 4)), null);
        when(textExtractor.extract(any(), any())).thenReturn(extraction);

        List<LegalChunker.ChunkCandidate> candidates =
                List.of(new LegalChunker.ChunkCandidate(0, "text", 1, null, 0, 4));
        when(chunker.chunk(any(), eq(properties.getChunking().getMaxChunkChars()))).thenReturn(candidates);

        LegalChunk persistedChunk = new LegalChunk();
        persistedChunk.setId(UUID.randomUUID());
        persistedChunk.setChunkText("text");
        when(chunkRepository.findByJudgmentIdOrderByChunkIndexAsc(judgmentId))
                .thenReturn(List.of(persistedChunk));

        when(embeddingClient.embedBatch(anyList()))
                .thenThrow(new EmbeddingException("provider unreachable"));

        orchestrator.ingest(judgmentId);

        // Chunks were already persisted (CHUNKED) before embedding was attempted — the
        // failure is not silently hidden, and does not roll the chunk rows back either;
        // a retry of ingestion (a later step's concern) would find them still there.
        verify(chunkRepository).saveAll(anyList());
        assertThat(recordedTransitions).containsExactly(
                JudgmentExtractionStatus.EXTRACTING, JudgmentExtractionStatus.CHUNKED,
                JudgmentExtractionStatus.FAILED);
        assertThat(judgment.getFailureReason()).contains("Embedding failed");
    }
}
