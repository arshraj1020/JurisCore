package com.juriscore.documents.service;

import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.CaseTimelineService;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.common.security.TenantGuard;
import com.juriscore.common.storage.ObjectStorageException;
import com.juriscore.common.storage.ObjectStorageService;
import com.juriscore.documents.api.dto.CreateDocumentRequest;
import com.juriscore.documents.api.dto.UpdateDocumentRequest;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import com.juriscore.documents.event.DocumentCreatedEvent;
import com.juriscore.documents.event.DocumentDeletedEvent;
import com.juriscore.documents.event.DocumentDownloadRequestedEvent;
import com.juriscore.documents.event.DocumentUpdatedEvent;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import com.juriscore.documents.repository.CaseDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Case documents: registering them, confirming them, handing out links, removing them.
 *
 * <p>The shape of this service follows from one fact: <strong>the file never passes
 * through the application.</strong> The browser PUTs it straight to storage, so the
 * platform learns about an upload in two separate steps — a row created when the link is
 * issued, and a confirmation afterwards that checks storage rather than believing the
 * client. Everything below is arranged around that split.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final CaseDocumentRepository documentRepository;
    private final CaseAccess caseAccess;
    private final CaseTimelineService timeline;
    private final ObjectStorageService storage;
    private final DocumentUploadPolicy policy;
    private final EventPublisher eventPublisher;
    private final DocumentFailureRecorder failureRecorder;

    /** The link, and the metadata it belongs to. */
    public record UploadTicket(CaseDocument document, ObjectStorageService.PresignedUrl url) {
    }

    /** The link, and what the document says about itself. */
    public record DownloadTicket(CaseDocument document, ObjectStorageService.PresignedUrl url) {
    }

    /**
     * Registers a document and issues a link to upload it to.
     *
     * <p>Order matters here. The case is resolved first, so a matter belonging to another
     * firm stops the request before a row exists; then the request is validated; then the
     * row is saved, because the object key is derived from the id the database assigns;
     * and only then is a link issued — nothing hands out a signed URL before there is
     * something authorized to write to.
     *
     * <p>If presigning fails, the transaction rolls back and no half-registered document
     * is left behind.
     */
    @Transactional
    public UploadTicket register(UUID caseId, UUID organizationId, CreateDocumentRequest request) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        policy.validate(request.filename(), request.contentType(), request.fileSize());

        CaseDocument document = new CaseDocument();
        document.setOrganizationId(organizationId);
        document.setCaseId(legalCase.getId());
        document.setOriginalFilename(request.filename().trim());
        document.setContentType(DocumentUploadPolicy.normalise(request.contentType()));
        document.setFileSize(request.fileSize());
        document.setStatus(DocumentStatus.UPLOADING);
        document.setDescription(request.description());
        document.setStorageKey(StorageKeys.reservation());

        // Two statements, one transaction. The key ends in the document id, and that id
        // does not exist until the row is inserted: an id assigned beforehand is refused
        // as a detached entity, and a value set between persist and flush is not folded
        // into the INSERT. So the row is written with a reservation and stamped straight
        // after, and neither the reservation nor the unstamped row is visible outside
        // this transaction.
        CaseDocument saved = documentRepository.saveAndFlush(document);
        saved.setStorageKey(StorageKeys.forDocument(organizationId, legalCase.getId(), saved.getId()));
        documentRepository.saveAndFlush(saved);

        // Nothing but a real key is ever signed for. If the stamping above were ever to
        // stop taking effect — which is precisely what a non-updatable mapping once did,
        // silently — this fails loudly here instead of handing out a link to nowhere.
        String storageKey = saved.getStorageKey();
        StorageKeys.requireCanonical(storageKey);

        ObjectStorageService.PresignedUrl url = storage.presignUpload(
                storageKey, saved.getContentType(), saved.getFileSize());

        log.info("Document {} registered on case {} in organization {} ({} bytes declared)",
                saved.getId(), legalCase.getId(), organizationId, saved.getFileSize());
        eventPublisher.publish(new DocumentCreatedEvent(organizationId, saved.getId(),
                legalCase.getId(), saved.getOriginalFilename(), saved.getContentType(),
                saved.getFileSize()));
        return new UploadTicket(saved, url);
    }

    /**
     * Confirms an upload by asking storage what is actually there.
     *
     * <p>The client's word that it finished is not evidence, so this reads the object back:
     * if nothing is there the document is refused, and if what is there breaks the size
     * ceiling the document is moved to FAILED rather than left as a usable file that the
     * policy would never have accepted. That second check is the real enforcement of the
     * maximum, because a presigned PUT cannot enforce a size — see
     * {@code S3ObjectStorageService}.
     *
     * <p><strong>Completing twice is a no-op.</strong> A retried request, a double-clicked
     * button or a client replaying after a timeout all find the document already AVAILABLE
     * and get it back unchanged, with no second transition and no second event. Anything
     * else — a deleted or failed document — is a 409, because those are not retries.
     */
    @Transactional
    public CaseDocument completeUpload(UUID documentId, UUID organizationId) {
        CaseDocument document = requireLive(documentId, organizationId);

        if (document.getStatus() == DocumentStatus.AVAILABLE) {
            log.debug("Document {} was already complete; treating the retry as a no-op", documentId);
            return document;
        }
        if (document.getStatus() != DocumentStatus.UPLOADING) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A document in " + document.getStatus() + " cannot be completed");
        }

        Optional<ObjectStorageService.StoredObject> stored = storage.head(document.getStorageKey());
        if (stored.isEmpty()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "No uploaded file was found for this document. Upload it to the link that "
                            + "was issued, then complete it.");
        }

        long actualSize = stored.get().sizeBytes();
        if (actualSize > policy.maxFileSize()) {
            fail(document, "storage reports " + actualSize + " bytes, over the "
                    + policy.maxFileSize() + " byte maximum");
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The uploaded file is larger than the maximum of " + policy.maxFileSize()
                            + " bytes");
        }
        if (actualSize <= 0) {
            fail(document, "storage reports an empty object");
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The uploaded file is empty");
        }

        // Storage is the authority on size; the declared figure was only ever a claim.
        document.setFileSize(actualSize);
        document.transitionTo(DocumentStatus.AVAILABLE, Instant.now());

        LegalCase legalCase = caseAccess.require(document.getCaseId(), organizationId);
        timeline.append(legalCase, CaseEventType.DOCUMENT_UPLOADED,
                CurrentUser.requireUserId(), "Document uploaded: " + document.getOriginalFilename());

        log.info("Document {} confirmed at {} bytes", documentId, actualSize);
        eventPublisher.publish(new DocumentUploadCompletedEvent(organizationId, documentId,
                document.getCaseId(), document.getOriginalFilename(), document.getContentType(),
                actualSize));
        return document;
    }

    /**
     * Records a rejected upload so that it survives the exception that follows it.
     *
     * <p>Both halves matter. {@link DocumentFailureRecorder} commits FAILED in its own
     * transaction, because this method's caller is about to throw and take its own
     * transaction down with it; the transition on the copy in hand keeps that object from
     * outliving the call still claiming a status the database no longer agrees with.
     */
    private void fail(CaseDocument document, String reason) {
        failureRecorder.markFailed(document.getId(), document.getOrganizationId(), reason);
        document.transitionTo(DocumentStatus.FAILED, Instant.now());
    }

    /**
     * Issues a download link for a document that is actually there.
     *
     * <p>Not transactional for writing — it changes nothing — but it does publish, because
     * who read which filing is the kind of record a firm is eventually asked for.
     */
    @Transactional(readOnly = true)
    public DownloadTicket download(UUID documentId, UUID organizationId) {
        CaseDocument document = requireLive(documentId, organizationId);
        if (!document.isDownloadable()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "This document is " + document.getStatus() + " and has no file to download");
        }

        ObjectStorageService.PresignedUrl url =
                storage.presignDownload(document.getStorageKey(), document.getOriginalFilename());

        eventPublisher.publish(new DocumentDownloadRequestedEvent(organizationId, documentId,
                document.getCaseId(), CurrentUser.requireUserId()));
        return new DownloadTicket(document, url);
    }

    /** A document of this firm that has not been deleted. Anything else answers not-found. */
    @Transactional(readOnly = true)
    public CaseDocument requireLive(UUID documentId, UUID organizationId) {
        CaseDocument document = documentRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(documentId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.DOCUMENT_NOT_FOUND, documentId));
        TenantGuard.check(document, ErrorCode.DOCUMENT_NOT_FOUND);
        return document;
    }

    @Transactional(readOnly = true)
    public Page<CaseDocument> listForCase(UUID caseId, UUID organizationId, DocumentStatus status,
                                          Pageable pageable) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        return status == null
                ? documentRepository
                        .findByOrganizationIdAndCaseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                                organizationId, legalCase.getId(), pageable)
                : documentRepository
                        .findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
                                organizationId, legalCase.getId(), status, pageable);
    }

    /**
     * Edits the two things a person may change: what the file is called, and what it is.
     *
     * <p>Nothing about the stored object moves. Content type, size and key are the object's
     * properties, not the row's opinion of them, and status belongs to the lifecycle.
     */
    @Transactional
    public CaseDocument update(UUID documentId, UUID organizationId, UpdateDocumentRequest request) {
        CaseDocument document = requireLive(documentId, organizationId);
        if (request.version() == null || request.version().longValue() != document.getVersion()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        policy.validateFilename(request.filename());

        document.setOriginalFilename(request.filename().trim());
        document.setDescription(request.description());

        eventPublisher.publish(new DocumentUpdatedEvent(organizationId, documentId,
                document.getCaseId(), document.getOriginalFilename()));
        return document;
    }

    /**
     * Removes a document from the matter.
     *
     * <p><strong>The metadata and the object are not deleted atomically, and cannot be.</strong>
     * PostgreSQL and S3 have no shared transaction, so one of the two orders has to be
     * chosen and its failure mode accepted:
     *
     * <ul>
     *   <li><em>Object first.</em> If the commit then fails, the row is still AVAILABLE and
     *       points at a file that is gone. Every later download breaks.</li>
     *   <li><em>Metadata first.</em> If the object removal then fails, the row is DELETED —
     *       invisible, undownloadable, correct — and an orphaned object sits in the bucket
     *       costing storage.</li>
     * </ul>
     *
     * <p>The second is chosen. It never leaves active metadata pointing at nothing, which
     * is the failure a user would experience; it leaves only a cost, which a bucket
     * lifecycle rule cleans up. The object removal happens after commit, in
     * {@code DeletedDocumentObjectCleaner}, so a rolled-back delete never touches storage.
     *
     * <p>Deleting twice answers not-found, because the second call is asking about
     * something no longer in the set it can act on.
     */
    @Transactional
    public CaseDocument delete(UUID documentId, UUID organizationId) {
        CaseDocument document = requireLive(documentId, organizationId);
        document.transitionTo(DocumentStatus.DELETED, Instant.now());

        LegalCase legalCase = caseAccess.require(document.getCaseId(), organizationId);
        timeline.append(legalCase, CaseEventType.DOCUMENT_DELETED,
                CurrentUser.requireUserId(), "Document removed: " + document.getOriginalFilename());

        log.info("Document {} deleted in organization {}", documentId, organizationId);
        eventPublisher.publish(new DocumentDeletedEvent(organizationId, documentId,
                document.getCaseId(), document.getOriginalFilename()));
        return document;
    }

    /**
     * Removes the object behind an already-deleted document. Called after commit.
     *
     * <p>Failure is logged and swallowed: the metadata is already DELETED and the user's
     * request already succeeded, so throwing here would only turn a bounded storage cost
     * into a confusing error on an operation that, from every angle the user can see,
     * worked.
     */
    @Transactional(readOnly = true)
    public void purgeObject(UUID documentId, UUID organizationId) {
        documentRepository.findByIdAndOrganizationId(documentId, organizationId)
                .ifPresent(document -> {
                    try {
                        storage.delete(document.getStorageKey());
                        log.debug("Removed the stored object for document {}", documentId);
                    } catch (ObjectStorageException e) {
                        log.warn("Document {} is deleted but its stored object could not be "
                                + "removed; it will be left for the bucket lifecycle rule",
                                documentId, e);
                    }
                });
    }
}
