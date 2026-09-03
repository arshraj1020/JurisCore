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
 * A piece of work on a matter.
 *
 * <p>{@code assignedToUserId} points into {@code identity.users} — no foreign key, and
 * validated through identity's service API before it is written. It is nullable: work
 * often exists before anybody has been given it, and forcing an assignee would mean
 * inventing one.
 *
 * <p>Removal is soft, following the client precedent in casework: a task that was on a
 * matter is part of what happened on that matter, and its timeline entries have to keep
 * making sense.
 */
@Entity
@Table(name = "tasks", schema = "case_management")
@Getter
@Setter
@NoArgsConstructor
public class Task extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 32)
    private TaskPriority priority;

    @Column(name = "assigned_to_user_id")
    private UUID assignedToUserId;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }

    /**
     * Moves the task, keeping {@code completedAt} in step with the status.
     *
     * <p>{@code ck_tasks_completed_at} asserts the same thing in the database, so a bug
     * here is a failed insert rather than a row that quietly disagrees with itself.
     */
    public void transitionTo(TaskStatus target, Instant when) {
        TaskStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        this.completedAt = target == TaskStatus.COMPLETED ? when : null;
    }
}
