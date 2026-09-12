package com.juriscore.legalresearch.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.service.DocumentService;
import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalJudgment;
import com.juriscore.legalresearch.event.JudgmentRegisteredEvent;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The one way an existing case document becomes a judgment under Legal Precedent
 * Intelligence.
 *
 * <p>Deliberately not a new upload path: a judgment is uploaded through the existing
 * {@code DocumentService}/S3-presigned flow exactly like any other case document, and this
 * service only marks an already-registered document as one to ingest. That is what keeps
 * this module from duplicating upload handling, storage, or authorization — tenant
 * isolation here is entirely {@link DocumentService#requireLive}'s, called with the
 * caller's own organization id, on the request thread, with a real
 * {@code CurrentUser} in context.
 *
 * <p>Registration can happen before or after the underlying upload completes — a caller
 * may register a document as a judgment moments after creating it, well before the browser
 * has finished the PUT. Either order works: {@link com.juriscore.legalresearch.event.JudgmentIngestionListener}
 * reacts to both this method's event and to {@code DocumentUploadCompletedEvent}, and
 * proceeds only once both "registered" and "uploaded" are true.
 */
@Service
@RequiredArgsConstructor
public class JudgmentRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(JudgmentRegistrationService.class);

    private final DocumentService documentService;
    private final LegalJudgmentRepository judgmentRepository;
    private final EventPublisher eventPublisher;

    @Transactional
    public LegalJudgment register(UUID sourceDocumentId, UUID organizationId) {
        // Tenant-checked, request-thread lookup: throws DOCUMENT_NOT_FOUND for a document
        // that does not exist, is deleted, or belongs to another firm.
        CaseDocument document = documentService.requireLive(sourceDocumentId, organizationId);

        LegalJudgment judgment = new LegalJudgment();
        judgment.setOrganizationId(organizationId);
        judgment.setSourceDocumentId(document.getId());
        judgment.setExtractionStatus(JudgmentExtractionStatus.PENDING);

        LegalJudgment saved;
        try {
            saved = judgmentRepository.saveAndFlush(judgment);
        } catch (DataIntegrityViolationException e) {
            // uk_legal_judgments_source_document: this document is already registered.
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "Document " + sourceDocumentId + " is already registered as a judgment", e);
        }

        log.info("Judgment {} registered for document {} in organization {}", saved.getId(),
                sourceDocumentId, organizationId);
        eventPublisher.publish(new JudgmentRegisteredEvent(organizationId, saved.getId(), sourceDocumentId));
        return saved;
    }

    /** A judgment of this firm. Anything else — wrong firm, unknown id — answers not-found. */
    @Transactional(readOnly = true)
    public LegalJudgment requireById(UUID judgmentId, UUID organizationId) {
        LegalJudgment judgment = judgmentRepository.findByIdAndOrganizationId(judgmentId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, judgmentId));
        TenantGuard.check(judgment, ErrorCode.RESOURCE_NOT_FOUND);
        return judgment;
    }
}
