package com.juriscore.casemanagement.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A time at which somebody wants to be reminded about a task or a deadline.
 *
 * <p>Exactly one target, enforced by {@code ck_reminders_one_target} rather than by the
 * five call sites that would otherwise each have to agree.
 *
 * <p>Phase 3 delivers nothing. When a reminder comes due the scheduler claims it,
 * marks it {@link ReminderStatus#SENT} and publishes {@code reminder.triggered}; the
 * consumer that turns that into an email belongs to a later phase. {@link #channel}
 * records what the firm intended so that consumer has something to act on.
 */
@Entity
@Table(name = "reminders", schema = "case_management")
@Getter
@Setter
@NoArgsConstructor
public class Reminder extends TenantAwareEntity {

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "deadline_id", updatable = false)
    private UUID deadlineId;

    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReminderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    private ReminderChannel channel;

    @Column(name = "note", length = 500)
    private String note;

    /** When it was published as an event. Not when anybody read it. */
    @Column(name = "triggered_at")
    private Instant triggeredAt;

    public boolean targetsTask() {
        return taskId != null;
    }

    public void transitionTo(ReminderStatus target, Instant when) {
        ReminderStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        this.triggeredAt = target == ReminderStatus.SENT ? when : null;
    }
}
