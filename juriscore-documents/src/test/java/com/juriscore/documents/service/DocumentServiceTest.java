package com.juriscore.documents.service;

import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.service.CaseAccess;
import com.juriscore.casework.service.CaseTimelineService;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
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
import com.juriscore.documents.support.CallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.description;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID OTHER_FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID DOC_ID = UUID.randomUUID();
    private static final String KEY = "organizations/x/cases/y/documents/z";

    @Mock
    private CaseDocumentRepository documentRepository;
    @Mock
    private CaseAccess caseAccess;
    @Mock
    private CaseTimelineService timeline;
    @Mock
    private ObjectStorageService storage;
    @Mock
    private DocumentUploadPolicy policy;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private DocumentFailureRecorder failureRecorder;

    @InjectMocks
    private DocumentService documentService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.LAWYER);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static LegalCase legalCase() {
        LegalCase legalCase = new LegalCase();
        legalCase.setId(CASE_ID);
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(UUID.randomUUID());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
        return legalCase;
    }

    private static CaseDocument document(DocumentStatus status) {
        CaseDocument document = new CaseDocument();
        document.setId(DOC_ID);
        document.setOrganizationId(FIRM);
        document.setCaseId(CASE_ID);
        document.setOriginalFilename("Written statement.pdf");
        document.setContentType("application/pdf");
        document.setFileSize(1024);
        document.setStorageKey(KEY);
        document.setStatus(status);
        if (status == DocumentStatus.AVAILABLE) {
            document.setUploadedAt(Instant.now());
        }
        return document;
    }

    private static CreateDocumentRequest creation() {
        return new CreateDocumentRequest("  Written statement.pdf  ", "application/pdf", 1024,
                "Filed on 1 September");
    }

    private static ObjectStorageService.PresignedUrl url() {
        return new ObjectStorageService.PresignedUrl("https://example.invalid/signed",
                Duration.ofMinutes(15), Instant.now().plusSeconds(900));
    }

    private void savesWhatItIsGiven() {
        when(documentRepository.saveAndFlush(any(CaseDocument.class)))
                .thenAnswer(call -> {
                    CaseDocument d = call.getArgument(0);
                    if (d.getId() == null) {
                        d.setId(DOC_ID);
                    }
                    return d;
                });
    }

    // ----------------------------------------------------------------------- registering

    @Test
    void registersADocumentInTheUploadingState() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        savesWhatItIsGiven();
        when(storage.presignUpload(anyString(), anyString(), anyLong())).thenReturn(url());

        DocumentService.UploadTicket ticket = documentService.register(CASE_ID, FIRM, creation());
        CaseDocument created = ticket.document();

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getStatus()).isEqualTo(DocumentStatus.UPLOADING);
        assertThat(created.getOriginalFilename()).isEqualTo("Written statement.pdf");
        assertThat(created.getUploadedAt()).isNull();
        assertThat(ticket.url().url()).isEqualTo("https://example.invalid/signed");
    }

    @Test
    @DisplayName("the object key is built from generated ids, never from the filename")
    void theKeyContainsNoCallerInput() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        savesWhatItIsGiven();
        when(storage.presignUpload(anyString(), anyString(), anyLong())).thenReturn(url());

        CaseDocument created = documentService.register(CASE_ID, FIRM,
                new CreateDocumentRequest("../../escape.pdf", "application/pdf", 10, null)).document();

        assertThat(created.getStorageKey())
                .isEqualTo(StorageKeys.forDocument(FIRM, CASE_ID, DOC_ID))
                .doesNotContain("escape")
                .doesNotContain("..");
    }

    @Test
    @DisplayName("the link is signed for the stamped key, never for the reservation")
    void signsOnlyForACanonicalKey() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        savesWhatItIsGiven();
        when(storage.presignUpload(anyString(), anyString(), anyLong())).thenReturn(url());

        documentService.register(CASE_ID, FIRM, creation());

        ArgumentCaptor<String> signedFor = ArgumentCaptor.forClass(String.class);
        verify(storage).presignUpload(signedFor.capture(), anyString(), anyLong());
        assertThat(signedFor.getValue())
                .isEqualTo(StorageKeys.forDocument(FIRM, CASE_ID, DOC_ID))
                .doesNotStartWith("reserved:")
                .doesNotStartWith("pending:");
        assertThat(StorageKeys.isCanonical(signedFor.getValue())).isTrue();
    }

    @Test
    @DisplayName("if the key were ever not stamped, registration fails loudly instead of "
            + "handing out a link to nowhere")
    void refusesToIssueALinkForAnUnstampedKey() {
        // Reproduces, at the seam, the mapping defect that let a row keep its placeholder:
        // the second write silently does not take. Before the guard this produced a signed
        // URL for a path no upload could ever be completed against.
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        when(documentRepository.saveAndFlush(any(CaseDocument.class)))
                .thenAnswer(call -> {
                    CaseDocument d = call.getArgument(0);
                    if (d.getId() == null) {
                        d.setId(DOC_ID);
                        return d;
                    }
                    d.setStorageKey(StorageKeys.reservation());
                    return d;
                });

        assertThatThrownBy(() -> documentService.register(CASE_ID, FIRM, creation()))
                .isInstanceOf(IllegalStateException.class);

        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("a matter belonging to another firm stops the request before a row exists")
    void refusesToRegisterAgainstAForeignCase() {
        when(caseAccess.require(CASE_ID, FIRM)).thenThrow(new ApiException(ErrorCode.CASE_NOT_FOUND));

        assertThatThrownBy(() -> documentService.register(CASE_ID, FIRM, creation()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CASE_NOT_FOUND);

        verify(documentRepository, never()).saveAndFlush(any());
        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("validation runs before anything is written or signed")
    void refusesAnInvalidRequestBeforeIssuingALink() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        doThrow(new ApiException(ErrorCode.VALIDATION_FAILED))
                .when(policy).validate(anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> documentService.register(CASE_ID, FIRM, creation()))
                .isInstanceOf(ApiException.class);

        verify(documentRepository, never()).saveAndFlush(any());
        verify(storage, never()).presignUpload(anyString(), anyString(), anyLong());
    }

    @Test
    void publishesDocumentCreatedWithoutTheUrl() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        savesWhatItIsGiven();
        when(storage.presignUpload(anyString(), anyString(), anyLong())).thenReturn(url());

        documentService.register(CASE_ID, FIRM, creation());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DocumentCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("document.created");
        assertThat(published.getValue().toString())
                .as("a presigned URL is a bearer credential and must never reach the bus")
                .doesNotContain("signed");
    }

    @Test
    @DisplayName("registering writes nothing to the case timeline — the file may never arrive")
    void registeringDoesNotTouchTheTimeline() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());
        savesWhatItIsGiven();
        when(storage.presignUpload(anyString(), anyString(), anyLong())).thenReturn(url());

        documentService.register(CASE_ID, FIRM, creation());

        verify(timeline, never()).append(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------ completing

    @Test
    void completionConfirmsAgainstStorageAndRecordsTheRealSize() {
        CaseDocument document = document(DocumentStatus.UPLOADING);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(storage.head(KEY)).thenReturn(
                Optional.of(new ObjectStorageService.StoredObject(KEY, 2048, "application/pdf")));
        when(policy.maxFileSize()).thenReturn(1_000_000L);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());

        CaseDocument completed = documentService.completeUpload(DOC_ID, FIRM);

        assertThat(completed.getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThat(completed.getUploadedAt()).isNotNull();
        assertThat(completed.getFileSize())
                .as("storage is the authority on size; the declared 1024 was only a claim")
                .isEqualTo(2048);
        verify(timeline).append(any(LegalCase.class), eq(CaseEventType.DOCUMENT_UPLOADED),
                eq(ACTOR), any());
    }

    @Test
    @DisplayName("the client's word is not evidence: no object means no completion")
    void refusesToCompleteWhenNothingWasUploaded() {
        CaseDocument document = document(DocumentStatus.UPLOADING);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(storage.head(KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.completeUpload(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADING);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("an oversized object is refused at completion — the real size ceiling")
    void refusesAnObjectLargerThanThePolicyAllows() {
        CaseDocument document = document(DocumentStatus.UPLOADING);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(storage.head(KEY)).thenReturn(
                Optional.of(new ObjectStorageService.StoredObject(KEY, 9_000_000L, "application/pdf")));
        when(policy.maxFileSize()).thenReturn(1_000_000L);

        assertThatThrownBy(() -> documentService.completeUpload(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        assertThat(document.getStatus())
                .as("a presigned PUT cannot enforce size, so this is where an under-declared "
                        + "upload is caught")
                .isEqualTo(DocumentStatus.FAILED);
        verify(failureRecorder, description(
                "the transition has to be committed outside this transaction, or the "
                        + "exception below rolls it away and the document stays UPLOADING"))
                .markFailed(eq(DOC_ID), eq(FIRM), anyString());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void refusesAnEmptyObject() {
        CaseDocument document = document(DocumentStatus.UPLOADING);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(storage.head(KEY)).thenReturn(
                Optional.of(new ObjectStorageService.StoredObject(KEY, 0, "application/pdf")));
        when(policy.maxFileSize()).thenReturn(1_000_000L);

        assertThatThrownBy(() -> documentService.completeUpload(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        verify(failureRecorder).markFailed(eq(DOC_ID), eq(FIRM), anyString());
    }

    @Test
    @DisplayName("completing twice is a no-op: no second transition, no second event")
    void completionIsIdempotent() {
        CaseDocument document = document(DocumentStatus.AVAILABLE);
        Instant firstUpload = document.getUploadedAt();
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));

        CaseDocument again = documentService.completeUpload(DOC_ID, FIRM);

        assertThat(again.getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
        assertThat(again.getUploadedAt())
                .as("a retry must not restamp the upload time")
                .isEqualTo(firstUpload);
        verify(storage, never()).head(anyString());
        verify(eventPublisher, never()).publish(any());
        verify(timeline, never()).append(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failed document is not a retry candidate — that is a 409, not a no-op")
    void refusesToCompleteAFailedDocument() {
        CaseDocument document = document(DocumentStatus.FAILED);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.completeUpload(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void publishesUploadCompletedWithTheRealSize() {
        CaseDocument document = document(DocumentStatus.UPLOADING);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(storage.head(KEY)).thenReturn(
                Optional.of(new ObjectStorageService.StoredObject(KEY, 2048, "application/pdf")));
        when(policy.maxFileSize()).thenReturn(1_000_000L);
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());

        documentService.completeUpload(DOC_ID, FIRM);

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        DocumentUploadCompletedEvent event = (DocumentUploadCompletedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("document.upload_completed");
        assertThat(event.getSize()).isEqualTo(2048);
    }

    // ------------------------------------------------------------------------ downloading

    @Test
    void issuesADownloadLinkForAnAvailableDocument() {
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document(DocumentStatus.AVAILABLE)));
        when(storage.presignDownload(KEY, "Written statement.pdf")).thenReturn(url());

        DocumentService.DownloadTicket ticket = documentService.download(DOC_ID, FIRM);

        assertThat(ticket.url().url()).isEqualTo("https://example.invalid/signed");
        verify(storage).presignDownload(eq(KEY), eq("Written statement.pdf"));
    }

    @Test
    @DisplayName("an unconfirmed document has no file, so it gets no link")
    void refusesToIssueALinkForAnUploadingDocument() {
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document(DocumentStatus.UPLOADING)));

        assertThatThrownBy(() -> documentService.download(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(storage, never()).presignDownload(anyString(), anyString());
    }

    @Test
    void recordsWhoAskedForTheFile() {
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document(DocumentStatus.AVAILABLE)));
        when(storage.presignDownload(anyString(), anyString())).thenReturn(url());

        documentService.download(DOC_ID, FIRM);

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        DocumentDownloadRequestedEvent event = (DocumentDownloadRequestedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("document.download_requested");
        assertThat(event.getActorUserId()).isEqualTo(ACTOR);
    }

    // -------------------------------------------------------------------- tenant scoping

    @Test
    @DisplayName("another firm's document is not found, not forbidden")
    void aForeignDocumentIsNotFound() {
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, OTHER_FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.requireLive(DOC_ID, OTHER_FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("the guard still fires if a repository ever returns a foreign row")
    void guardsAgainstAQueryThatForgotTheTenantPredicate() {
        CaseDocument foreign = document(DocumentStatus.AVAILABLE);
        foreign.setOrganizationId(OTHER_FIRM);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> documentService.requireLive(DOC_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);
    }

    // ------------------------------------------------------------------ update and delete

    @Test
    void updateRefusesAStaleVersion() {
        CaseDocument document = document(DocumentStatus.AVAILABLE);
        document.setVersion(4L);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.update(DOC_ID, FIRM,
                new UpdateDocumentRequest("Renamed.pdf", null, 3L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        assertThat(document.getOriginalFilename()).isEqualTo("Written statement.pdf");
    }

    @Test
    @DisplayName("an edit changes the name and the note, and nothing about the stored object")
    void updateTouchesOnlyTheTwoEditableFields() {
        CaseDocument document = document(DocumentStatus.AVAILABLE);
        document.setVersion(0L);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));

        documentService.update(DOC_ID, FIRM,
                new UpdateDocumentRequest("  Rejoinder.pdf  ", "Amended", 0L));

        assertThat(document.getOriginalFilename()).isEqualTo("Rejoinder.pdf");
        assertThat(document.getDescription()).isEqualTo("Amended");
        assertThat(document.getContentType()).isEqualTo("application/pdf");
        assertThat(document.getFileSize()).isEqualTo(1024);
        assertThat(document.getStorageKey()).isEqualTo(KEY);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.AVAILABLE);
        verify(policy).validateFilename("  Rejoinder.pdf  ");
    }

    @Test
    @DisplayName("deletion soft-deletes the metadata and does not touch storage inside the transaction")
    void deletionIsSoftAndDefersTheObjectRemoval() {
        CaseDocument document = document(DocumentStatus.AVAILABLE);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase());

        CaseDocument deleted = documentService.delete(DOC_ID, FIRM);

        assertThat(deleted.getStatus()).isEqualTo(DocumentStatus.DELETED);
        assertThat(deleted.getDeletedAt()).isNotNull();
        verify(documentRepository, never()).delete(any());
        verify(storage, never()).delete(anyString());
        verify(timeline).append(any(LegalCase.class), eq(CaseEventType.DOCUMENT_DELETED),
                eq(ACTOR), any());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DocumentDeletedEvent.class);
    }

    @Test
    void purgingRemovesTheObject() {
        when(documentRepository.findByIdAndOrganizationId(DOC_ID, FIRM))
                .thenReturn(Optional.of(document(DocumentStatus.DELETED)));

        documentService.purgeObject(DOC_ID, FIRM);

        verify(storage).delete(KEY);
    }

    @Test
    @DisplayName("a storage failure during purge is swallowed — the user's delete already worked")
    void purgingSurvivesAStorageFailure() {
        when(documentRepository.findByIdAndOrganizationId(DOC_ID, FIRM))
                .thenReturn(Optional.of(document(DocumentStatus.DELETED)));
        doThrow(new ObjectStorageException("bucket unreachable")).when(storage).delete(KEY);

        documentService.purgeObject(DOC_ID, FIRM);
    }

    @Test
    void updatePublishesDocumentUpdated() {
        CaseDocument document = document(DocumentStatus.AVAILABLE);
        document.setVersion(0L);
        when(documentRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DOC_ID, FIRM))
                .thenReturn(Optional.of(document));

        documentService.update(DOC_ID, FIRM, new UpdateDocumentRequest("Renamed.pdf", null, 0L));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DocumentUpdatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("document.updated");
    }
}
