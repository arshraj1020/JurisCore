package com.juriscore.legalresearch.event;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import com.juriscore.documents.service.DocumentService;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import com.juriscore.legalresearch.service.JudgmentIngestionOrchestrator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Starts ingestion once both halves of "a judgment ready to process" are true: the
 * document has been registered as a judgment ({@link JudgmentRegisteredEvent}) and its
 * upload has actually completed ({@link DocumentUploadCompletedEvent}). Those two facts
 * can become true in either order, so this listens for both and checks the other half
 * itself before kicking off {@link JudgmentIngestionOrchestrator#ingest}.
 *
 * <p>Both handlers are {@code AFTER_COMMIT}, the same rule every other listener in this
 * codebase follows ({@code DeletedDocumentObjectCleaner}, {@code BillingNotificationListener}):
 * a registration or an upload confirmation that rolls back must never start ingestion for
 * a row that, from the database's point of view, never happened.
 *
 * <p>{@link JudgmentIngestionOrchestrator#ingest} itself is {@code @Async} and idempotent
 * against being invoked twice for the same judgment (it exits immediately if the judgment
 * is not still {@code PENDING}), so the ordinary case — both events firing in the same
 * request or moments apart — cannot double-process a judgment even though both handlers
 * below may end up calling it.
 */
@Component
@RequiredArgsConstructor
public class JudgmentIngestionListener {

    private static final Logger log = LoggerFactory.getLogger(JudgmentIngestionListener.class);

    private final LegalJudgmentRepository judgmentRepository;
    private final DocumentService documentService;
    private final JudgmentIngestionOrchestrator orchestrator;

    /**
     * A document was just registered as a judgment. If its upload had already completed
     * before registration happened, {@code DocumentUploadCompletedEvent} already fired and
     * will never fire again for this document — so this is the only chance to start
     * ingestion in that ordering.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJudgmentRegistered(JudgmentRegisteredEvent event) {
        CaseDocument document;
        try {
            document = documentService.findLive(event.getSourceDocumentId(), event.organizationId());
        } catch (RuntimeException e) {
            log.warn("Judgment {} registered but its source document {} could not be read; "
                    + "ingestion will not start", event.getJudgmentId(), event.getSourceDocumentId(), e);
            return;
        }
        if (document.getStatus() == DocumentStatus.AVAILABLE) {
            orchestrator.ingest(event.getJudgmentId());
        }
        // Otherwise still UPLOADING: onDocumentUploadCompleted below will start it.
    }

    /**
     * A document's upload was confirmed. If it was already registered as a judgment
     * (registration can precede or follow completion), start ingestion now.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentUploadCompleted(DocumentUploadCompletedEvent event) {
        Optional<UUID> judgmentId = judgmentRepository
                .findBySourceDocumentIdAndOrganizationId(event.getDocumentId(), event.organizationId())
                .map(judgment -> judgment.getId());
        judgmentId.ifPresent(orchestrator::ingest);
    }
}
