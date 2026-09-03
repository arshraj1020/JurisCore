package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderChannel;
import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.event.ReminderTriggeredEvent;
import com.juriscore.casemanagement.repository.ReminderRepository;
import com.juriscore.casemanagement.scheduler.ReminderProperties;
import com.juriscore.common.event.DomainEvent;
import com.juriscore.common.event.EventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweep's logic, with the lock mocked out.
 *
 * <p>What a unit test can show here is the bookkeeping: what gets marked, what gets
 * published, what happens to a row that should not have been claimed. What it cannot show
 * is the property that actually matters — that two instances never claim the same row —
 * because that is a database row lock, and a mock has no locks. {@code ReminderClaimIT}
 * proves that against real PostgreSQL with real concurrent transactions.
 */
@ExtendWith(MockitoExtension.class)
class ReminderDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:15:30Z");

    @Mock
    private ReminderClaimer claimer;
    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private ReminderProperties properties;

    @InjectMocks
    private ReminderDispatchService dispatchService;

    private static Reminder due(ReminderStatus status) {
        Reminder reminder = new Reminder();
        reminder.setOrganizationId(UUID.randomUUID());
        reminder.setTaskId(UUID.randomUUID());
        reminder.setRemindAt(NOW.minusSeconds(60));
        reminder.setStatus(status);
        reminder.setChannel(ReminderChannel.IN_APP);
        return reminder;
    }

    @Test
    void marksClaimedRemindersSentAndAnnouncesThem() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        Reminder first = due(ReminderStatus.SCHEDULED);
        Reminder second = due(ReminderStatus.SCHEDULED);
        when(claimer.claimDue(NOW, 100)).thenReturn(ids);
        when(reminderRepository.findByIdIn(ids)).thenReturn(List.of(first, second));

        int published = dispatchService.dispatchDue(NOW, 100);

        assertThat(published).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(second.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(first.getTriggeredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("'sent' means published as reminder.triggered, and nothing more")
    void publishesTheHandoverEvent() {
        List<UUID> ids = List.of(UUID.randomUUID());
        when(claimer.claimDue(NOW, 100)).thenReturn(ids);
        when(reminderRepository.findByIdIn(ids)).thenReturn(List.of(due(ReminderStatus.SCHEDULED)));

        dispatchService.dispatchDue(NOW, 100);

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(ReminderTriggeredEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("reminder.triggered");
    }

    @Test
    void anEmptySweepTouchesNothing() {
        when(claimer.claimDue(NOW, 100)).thenReturn(List.of());

        assertThat(dispatchService.dispatchDue(NOW, 100)).isZero();

        verify(reminderRepository, never()).findByIdIn(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("a row that is somehow no longer scheduled is skipped, not fired again")
    void skipsAnythingNotStillScheduled() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        Reminder alreadySent = due(ReminderStatus.SENT);
        alreadySent.setTriggeredAt(NOW.minusSeconds(3600));
        Reminder cancelled = due(ReminderStatus.CANCELLED);
        when(claimer.claimDue(NOW, 100)).thenReturn(ids);
        when(reminderRepository.findByIdIn(ids)).thenReturn(List.of(alreadySent, cancelled));

        int published = dispatchService.dispatchDue(NOW, 100);

        assertThat(published)
                .as("the row lock makes this impossible; skipping quietly is the safe reading "
                        + "of a state that should not exist")
                .isZero();
        assertThat(alreadySent.getTriggeredAt())
                .as("and the earlier firing must not be restamped")
                .isEqualTo(NOW.minusSeconds(3600));
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("the batch size is bounded, so a backlog is worked through over several sweeps")
    void takesTheBatchSizeFromConfiguration() {
        when(properties.getBatchSize()).thenReturn(25);
        when(claimer.claimDue(any(Instant.class), anyInt())).thenReturn(List.of());

        dispatchService.dispatchDue();

        verify(claimer).claimDue(any(Instant.class), org.mockito.ArgumentMatchers.eq(25));
    }
}
