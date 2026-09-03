package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateReminderRequest;
import com.juriscore.casemanagement.api.dto.UpdateReminderRequest;
import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.domain.DeadlineType;
import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderChannel;
import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskPriority;
import com.juriscore.casemanagement.domain.TaskStatus;
import com.juriscore.casemanagement.event.ReminderScheduledEvent;
import com.juriscore.casemanagement.repository.ReminderRepository;
import com.juriscore.casemanagement.support.CallerContext;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    private static final UUID FIRM = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID DEADLINE_ID = UUID.randomUUID();
    private static final UUID REMINDER_ID = UUID.randomUUID();
    private static final Instant FUTURE = Instant.now().plus(2, ChronoUnit.DAYS);
    private static final Instant PAST = Instant.now().minus(1, ChronoUnit.HOURS);

    @Mock
    private ReminderRepository reminderRepository;
    @Mock
    private TaskService taskService;
    @Mock
    private DeadlineService deadlineService;
    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private ReminderService reminderService;

    @BeforeEach
    void signIn() {
        CallerContext.signIn(UUID.randomUUID(), FIRM, Role.LAWYER);
    }

    @AfterEach
    void signOut() {
        CallerContext.clear();
    }

    private static Task task(TaskStatus status) {
        Task task = new Task();
        // The service writes task.getId(), not the path parameter — it stores the id of the
        // entity it actually resolved, so a fixture without one is not the object under test.
        task.setId(TASK_ID);
        task.setOrganizationId(FIRM);
        task.setCaseId(UUID.randomUUID());
        task.setTitle("Draft the reply");
        task.setStatus(status);
        task.setPriority(TaskPriority.MEDIUM);
        return task;
    }

    private static Deadline deadline(DeadlineStatus status) {
        Deadline deadline = new Deadline();
        deadline.setId(DEADLINE_ID);
        deadline.setOrganizationId(FIRM);
        deadline.setCaseId(UUID.randomUUID());
        deadline.setTitle("File the written statement");
        deadline.setDeadlineType(DeadlineType.COURT);
        deadline.setDueAt(FUTURE);
        deadline.setStatus(status);
        return deadline;
    }

    private static Reminder reminder(ReminderStatus status) {
        Reminder reminder = new Reminder();
        reminder.setOrganizationId(FIRM);
        reminder.setTaskId(TASK_ID);
        reminder.setRemindAt(FUTURE);
        reminder.setStatus(status);
        reminder.setChannel(ReminderChannel.IN_APP);
        return reminder;
    }

    private static CreateReminderRequest creation(Instant when) {
        return new CreateReminderRequest(when, ReminderChannel.IN_APP, "Chase the draft");
    }

    @Test
    void schedulesAReminderOnALiveTask() {
        when(taskService.requireLive(TASK_ID, FIRM)).thenReturn(task(TaskStatus.TODO));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        Reminder created = reminderService.scheduleForTask(TASK_ID, FIRM, creation(FUTURE));

        assertThat(created.getOrganizationId()).isEqualTo(FIRM);
        assertThat(created.getStatus()).isEqualTo(ReminderStatus.SCHEDULED);
        assertThat(created.getTaskId()).isEqualTo(TASK_ID);
        assertThat(created.getDeadlineId())
                .as("ck_reminders_one_target insists on exactly one target")
                .isNull();
        assertThat(created.getTriggeredAt()).isNull();
    }

    @Test
    void schedulesAReminderOnAnOpenDeadline() {
        when(deadlineService.requireLive(DEADLINE_ID, FIRM)).thenReturn(deadline(DeadlineStatus.OPEN));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        Reminder created = reminderService.scheduleForDeadline(DEADLINE_ID, FIRM, creation(FUTURE));

        assertThat(created.getDeadlineId()).isEqualTo(DEADLINE_ID);
        assertThat(created.getTaskId()).isNull();
    }

    @Test
    void publishesReminderScheduled() {
        when(taskService.requireLive(TASK_ID, FIRM)).thenReturn(task(TaskStatus.TODO));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(call -> call.getArgument(0));

        reminderService.scheduleForTask(TASK_ID, FIRM, creation(FUTURE));

        ArgumentCaptor<DomainEvent> published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(ReminderScheduledEvent.class);
        assertThat(published.getValue().eventType()).isEqualTo("reminder.scheduled");
    }

    @Test
    @DisplayName("a reminder in the past would fire on the very next sweep, which nobody meant")
    void refusesAReminderInThePast() {
        when(taskService.requireLive(TASK_ID, FIRM)).thenReturn(task(TaskStatus.TODO));

        assertThatThrownBy(() -> reminderService.scheduleForTask(TASK_ID, FIRM, creation(PAST)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);

        verify(reminderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a reminder on finished work would never help anybody")
    void refusesAReminderOnATerminalTask() {
        when(taskService.requireLive(TASK_ID, FIRM)).thenReturn(task(TaskStatus.COMPLETED));

        assertThatThrownBy(() -> reminderService.scheduleForTask(TASK_ID, FIRM, creation(FUTURE)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        verify(reminderRepository, never()).save(any());
    }

    @Test
    void refusesAReminderOnAClosedDeadline() {
        when(deadlineService.requireLive(DEADLINE_ID, FIRM))
                .thenReturn(deadline(DeadlineStatus.COMPLETED));

        assertThatThrownBy(() -> reminderService.scheduleForDeadline(DEADLINE_ID, FIRM, creation(FUTURE)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("another firm's task is not found — setting a reminder is not a way to probe ids")
    void refusesAForeignTarget() {
        when(taskService.requireLive(TASK_ID, FIRM))
                .thenThrow(new ApiException(ErrorCode.TASK_NOT_FOUND));

        assertThatThrownBy(() -> reminderService.scheduleForTask(TASK_ID, FIRM, creation(FUTURE)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TASK_NOT_FOUND);

        verify(reminderRepository, never()).save(any());
    }

    @Test
    void cancellingStopsTheReminder() {
        Reminder reminder = reminder(ReminderStatus.SCHEDULED);
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.of(reminder));

        Reminder cancelled = reminderService.cancel(REMINDER_ID, FIRM);

        assertThat(cancelled.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        assertThat(cancelled.getTriggeredAt()).isNull();
        verify(reminderRepository, never()).delete(any());
    }

    @Test
    @DisplayName("cancelling twice is a no-op: the caller wanted it off, and it is off")
    void cancellingTwiceIsIdempotent() {
        Reminder reminder = reminder(ReminderStatus.CANCELLED);
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.of(reminder));

        assertThat(reminderService.cancel(REMINDER_ID, FIRM).getStatus())
                .isEqualTo(ReminderStatus.CANCELLED);
    }

    @Test
    @DisplayName("a reminder that already fired cannot be rescheduled")
    void refusesToRescheduleASentReminder() {
        Reminder reminder = reminder(ReminderStatus.SENT);
        reminder.setVersion(0L);
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.of(reminder));

        assertThatThrownBy(() -> reminderService.update(REMINDER_ID, FIRM,
                new UpdateReminderRequest(FUTURE, ReminderChannel.IN_APP, null, 0L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void updateRefusesAStaleVersion() {
        Reminder reminder = reminder(ReminderStatus.SCHEDULED);
        reminder.setVersion(4L);
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.of(reminder));

        assertThatThrownBy(() -> reminderService.update(REMINDER_ID, FIRM,
                new UpdateReminderRequest(FUTURE, ReminderChannel.EMAIL, null, 3L)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    void updateMovesTheTimeButNeverTheTarget() {
        Reminder reminder = reminder(ReminderStatus.SCHEDULED);
        reminder.setVersion(0L);
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.of(reminder));
        Instant later = FUTURE.plus(1, ChronoUnit.DAYS);

        Reminder updated = reminderService.update(REMINDER_ID, FIRM,
                new UpdateReminderRequest(later, ReminderChannel.EMAIL, "Chase again", 0L));

        assertThat(updated.getRemindAt()).isEqualTo(later);
        assertThat(updated.getChannel()).isEqualTo(ReminderChannel.EMAIL);
        assertThat(updated.getTaskId())
                .as("a reminder belongs to the thing it was set on")
                .isEqualTo(TASK_ID);
    }

    @Test
    void aForeignReminderIsNotFound() {
        when(reminderRepository.findByIdAndOrganizationId(REMINDER_ID, FIRM))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reminderService.getScoped(REMINDER_ID, FIRM))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
