package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.AssignLawyerRequest;
import com.juriscore.casework.domain.CaseAssignment;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.event.CaseLawyerAssignedEvent;
import com.juriscore.casework.event.CaseLawyerUnassignedEvent;
import com.juriscore.casework.repository.CaseAssignmentRepository;
import com.juriscore.casework.support.CallerContext;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseAssignmentServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID ASHA = UUID.randomUUID();
    private static final UUID RAVI = UUID.randomUUID();

    @Mock
    private CaseAccess caseAccess;
    @Mock
    private CaseAssignmentRepository assignmentRepository;
    @Mock
    private LawyerDirectory lawyerDirectory;
    @Mock
    private CaseTimelineService timeline;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private CaseAssignmentService assignmentService;

    private LegalCase legalCase;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(ACTOR, FIRM, Role.FIRM_ADMIN);
        legalCase = new LegalCase();
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(UUID.randomUUID());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private CaseAssignment assignment(UUID lawyerUserId, boolean lead) {
        CaseAssignment assignment = new CaseAssignment();
        assignment.setOrganizationId(FIRM);
        assignment.setCaseId(CASE_ID);
        assignment.setLawyerUserId(lawyerUserId);
        assignment.setLead(lead);
        assignment.setAssignedAt(Instant.now());
        return assignment;
    }

    private void caseExists() {
        when(caseAccess.require(CASE_ID, FIRM)).thenReturn(legalCase);
    }

    private void savesWhatItIsGiven() {
        when(assignmentRepository.saveAndFlush(any(CaseAssignment.class)))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("the first lawyer on a matter takes the lead without being asked to")
    void firstAssignmentBecomesLead() {
        caseExists();
        savesWhatItIsGiven();
        when(assignmentRepository.countByOrganizationIdAndCaseId(FIRM, null)).thenReturn(0L);

        CaseAssignment created = assignmentService.assign(CASE_ID, FIRM, ACTOR,
                new AssignLawyerRequest(ASHA, null));

        assertThat(created.isLead()).isTrue();
        assertThat(created.getLawyerUserId()).isEqualTo(ASHA);
        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getAssignedBy()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("a second lawyer joins without the lead, so a matter never has two")
    void secondAssignmentIsNotLeadByDefault() {
        caseExists();
        savesWhatItIsGiven();
        when(assignmentRepository.countByOrganizationIdAndCaseId(FIRM, null)).thenReturn(1L);

        CaseAssignment created = assignmentService.assign(CASE_ID, FIRM, ACTOR,
                new AssignLawyerRequest(RAVI, null));

        assertThat(created.isLead()).isFalse();
    }

    @Test
    @DisplayName("promoting a newcomer demotes the sitting lead first, in that order")
    void assigningANewLeadDemotesTheOldOne() {
        caseExists();
        savesWhatItIsGiven();
        when(assignmentRepository.countByOrganizationIdAndCaseId(FIRM, null)).thenReturn(1L);
        CaseAssignment sittingLead = assignment(ASHA, true);
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLeadTrue(FIRM, null))
                .thenReturn(Optional.of(sittingLead));

        CaseAssignment created = assignmentService.assign(CASE_ID, FIRM, ACTOR,
                new AssignLawyerRequest(RAVI, true));

        assertThat(sittingLead.isLead())
                .as("the demotion has to be flushed before the promotion, or the partial "
                        + "unique index on (case_id) where is_lead rejects the pair")
                .isFalse();
        assertThat(created.isLead()).isTrue();
    }

    @Test
    void refusesADuplicateAssignment() {
        caseExists();
        when(assignmentRepository.existsByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, ASHA))
                .thenReturn(true);

        assertThatThrownBy(() -> assignmentService.assign(CASE_ID, FIRM, ACTOR,
                new AssignLawyerRequest(ASHA, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.DUPLICATE_RESOURCE);

        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a lawyer the directory refuses is rejected before anything is written")
    void anUnassignableLawyerIsRejectedBeforeAnyWrite() {
        caseExists();
        doThrow(new ApiException(ErrorCode.USER_NOT_FOUND))
                .when(lawyerDirectory).requireAssignableLawyer(ASHA, FIRM);

        assertThatThrownBy(() -> assignmentService.assign(CASE_ID, FIRM, ACTOR,
                new AssignLawyerRequest(ASHA, null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(assignmentRepository, never()).saveAndFlush(any());
        verify(timeline, never()).append(any(), any(), any(), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void publishesLawyerAssigned() {
        caseExists();
        savesWhatItIsGiven();
        when(assignmentRepository.countByOrganizationIdAndCaseId(FIRM, null)).thenReturn(0L);

        assignmentService.assign(CASE_ID, FIRM, ACTOR, new AssignLawyerRequest(ASHA, null));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(CaseLawyerAssignedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("case.lawyer_assigned");
        verify(timeline).append(eq(legalCase), eq(CaseEventType.LAWYER_ASSIGNED), eq(ACTOR), any());
    }

    @Test
    void unassignsALawyerWhoIsNotLead() {
        caseExists();
        CaseAssignment junior = assignment(RAVI, false);
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, RAVI))
                .thenReturn(Optional.of(junior));

        assignmentService.unassign(CASE_ID, FIRM, ACTOR, RAVI, null);

        verify(assignmentRepository).delete(junior);
        verify(timeline).append(eq(legalCase), eq(CaseEventType.LAWYER_UNASSIGNED), eq(ACTOR), any());
    }

    @Test
    @DisplayName("the final lead cannot be removed — there is nobody to hand the matter to")
    void cannotRemoveTheFinalLead() {
        caseExists();
        CaseAssignment onlyLawyer = assignment(ASHA, true);
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, ASHA))
                .thenReturn(Optional.of(onlyLawyer));

        assertThatThrownBy(() -> assignmentService.unassign(CASE_ID, FIRM, ACTOR, ASHA, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("newLeadUserId")
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);

        verify(assignmentRepository, never()).delete(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void removingTheLeadPromotesTheNamedSuccessor() {
        caseExists();
        CaseAssignment lead = assignment(ASHA, true);
        CaseAssignment successor = assignment(RAVI, false);
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, ASHA))
                .thenReturn(Optional.of(lead));
        when(assignmentRepository.existsByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, RAVI))
                .thenReturn(true);
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, RAVI))
                .thenReturn(Optional.of(successor));
        when(assignmentRepository.saveAndFlush(any(CaseAssignment.class)))
                .thenAnswer(call -> call.getArgument(0));

        assignmentService.unassign(CASE_ID, FIRM, ACTOR, ASHA, RAVI);

        assertThat(successor.isLead()).isTrue();
        verify(assignmentRepository).delete(lead);

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        CaseLawyerUnassignedEvent event = (CaseLawyerUnassignedEvent) published.getValue();
        assertThat(event.eventType()).isEqualTo("case.lawyer_unassigned");
        assertThat(event.getNewLeadUserId()).isEqualTo(RAVI);
    }

    @Test
    @DisplayName("the successor has to already be on the matter")
    void refusesToPromoteSomebodyWhoIsNotAssigned() {
        caseExists();
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, ASHA))
                .thenReturn(Optional.of(assignment(ASHA, true)));
        when(assignmentRepository.existsByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, RAVI))
                .thenReturn(false);

        assertThatThrownBy(() -> assignmentService.unassign(CASE_ID, FIRM, ACTOR, ASHA, RAVI))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);

        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void refusesToPromoteTheLawyerBeingRemoved() {
        caseExists();
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, ASHA))
                .thenReturn(Optional.of(assignment(ASHA, true)));

        assertThatThrownBy(() -> assignmentService.unassign(CASE_ID, FIRM, ACTOR, ASHA, ASHA))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);

        verify(assignmentRepository, never()).delete(any());
    }

    @Test
    void unassigningSomebodyWhoIsNotOnTheMatterIsNotFound() {
        caseExists();
        when(assignmentRepository.findByOrganizationIdAndCaseIdAndLawyerUserId(FIRM, null, RAVI))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.unassign(CASE_ID, FIRM, ACTOR, RAVI, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
