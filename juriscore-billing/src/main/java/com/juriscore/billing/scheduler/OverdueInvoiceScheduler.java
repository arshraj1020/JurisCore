package com.juriscore.billing.scheduler;

import com.juriscore.billing.service.OverdueInvoiceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock behind the overdue sweep, and nothing else.
 *
 * <p>Deliberately the same shape as {@code ReminderScheduler}: all the work — claiming,
 * state, events — is in {@code OverdueInvoiceService}, which is transactional and directly
 * callable, so this class only says when.
 *
 * <p>{@code fixedDelay} rather than {@code fixedRate}, so a sweep that runs long does not
 * have the next one queued behind it. Within one instance Spring's default scheduler is
 * single-threaded and sweeps never overlap; across instances they may, and the row lock in
 * {@code OverdueInvoiceClaimer} is what makes that safe rather than this annotation.
 *
 * <p>Hourly, not by the minute. An invoice becomes late once, at midnight, and nothing is
 * improved by noticing within sixty seconds.
 *
 * <p>An exception here would silently kill the schedule for the life of the process, so the
 * sweep is wrapped: one bad batch must not mean no overdue invoices until the next deploy.
 */
@Component
@ConditionalOnProperty(prefix = "juriscore.billing", name = "overdue-sweep-enabled",
        havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OverdueInvoiceScheduler {

    private static final Logger log = LoggerFactory.getLogger(OverdueInvoiceScheduler.class);

    private final OverdueInvoiceService overdueInvoiceService;

    @Scheduled(fixedDelayString = "${juriscore.billing.overdue-poll-interval:PT1H}")
    public void sweep() {
        try {
            overdueInvoiceService.markOverdue();
        } catch (RuntimeException e) {
            log.error("Overdue invoice sweep failed; the schedule continues", e);
        }
    }
}
