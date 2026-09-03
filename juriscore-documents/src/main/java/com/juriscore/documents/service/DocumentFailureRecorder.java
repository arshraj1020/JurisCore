package com.juriscore.documents.service;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import com.juriscore.documents.repository.CaseDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Moves a document to FAILED in a transaction of its own.
 *
 * <p>Sibling of {@code LoginAttemptRecorder}, and it exists for exactly the same reason.
 * A rejected upload has to end in an error response; {@code ApiException} is a
 * {@code RuntimeException}, so throwing it marks the caller's transaction rollback-only
 * and discards every write made before the throw — including the FAILED transition that
 * is the whole point of catching an oversized object. {@code DocumentService} used to set
 * the status on the loaded entity and rely on dirty checking, and that write was thrown
 * away on every single rejection: the row stayed UPLOADING, so the client could simply
 * upload something acceptable to the same link and complete successfully, and FAILED was
 * a state the system could describe but never reach. {@code REQUIRES_NEW} commits this
 * write before the caller throws.
 *
 * <p>It is a separate bean because Spring's proxying ignores self-invocation: a private
 * method on {@code DocumentService} would silently join the outer transaction and change
 * nothing at all — which is indistinguishable, in a unit test with a mocked repository,
 * from working.
 *
 * <p>{@code noRollbackFor = ApiException.class} on {@code completeUpload} was the smaller
 * alternative and was rejected for the reason recorded on {@code LoginAttemptRecorder}: it
 * would commit whatever partial state any expected failure in that method happens to leave
 * behind, now or later, which is a far wider promise than "this one transition survives".
 *
 * <h2>Why it re-reads the row</h2>
 *
 * <p>This transaction has its own persistence context, so it loads the document again
 * rather than writing through the caller's copy — a detached copy would carry the caller's
 * in-flight edits with it. The status is re-checked here as well: the guard belongs with
 * the write, and by the time this runs the caller's read is already stale in principle.
 * Anything that is no longer UPLOADING is left alone, which makes a retried or concurrent
 * rejection a no-op instead of an illegal transition.
 */
@Service
@RequiredArgsConstructor
public class DocumentFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(DocumentFailureRecorder.class);

    private final CaseDocumentRepository documentRepository;

    /**
     * Records that this document's upload will not be accepted, and commits.
     *
     * <p>Tenant-scoped like every other lookup: a document belonging to another firm is
     * simply not found, and nothing is written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID documentId, UUID organizationId, String reason) {
        CaseDocument document = documentRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(documentId, organizationId)
                .orElse(null);

        if (document == null || document.getStatus() != DocumentStatus.UPLOADING) {
            // Already resolved, already deleted, or never ours. Nothing to record, and
            // nothing worth failing the caller's response over.
            return;
        }

        document.transitionTo(DocumentStatus.FAILED, Instant.now());
        documentRepository.saveAndFlush(document);
        log.warn("Document {} in organization {} marked FAILED: {}",
                documentId, organizationId, reason);
    }
}
