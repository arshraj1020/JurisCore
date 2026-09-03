package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.api.dto.CreateReminderRequest;
import com.juriscore.casemanagement.api.dto.UpdateReminderRequest;
import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskStatus;
import com.juriscore.casemanagement.event.ReminderScheduledEvent;
import com.juriscore.casemanagement.repository.ReminderRepository;
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

/**
 * Reminders on tasks and deadlines.
 *
 * <p>Nothing here delivers anything. A reminder is a row and a time; when the time comes
 * the scheduler publishes an event. What turns that into an email belongs to a later
 * phase, and this class is careful never to imply otherwise.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository reminderRepository;
    private final TaskService taskService;
    private final DeadlineService deadlineService;
    private final EventPublisher eventPublisher;

    /**
     * Schedules a reminder on a task.
     *
     * <p>The task is resolved through its own service, so another firm's task — or one
     * that has been removed — answers {@code TASK_NOT_FOUND} exactly as it would if the
     * caller had asked for it directly. Setting a reminder is not a way to find out
     * which ids exist.
     */
    @Transactional
    public Reminder scheduleForTask(UUID taskId, UUID organizationId, CreateReminderRequest request) {
        Task task = taskService.requireLive(taskId, organizationId);
        if (task.getStatus() == TaskStatus.COMPLETED || task.getStatus() == TaskStatus.CANCELLED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "That task is already " + task.getStatus() + "; a reminder on it would never help");
        }
        requireFutureInstant(request.remindAt());

        Reminder reminder = newReminder(organizationId, request);
        reminder.setTaskId(task.getId());
        return persist(reminder, organizationId);
    }

    @Transactional
    public Reminder scheduleForDeadline(UUID deadlineId, UUID organizationId,
                                        CreateReminderRequest request) {
        Deadline deadline = deadlineService.requireLive(deadlineId, organizationId);
        if (deadline.getStatus() != DeadlineStatus.OPEN) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "That deadline is already " + deadline.getStatus()
                            + "; a reminder on it would never help");
        }
        requireFutureInstant(request.remindAt());

        Reminder reminder = newReminder(organizationId, request);
        reminder.setDeadlineId(deadline.getId());
        return persist(reminder, organizationId);
    }

    @Transactional(readOnly = true)
    public Reminder getScoped(UUID reminderId, UUID organizationId) {
        Reminder reminder = reminderRepository.findByIdAndOrganizationId(reminderId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.RESOURCE_NOT_FOUND, reminderId));
        TenantGuard.check(reminder, ErrorCode.RESOURCE_NOT_FOUND);
        return reminder;
    }

    @Transactional(readOnly = true)
    public Page<Reminder> list(UUID organizationId, ReminderStatus status, Pageable pageable) {
        return status == null
                ? reminderRepository.findByOrganizationId(organizationId, pageable)
                : reminderRepository.findByOrganizationIdAndStatus(organizationId, status, pageable);
    }

    /**
     * Moves a reminder's time or details.
     *
     * <p>A reminder that has already fired cannot be edited. Changing the time on a row
     * that says SENT would either resurrect it or leave a row whose {@code remind_at} is
     * a time nothing happened at; neither is worth having.
     */
    @Transactional
    public Reminder update(UUID reminderId, UUID organizationId, UpdateReminderRequest request) {
        Reminder reminder = getScoped(reminderId, organizationId);
        OptimisticVersion.require(reminder, request.version());
        if (reminder.getStatus() != ReminderStatus.SCHEDULED) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A reminder that is already " + reminder.getStatus() + " cannot be rescheduled");
        }
        requireFutureInstant(request.remindAt());

        reminder.setRemindAt(request.remindAt());
        reminder.setChannel(request.channel());
        reminder.setNote(request.note());
        return reminder;
    }

    /**
     * Calls a reminder off.
     *
     * <p>Cancellation rather than deletion, so that "this fired" and "somebody stopped
     * it" stay distinguishable afterwards. Cancelling an already-cancelled reminder is a
     * no-op rather than an error — the caller wanted it off, and it is off.
     */
    @Transactional
    public Reminder cancel(UUID reminderId, UUID organizationId) {
        Reminder reminder = getScoped(reminderId, organizationId);
        if (reminder.getStatus() == ReminderStatus.CANCELLED) {
            return reminder;
        }
        reminder.transitionTo(ReminderStatus.CANCELLED, Instant.now());
        log.info("Reminder {} cancelled in organization {}", reminderId, organizationId);
        return reminder;
    }

    private Reminder persist(Reminder reminder, UUID organizationId) {
        Reminder saved = reminderRepository.save(reminder);
        log.info("Reminder {} scheduled for {} in organization {}", saved.getId(),
                saved.getRemindAt(), organizationId);
        eventPublisher.publish(new ReminderScheduledEvent(organizationId, saved.getId(),
                saved.getTaskId(), saved.getDeadlineId(), saved.getRemindAt(), saved.getChannel()));
        return saved;
    }

    private Reminder newReminder(UUID organizationId, CreateReminderRequest request) {
        Reminder reminder = new Reminder();
        reminder.setOrganizationId(organizationId);
        reminder.setRemindAt(request.remindAt());
        reminder.setStatus(ReminderStatus.SCHEDULED);
        reminder.setChannel(request.channel());
        reminder.setNote(request.note());
        return reminder;
    }

    /**
     * A reminder in the past would be picked up by the very next sweep and fire
     * immediately, which is never what the person setting it meant.
     */
    private static void requireFutureInstant(Instant remindAt) {
        if (remindAt == null || !remindAt.isAfter(Instant.now())) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT, "remindAt must be in the future");
        }
    }
}
