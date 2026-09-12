package com.juriscore.legalresearch.event;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import com.juriscore.documents.event.DocumentUploadCompletedEvent;
import com.juriscore.documents.service.DocumentService;
import com.juriscore.legalresearch.domain.LegalJudgment;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import com.juriscore.legalresearch.service.JudgmentIngestionOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgmentIngestionListenerTest {

    @Mock
    private LegalJudgmentRepository judgmentRepository;
    @Mock
    private DocumentService documentService;
    @Mock
    private JudgmentIngestionOrchestrator orchestrator;

    private JudgmentIngestionListener listener;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID judgmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new JudgmentIngestionListener(judgmentRepository, documentService, orchestrator);
    }

    @Test
    void registrationStartsIngestionImmediatelyWhenTheDocumentIsAlreadyAvailable() {
        CaseDocument document = new CaseDocument();
        document.setStatus(DocumentStatus.AVAILABLE);
        when(documentService.findLive(documentId, organizationId)).thenReturn(document);

        listener.onJudgmentRegistered(new JudgmentRegisteredEvent(organizationId, judgmentId, documentId));

        verify(orchestrator).ingest(judgmentId);
    }

    @Test
    void registrationDoesNotStartIngestionWhileTheUploadIsStillInProgress() {
        CaseDocument document = new CaseDocument();
        document.setStatus(DocumentStatus.UPLOADING);
        when(documentService.findLive(documentId, organizationId)).thenReturn(document);

        listener.onJudgmentRegistered(new JudgmentRegisteredEvent(organizationId, judgmentId, documentId));

        verify(orchestrator, never()).ingest(any());
    }

    @Test
    void uploadCompletionStartsIngestionForAMatchingRegisteredJudgment() {
        LegalJudgment judgment = new LegalJudgment();
        judgment.setId(judgmentId);
        when(judgmentRepository.findBySourceDocumentIdAndOrganizationId(documentId, organizationId))
                .thenReturn(Optional.of(judgment));

        listener.onDocumentUploadCompleted(new DocumentUploadCompletedEvent(
                organizationId, documentId, UUID.randomUUID(), "judgment.pdf", "application/pdf", 100L));

        verify(orchestrator).ingest(judgmentId);
    }

    @Test
    void uploadCompletionOfAnOrdinaryDocumentDoesNothing() {
        when(judgmentRepository.findBySourceDocumentIdAndOrganizationId(documentId, organizationId))
                .thenReturn(Optional.empty());

        listener.onDocumentUploadCompleted(new DocumentUploadCompletedEvent(
                organizationId, documentId, UUID.randomUUID(), "invoice.pdf", "application/pdf", 100L));

        verify(orchestrator, never()).ingest(any());
    }

    private static UUID any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
