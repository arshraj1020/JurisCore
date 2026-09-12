package com.juriscore.legalresearch.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.Role;
import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.service.DocumentService;
import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalJudgment;
import com.juriscore.legalresearch.event.JudgmentRegisteredEvent;
import com.juriscore.legalresearch.repository.LegalJudgmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JudgmentRegistrationServiceTest {

    @Mock
    private DocumentService documentService;
    @Mock
    private LegalJudgmentRepository judgmentRepository;
    @Mock
    private EventPublisher eventPublisher;

    private JudgmentRegistrationService service;

    private final UUID organizationId = UUID.randomUUID();
    private final UUID otherOrganizationId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new JudgmentRegistrationService(documentService, judgmentRepository, eventPublisher);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(), organizationId, "lawyer@firm.test", Role.LAWYER),
                null, java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void registeringAnAvailableDocumentSavesAPendingJudgmentAndPublishesAnEvent() {
        CaseDocument document = new CaseDocument();
        document.setId(documentId);
        document.setOrganizationId(organizationId);
        when(documentService.requireLive(documentId, organizationId)).thenReturn(document);
        when(judgmentRepository.saveAndFlush(any(LegalJudgment.class))).thenAnswer(inv -> {
            LegalJudgment judgment = inv.getArgument(0);
            judgment.setId(UUID.randomUUID());
            return judgment;
        });

        LegalJudgment result = service.register(documentId, organizationId);

        assertThat(result.getExtractionStatus()).isEqualTo(JudgmentExtractionStatus.PENDING);
        assertThat(result.getSourceDocumentId()).isEqualTo(documentId);
        assertThat(result.getOrganizationId()).isEqualTo(organizationId);

        var captor = org.mockito.ArgumentCaptor.forClass(JudgmentRegisteredEvent.class);
        verify(eventPublisher).publish(captor.capture());
        assertThat(captor.getValue().getSourceDocumentId()).isEqualTo(documentId);
        assertThat(captor.getValue().organizationId()).isEqualTo(organizationId);
    }

    @Test
    void registeringTheSameDocumentTwiceIsAConflictNotASecondRow() {
        CaseDocument document = new CaseDocument();
        document.setId(documentId);
        when(documentService.requireLive(documentId, organizationId)).thenReturn(document);
        when(judgmentRepository.saveAndFlush(any(LegalJudgment.class)))
                .thenThrow(new DataIntegrityViolationException("uk_legal_judgments_source_document"));

        assertThatThrownBy(() -> service.register(documentId, organizationId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void registeringADocumentFromAnotherFirmIsRefusedBeforeAnyJudgmentRowIsWritten() {
        // Tenant isolation here is entirely DocumentService#requireLive's — it throws for
        // a document belonging to another organization, and this service must not catch
        // that and try anyway.
        when(documentService.requireLive(documentId, organizationId))
                .thenThrow(ApiException.notFound(ErrorCode.DOCUMENT_NOT_FOUND, documentId));

        assertThatThrownBy(() -> service.register(documentId, organizationId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DOCUMENT_NOT_FOUND);

        verify(judgmentRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void requireByIdRefusesAJudgmentThatBelongsToAnotherFirmEvenIfTheRepositoryReturnsOne() {
        // Simulates the repository predicate somehow failing to filter correctly — the
        // second line of defence (TenantGuard) has to catch it independently, the same
        // redundancy every other module's *Access class relies on.
        UUID judgmentId = UUID.randomUUID();
        LegalJudgment judgment = new LegalJudgment();
        judgment.setId(judgmentId);
        judgment.setOrganizationId(otherOrganizationId);
        when(judgmentRepository.findByIdAndOrganizationId(judgmentId, organizationId))
                .thenReturn(Optional.of(judgment));

        assertThatThrownBy(() -> service.requireById(judgmentId, organizationId))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
