package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The invariants live on the entities, so they are tested on the entities: a service that
 * forgets to ask the policy is a bug the service's own tests would not catch.
 *
 * <p>Each of the three timestamp pairs below is also a database check constraint, so a
 * bug here is a failed insert rather than a row that quietly disagrees with itself. These
 * tests assert the Java half; the migration tests assert the SQL half.
 */
class EntityInvariantsTest {

    private static final Instant WHEN = Instant.parse("2026-09-01T10:15:30Z");

    private static Hearing hearing() {
        Hearing hearing = new Hearing();
        hearing.setOrganizationId(UUID.randomUUID());
        hearing.setCaseId(UUID.randomUUID());
        hearing.setCourtId(UUID.randomUUID());
        hearing.setHearingType(HearingType.MENTION);
        hearing.setStatus(HearingStatus.SCHEDULED);
        hearing.setScheduledAt(WHEN);
        return hearing;
    }

    private static Task task() {
        Task task = new Task();
        task.setOrganizationId(UUID.randomUUID());
        task.setCaseId(UUID.randomUUID());
        task.setTitle("Draft the reply");
        task.setStatus(TaskStatus.TODO);
        task.setPriority(TaskPriority.MEDIUM);
        return task;
    }

    private static Deadline deadline() {
        Deadline deadline = new Deadline();
        deadline.setOrganizationId(UUID.randomUUID());
        deadline.setCaseId(UUID.randomUUID());
        deadline.setTitle("File the reply");
        deadline.setDeadlineType(DeadlineType.COURT);
        deadline.setDueAt(WHEN);
        deadline.setStatus(DeadlineStatus.OPEN);
        return deadline;
    }

    private static Reminder reminder() {
        Reminder reminder = new Reminder();
        reminder.setOrganizationId(UUID.randomUUID());
        reminder.setTaskId(UUID.randomUUID());
        reminder.setRemindAt(WHEN);
        reminder.setStatus(ReminderStatus.SCHEDULED);
        reminder.setChannel(ReminderChannel.IN_APP);
        return reminder;
    }

    // ------------------------------------------------------------------------ hearings

    @Test
    void anAdjournedHearingCanBeRelisted() {
        Hearing hearing = hearing();
        hearing.transitionTo(HearingStatus.ADJOURNED);
        hearing.transitionTo(HearingStatus.SCHEDULED);

        assertThat(hearing.getStatus()).isEqualTo(HearingStatus.SCHEDULED);
    }

    @Test
    void aCompletedHearingCannotBeReopenedAndIsUnchangedByTheAttempt() {
        Hearing hearing = hearing();
        hearing.transitionTo(HearingStatus.COMPLETED);

        assertThatThrownBy(() -> hearing.transitionTo(HearingStatus.SCHEDULED))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        assertThat(hearing.getStatus()).isEqualTo(HearingStatus.COMPLETED);
    }

    // --------------------------------------------------------------------------- tasks

    @Test
    @DisplayName("completing a task stamps completedAt; nothing else does")
    void onlyCompletionStampsATask() {
        Task task = task();
        task.transitionTo(TaskStatus.IN_PROGRESS, WHEN);
        assertThat(task.getCompletedAt())
                .as("ck_tasks_completed_at refuses a row where status and completed_at disagree")
                .isNull();

        task.transitionTo(TaskStatus.COMPLETED, WHEN);
        assertThat(task.getCompletedAt()).isEqualTo(WHEN);
    }

    @Test
    void cancellingATaskLeavesNoCompletionTime() {
        Task task = task();
        task.transitionTo(TaskStatus.CANCELLED, WHEN);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    void aFailedTaskTransitionLeavesTheStampAlone() {
        Task task = task();
        task.transitionTo(TaskStatus.COMPLETED, WHEN);

        assertThatThrownBy(() -> task.transitionTo(TaskStatus.IN_PROGRESS, WHEN))
                .isInstanceOf(ApiException.class);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getCompletedAt())
                .as("the refused reopen must not have cleared the completion time on its way out")
                .isEqualTo(WHEN);
    }

    @Test
    void softRemovalMarksTheTaskWithoutTouchingItsStatus() {
        Task task = task();
        task.markDeleted(WHEN);

        assertThat(task.isDeleted()).isTrue();
        assertThat(task.getDeletedAt()).isEqualTo(WHEN);
        assertThat(task.getStatus())
                .as("removing a task is not the same as finishing or abandoning it")
                .isEqualTo(TaskStatus.TODO);
    }

    // ----------------------------------------------------------------------- deadlines

    @Test
    void meetingADeadlineStampsIt() {
        Deadline deadline = deadline();
        deadline.transitionTo(DeadlineStatus.COMPLETED, WHEN);

        assertThat(deadline.getStatus()).isEqualTo(DeadlineStatus.COMPLETED);
        assertThat(deadline.getCompletedAt()).isEqualTo(WHEN);
    }

    @Test
    void withdrawingADeadlineDoesNot() {
        Deadline deadline = deadline();
        deadline.transitionTo(DeadlineStatus.CANCELLED, WHEN);

        assertThat(deadline.getCompletedAt()).isNull();
    }

    // ----------------------------------------------------------------------- reminders

    @Test
    @DisplayName("firing a reminder stamps triggeredAt — the time it was published, not delivered")
    void firingStampsTheReminder() {
        Reminder reminder = reminder();
        reminder.transitionTo(ReminderStatus.SENT, WHEN);

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(reminder.getTriggeredAt()).isEqualTo(WHEN);
    }

    @Test
    void cancellingAReminderLeavesNoTriggerTime() {
        Reminder reminder = reminder();
        reminder.transitionTo(ReminderStatus.CANCELLED, WHEN);

        assertThat(reminder.getTriggeredAt()).isNull();
    }

    @Test
    @DisplayName("a reminder that already fired cannot fire again")
    void aSentReminderRefusesASecondFiring() {
        Reminder reminder = reminder();
        reminder.transitionTo(ReminderStatus.SENT, WHEN);

        assertThatThrownBy(() -> reminder.transitionTo(ReminderStatus.SENT, WHEN))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void aReminderKnowsWhichKindOfThingItIsAttachedTo() {
        assertThat(reminder().targetsTask()).isTrue();

        Reminder onDeadline = reminder();
        onDeadline.setTaskId(null);
        onDeadline.setDeadlineId(UUID.randomUUID());
        assertThat(onDeadline.targetsTask()).isFalse();
    }
}
