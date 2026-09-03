package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.DeadlineRequest;
import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.domain.DeadlineType;
import com.juriscore.casemanagement.event.DeadlineCompletedEvent;
import com.juriscore.casemanagement.event.DeadlineCreatedEvent;
import com.juriscore.casemanagement.repository.DeadlineRepository;
import com.juriscore.casemanagement.support.CallerContext;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
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
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadlineServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID CASE_ID = UUID.randomUUID();
    private static final UUID DEADLINE_ID = UUID.randomUUID();
    private static final Instant DUE = Instant.now().plus(30, ChronoUnit.DAYS);

    @Mock
    private DeadlineRepository deadlineRepository;
    @Mock
    private CaseTimelineRecorder recorder;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private DeadlineService deadlineService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(UUID.randomUUID(), FIRM, Role.LAWYER);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static LegalCase legalCase() {
        LegalCase legalCase = new LegalCase();
        legalCase.setOrganizationId(FIRM);
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(UUID.randomUUID());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
        return legalCase;
    }

    private static Deadline deadline() {
        Deadline deadline = new Deadline();
        deadline.setOrganizationId(FIRM);
        deadline.setCaseId(CASE_ID);
        deadline.setTitle("File the written statement");
        deadline.setDeadlineType(DeadlineType.COURT);
        deadline.setDueAt(DUE);
        deadline.setStatus(DeadlineStatus.OPEN);
        return deadline;
    }

    private static DeadlineRequest request(Long version) {
        return new DeadlineRequest("  File the written statement  ", "Within 30 days",
                DeadlineType.COURT, DUE, "Order dated 1 September", version);
    }

    @Test
    void createsADeadlineInTheOpenState() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(deadlineRepository.save(any(Deadline.class))).thenAnswer(call -> call.getArgument(0));

        Deadline created = deadlineService.create(CASE_ID, FIRM, request(null));

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getStatus()).isEqualTo(DeadlineStatus.OPEN);
        assertThat(created.getTitle()).isEqualTo("File the written statement");
        assertThat(created.getDueAt())
                .as("the date is taken as given — Phase 3 computes nothing from statute")
                .isEqualTo(DUE);
        assertThat(created.getSource()).isEqualTo("Order dated 1 September");
    }

    @Test
    void creationRecordsAndPublishes() {
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());
        when(deadlineRepository.save(any(Deadline.class))).thenAnswer(call -> call.getArgument(0));

        deadlineService.create(CASE_ID, FIRM, request(null));

        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.DEADLINE_CREATED), any());
        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DeadlineCreatedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("deadline.created");
    }

    @Test
    @DisplayName("another firm's matter stops creation before anything is written")
    void refusesToRecordAgainstAForeignCase() {
        when(recorder.requireCase(CASE_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.CASE_NOT_FOUND));

        assertThatThrownBy(() -> deadlineService.create(CASE_ID, FIRM, request(null)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CASE_NOT_FOUND);

        verify(deadlineRepository, never()).save(any());
    }

    @Test
    void meetingADeadlineStampsItRecordsItAndPublishes() {
        Deadline deadline = deadline();
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        deadlineService.changeStatus(DEADLINE_ID, FIRM, DeadlineStatus.COMPLETED);

        assertThat(deadline.getStatus()).isEqualTo(DeadlineStatus.COMPLETED);
        assertThat(deadline.getCompletedAt()).isNotNull();
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.DEADLINE_COMPLETED), any());

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(DeadlineCompletedEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("deadline.completed");
    }

    @Test
    void withdrawingADeadlineIsRecordedButNotPublishedAsCompletion() {
        Deadline deadline = deadline();
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));
        when(recorder.requireCase(CASE_ID, FIRM)).thenReturn(legalCase());

        deadlineService.changeStatus(DEADLINE_ID, FIRM, DeadlineStatus.CANCELLED);

        assertThat(deadline.getCompletedAt()).isNull();
        verify(recorder).append(any(LegalCase.class), eq(CaseEventType.DEADLINE_CANCELLED), any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void anIllegalTransitionLeavesNoTrace() {
        Deadline deadline = deadline();
        deadline.transitionTo(DeadlineStatus.COMPLETED, Instant.now());
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));

        assertThatThrownBy(() -> deadlineService.changeStatus(DEADLINE_ID, FIRM, DeadlineStatus.OPEN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(recorder, never()).append(any(), any(), any());
    }

    @Test
    void removalIsSoft() {
        Deadline deadline = deadline();
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));

        assertThat(deadlineService.remove(DEADLINE_ID, FIRM).isDeleted()).isTrue();
        verify(deadlineRepository, never()).delete(any());
        verify(deadlineRepository, never()).deleteById(any());
    }

    @Test
    void aForeignDeadlineIsNotFound() {
        when(deadlineRepository.findByIdAndOrganizationId(DEADLINE_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> deadlineService.getScoped(DEADLINE_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void updateRefusesAStaleVersion() {
        Deadline deadline = deadline();
        deadline.setVersion(4L);
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));

        assertThatThrownBy(() -> deadlineService.update(DEADLINE_ID, FIRM, request(3L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void updateAppliesChangesWhenTheVersionMatches() {
        Deadline deadline = deadline();
        deadline.setVersion(4L);
        when(deadlineRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(DEADLINE_ID, FIRM))
                .thenReturn(Optional.of(deadline));

        Deadline updated = deadlineService.update(DEADLINE_ID, FIRM, new DeadlineRequest(
                "File the rejoinder", null, DeadlineType.INTERNAL, DUE, null, 4L));

        assertThat(updated.getTitle()).isEqualTo("File the rejoinder");
        assertThat(updated.getDeadlineType()).isEqualTo(DeadlineType.INTERNAL);
        assertThat(updated.getStatus())
                .as("an edit does not move the deadline through its lifecycle")
                .isEqualTo(DeadlineStatus.OPEN);
    }
}
