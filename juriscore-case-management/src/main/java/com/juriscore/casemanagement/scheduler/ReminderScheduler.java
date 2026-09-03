package com.juriscore.casemanagement.scheduler;

import com.juriscore.casemanagement.service.ReminderDispatchService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock behind the reminder sweep, and nothing else.
 *
 * <p>All the work — claiming, state, events — is in {@code ReminderDispatchService},
 * which is transactional and directly callable. This class exists only to say when, so
 * that "does the sweep work" and "does the timer fire" stay separate questions.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}: a sweep that runs long must not
 * have the next one queued behind it. Spring's default scheduler is single-threaded, so
 * within one instance sweeps never overlap; across instances they may, and the row lock
 * in {@code ReminderClaimer} is what makes that safe rather than this annotation.
 *
 * <p>An exception here would silently kill the schedule for the life of the process, so
 * the sweep is wrapped: one bad batch must not mean no reminders until the next deploy.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.reminders", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderDispatchService dispatchService;

    @Scheduled(fixedDelayString = "${juriscore.reminders.poll-interval:PT60S}")
    public void sweep() {
        try {
            dispatchService.dispatchDue();
        } catch (RuntimeException e) {
            log.error("Reminder sweep failed; the schedule continues", e);
        }
    }
}
