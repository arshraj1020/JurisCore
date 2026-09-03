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
 * A date a matter has to meet.
 *
 * <p>A first-class row rather than a task with a due date, because the two are not the
 * same thing: a task is work somebody does and can be reassigned or dropped, while a
 * deadline is an obligation that exists whether or not anybody is working on it. Filing
 * dates outlive the tasks created to meet them.
 *
 * <p>Phase 3 does no date arithmetic. {@code dueAt} is whatever a person entered, and
 * {@code source} is free text recording where they got it — there is no statutory rule
 * engine here and nothing pretends there is.
 */
@Entity
@Table(name = "deadlines", schema = "case_management")
@Getter
@Setter
@NoArgsConstructor
public class Deadline extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_type", nullable = false, length = 32)
    private DeadlineType deadlineType;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeadlineStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Where the date came from — an order, a direction, a rule. Free text on purpose. */
    @Column(name = "source", length = 255)
    private String source;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }

    public void transitionTo(DeadlineStatus target, Instant when) {
        DeadlineStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        this.completedAt = target == DeadlineStatus.COMPLETED ? when : null;
    }
}
