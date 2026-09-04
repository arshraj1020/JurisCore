package com.juriscore.billing.service;

import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.billing.domain.Money;
import com.juriscore.billing.event.InvoiceOverdueEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.billing.repository.PaymentRepository;
import com.juriscore.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Moves invoices past their due date into OVERDUE.
 *
 * <p>Separate from {@code OverdueInvoiceScheduler} and directly callable, exactly as
 * {@code ReminderDispatchService} is: "does the sweep work" and "does the timer fire" stay
 * two questions, and the integration tests drive this method rather than waiting for a
 * clock.
 *
 * <h2>Why a sweep at all</h2>
 *
 * <p>OVERDUE is the one invoice state nobody causes. Every other transition has a request
 * behind it, so it can be decided when that request arrives; this one is caused by time
 * passing, and something has to notice. The alternative — deriving it on read, so an
 * invoice "is" overdue whenever its due date has passed — was rejected for two reasons: a
 * derived status cannot be the subject of an event, so nobody could be told; and it would
 * leave the stored status disagreeing with the API's, which is exactly the kind of split
 * a reader eventually gets wrong.
 *
 * <p>The sweep reuses Phase 3's scheduling architecture rather than adding one. Nothing
 * here is new infrastructure.
 */
@Service
@RequiredArgsConstructor
public class OverdueInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(OverdueInvoiceService.class);

    private final OverdueInvoiceClaimer claimer;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final EventPublisher eventPublisher;

    @Value("${juriscore.billing.overdue-batch-size:100}")
    private int batchSize = 100;

    /** The scheduled entry point: everything due before today, in UTC. */
    @Transactional
    public int markOverdue() {
        return markOverdue(InvoiceService.today());
    }

    /**
     * Everything with a due date strictly before {@code asOf}.
     *
     * <p>Strictly before, so an invoice due today is not late today. A firm that gives a
     * client until the 30th means the whole of the 30th.
     *
     * @return how many invoices moved
     */
    @Transactional
    public int markOverdue(LocalDate asOf) {
        List<UUID> claimed = claimer.claimOverdue(asOf, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Invoice> invoices = invoiceRepository.findByIdInOrderByDueDateAsc(claimed);
        int moved = 0;
        for (Invoice invoice : invoices) {
            // Re-checked rather than assumed: the claim is authoritative, but a status
            // that changed between the claim and here would make transitionTo throw, and
            // one late payment must not abort the whole batch.
            if (invoice.getStatus() != InvoiceStatus.ISSUED
                    && invoice.getStatus() != InvoiceStatus.PARTIALLY_PAID) {
                continue;
            }
            invoice.transitionTo(InvoiceStatus.OVERDUE, Instant.now());
            moved++;

            BigDecimal paid = Money.amount(paymentRepository.totalPaid(
                    invoice.getOrganizationId(), invoice.getId()));
            eventPublisher.publish(new InvoiceOverdueEvent(
                    invoice.getOrganizationId(), invoice.getId(), invoice.getInvoiceNumber(),
                    invoice.getClientId(), invoice.getTotalAmount().subtract(paid),
                    invoice.getCurrency(), invoice.getDueDate()));
        }

        if (moved > 0) {
            log.info("Overdue sweep moved {} invoice(s) past their due date as of {}", moved, asOf);
        }
        return moved;
    }
}
