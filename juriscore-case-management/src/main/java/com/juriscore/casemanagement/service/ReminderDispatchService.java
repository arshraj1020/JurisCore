package com.juriscore.casemanagement.service;

import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.event.ReminderTriggeredEvent;
import com.juriscore.casemanagement.repository.ReminderRepository;
import com.juriscore.casemanagement.scheduler.ReminderProperties;
import com.juriscore.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One sweep: claim the reminders that have come due, mark them, announce them.
 *
 * <p>Separate from the scheduler that calls it so the work can be driven directly — by a
 * test, or by an operator — without waiting for a timer. The scheduler contributes the
 * clock and nothing else.
 *
 * <h2>What "sent" means</h2>
 *
 * <p>Publishing a {@code reminder.triggered} event. Nothing in this platform sends email,
 * SMS or push, and this method does not pretend to: the state change and the event are
 * the whole of it. A Phase 5 consumer subscribing to that event is what will eventually
 * make a reminder reach a person.
 *
 * <h2>Exactly once, across instances</h2>
 *
 * <p>The claim, the status change and the publish are one transaction. The row lock taken
 * by {@code ReminderClaimer} is released at the same commit that writes SENT, so no other
 * instance can see the row as due at any point. Events are delivered after that commit,
 * so a sweep that rolls back announces nothing.
 */
@Service
@RequiredArgsConstructor
public class ReminderDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ReminderDispatchService.class);

    private final ReminderClaimer claimer;
    private final ReminderRepository reminderRepository;
    private final EventPublisher eventPublisher;
    private final ReminderProperties properties;

    /**
     * @return how many reminders this sweep took ownership of and published.
     */
    @Transactional
    public int dispatchDue() {
        return dispatchDue(Instant.now(), properties.getBatchSize());
    }

    @Transactional
    public int dispatchDue(Instant now, int batchSize) {
        List<UUID> claimed = claimer.claimDue(now, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Reminder> reminders = reminderRepository.findByIdIn(claimed);
        int published = 0;
        for (Reminder reminder : reminders) {
            // Belt and braces. The lock makes this impossible, and the policy would throw
            // rather than double-fire if it ever became possible, so skipping quietly here
            // is the safe reading of a state that should not exist.
            if (reminder.getStatus() != ReminderStatus.SCHEDULED) {
                continue;
            }
            reminder.transitionTo(ReminderStatus.SENT, now);
            eventPublisher.publish(new ReminderTriggeredEvent(
                    reminder.getOrganizationId(), reminder.getId(), reminder.getTaskId(),
                    reminder.getDeadlineId(), reminder.getChannel(), reminder.getRemindAt()));
            published++;
        }

        if (published > 0) {
            log.info("Reminder sweep published {} reminder(s)", published);
        }
        return published;
    }
}
