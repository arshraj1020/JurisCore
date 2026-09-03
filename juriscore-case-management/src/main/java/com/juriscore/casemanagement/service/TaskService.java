package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateTaskRequest;
import com.juriscore.casemanagement.api.dto.UpdateTaskRequest;
import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskStatus;
import com.juriscore.casemanagement.event.TaskCompletedEvent;
import com.juriscore.casemanagement.event.TaskCreatedEvent;
import com.juriscore.casemanagement.repository.TaskRepository;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Work on a matter. */
@Service
@RequiredArgsConstructor
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;
    private final FirmMemberDirectory firmMembers;
    private final CaseTimelineRecorder recorder;
    private final EventPublisher eventPublisher;

    @Transactional
    public Task create(UUID caseId, UUID organizationId, CreateTaskRequest request) {
        LegalCase legalCase = recorder.requireCase(caseId, organizationId);
        UUID assignee = validatedAssignee(request.assignedToUserId(), organizationId);

        Task task = new Task();
        task.setOrganizationId(organizationId);
        task.setCaseId(legalCase.getId());
        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setStatus(TaskStatus.TODO);
        task.setPriority(request.priority());
        task.setAssignedToUserId(assignee);
        task.setDueAt(request.dueAt());

        Task saved = taskRepository.save(task);
        recorder.append(legalCase, CaseEventType.TASK_CREATED, "Task created: " + saved.getTitle());

        log.info("Task {} created on case {} in organization {}", saved.getId(), caseId, organizationId);
        eventPublisher.publish(new TaskCreatedEvent(organizationId, saved.getId(), legalCase.getId(),
                saved.getTitle(), assignee));
        return saved;
    }

    /** Includes removed tasks, so a timeline entry that names one still resolves. */
    @Transactional(readOnly = true)
    public Task getScoped(UUID taskId, UUID organizationId) {
        Task task = taskRepository.findByIdAndOrganizationId(taskId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.TASK_NOT_FOUND, taskId));
        TenantGuard.check(task, ErrorCode.TASK_NOT_FOUND);
        return task;
    }

    /** A task that can still be acted on. A removed one answers the same not-found. */
    @Transactional(readOnly = true)
    public Task requireLive(UUID taskId, UUID organizationId) {
        Task task = taskRepository.findByIdAndOrganizationIdAndDeletedAtIsNull(taskId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.TASK_NOT_FOUND, taskId));
        TenantGuard.check(task, ErrorCode.TASK_NOT_FOUND);
        return task;
    }

    @Transactional(readOnly = true)
    public Page<Task> listForCase(UUID caseId, UUID organizationId, TaskStatus status,
                                  Pageable pageable) {
        LegalCase legalCase = recorder.requireCase(caseId, organizationId);
        return status == null
                ? taskRepository.findByOrganizationIdAndCaseIdAndDeletedAtIsNull(
                        organizationId, legalCase.getId(), pageable)
                : taskRepository.findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNull(
                        organizationId, legalCase.getId(), status, pageable);
    }

    /**
     * Edits a task's details. Never its status — that has its own endpoint, so a routine
     * edit cannot quietly complete somebody's work.
     */
    @Transactional
    public Task update(UUID taskId, UUID organizationId, UpdateTaskRequest request) {
        Task task = requireLive(taskId, organizationId);
        OptimisticVersion.require(task, request.version());

        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        task.setPriority(request.priority());
        task.setAssignedToUserId(validatedAssignee(request.assignedToUserId(), organizationId));
        task.setDueAt(request.dueAt());
        return task;
    }

    /**
     * Moves the task.
     *
     * <p>Only the two endings reach the timeline. A task bouncing between TODO and
     * IN_PROGRESS three times in an afternoon is not what happened on the matter, and
     * writing all of it would bury the entries that are.
     */
    @Transactional
    public Task changeStatus(UUID taskId, UUID organizationId, TaskStatus target) {
        Task task = requireLive(taskId, organizationId);
        TaskStatus previous = task.getStatus();
        Instant now = Instant.now();

        task.transitionTo(target, now);

        if (target == TaskStatus.COMPLETED || target == TaskStatus.CANCELLED) {
            LegalCase legalCase = recorder.requireCase(task.getCaseId(), organizationId);
            CaseEventType entry = target == TaskStatus.COMPLETED
                    ? CaseEventType.TASK_COMPLETED
                    : CaseEventType.TASK_CANCELLED;
            recorder.append(legalCase, entry,
                    "Task " + target.name().toLowerCase() + ": " + task.getTitle());
        }
        if (target == TaskStatus.COMPLETED) {
            eventPublisher.publish(new TaskCompletedEvent(organizationId, taskId, task.getCaseId(),
                    task.getTitle(), task.getAssignedToUserId()));
        }

        log.info("Task {} moved from {} to {}", taskId, previous, target);
        return task;
    }

    /**
     * Removes a task from the live list without removing the row.
     *
     * <p>Soft, following the client precedent in casework: a task that was on a matter is
     * part of what happened on that matter, and the timeline entries naming it have to
     * keep making sense. Removing twice answers not-found, because the second call is
     * asking about something no longer in the set it can act on.
     */
    @Transactional
    public Task remove(UUID taskId, UUID organizationId) {
        Task task = requireLive(taskId, organizationId);
        task.markDeleted(Instant.now());
        log.info("Task {} removed in organization {}", taskId, organizationId);
        return task;
    }

    /** Null means unassigned, which is allowed. A value must be active staff of this firm. */
    private UUID validatedAssignee(UUID assignedToUserId, UUID organizationId) {
        if (assignedToUserId == null) {
            return null;
        }
        firmMembers.requireAssignableMember(assignedToUserId, organizationId);
        return assignedToUserId;
    }
}
