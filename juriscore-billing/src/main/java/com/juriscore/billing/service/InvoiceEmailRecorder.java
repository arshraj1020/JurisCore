package com.juriscore.billing.service;

import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceEmailStatus;
import com.juriscore.billing.event.InvoiceEmailFailedEvent;
import com.juriscore.billing.event.InvoiceEmailedEvent;
import com.juriscore.billing.repository.InvoiceRepository;
import com.juriscore.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the outcome of an email attempt, in a transaction of its own.
 *
 * <p>Sibling of {@code DocumentFailureRecorder}, and it exists for the same reason. A
 * refused send has to end in an error response; {@code ApiException} is a
 * {@code RuntimeException}, so throwing it would mark the caller's transaction
 * rollback-only and discard the FAILED record that is the entire point of catching the
 * refusal. {@code REQUIRES_NEW} commits the record before the caller throws — and, just as
 * importantly, commits it in time for the {@code AFTER_COMMIT} listener that writes the
 * audit row, which would otherwise never fire on the failure path.
 *
 * <p>A separate bean because Spring's proxying ignores self-invocation: these methods on
 * {@code InvoiceEmailService} would silently join whatever transaction the caller had —
 * which, since that caller deliberately has none, would mean no transaction at all.
 *
 * <h2>Why it re-reads the invoice</h2>
 *
 * <p>This transaction has its own persistence context, and the caller's copy was loaded
 * before a network round-trip to a mail provider — long enough, in principle, for the row
 * to have moved. Loading it again keeps the write and the tenant check together, which is
 * the rule every other write in this platform follows.
 */
@Service
@RequiredArgsConstructor
public class InvoiceEmailRecorder {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEmailRecorder.class);

    private final InvoiceRepository invoiceRepository;
    private final EventPublisher eventPublisher;

    /**
     * Records that the provider accepted the message, and commits.
     *
     * @return when it was accepted, or null if the invoice is no longer there
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Instant recordSent(UUID invoiceId, UUID organizationId, String recipient) {
        Invoice invoice = find(invoiceId, organizationId);
        if (invoice == null) {
            return null;
        }
        Instant when = Instant.now();
        invoice.recordEmailAttempt(InvoiceEmailStatus.SENT, recipient, when);

        log.info("Invoice {} emailed to the client in organization {}",
                invoice.getInvoiceNumber(), organizationId);
        eventPublisher.publish(new InvoiceEmailedEvent(organizationId, invoice.getId(),
                invoice.getInvoiceNumber(), invoice.getClientId(), recipient));
        return when;
    }

    /**
     * Records that an attempt was made and did not go, and commits.
     *
     * @param reasonCode the short token from {@code EmailDeliveryException} — never the
     *                   provider's full response, which stays in the log
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID invoiceId, UUID organizationId, String recipient,
                              String reasonCode) {
        Invoice invoice = find(invoiceId, organizationId);
        if (invoice == null) {
            return;
        }
        invoice.recordEmailAttempt(InvoiceEmailStatus.FAILED, recipient, Instant.now());

        log.warn("Invoice {} could not be emailed in organization {}: {}",
                invoice.getInvoiceNumber(), organizationId, reasonCode);
        eventPublisher.publish(new InvoiceEmailFailedEvent(organizationId, invoice.getId(),
                invoice.getInvoiceNumber(), invoice.getClientId(), recipient, reasonCode));
    }

    /** Tenant-scoped like every other lookup: another firm's invoice is simply not there. */
    private Invoice find(UUID invoiceId, UUID organizationId) {
        Invoice invoice = invoiceRepository.findByIdAndOrganizationId(invoiceId, organizationId)
                .orElse(null);
        if (invoice == null) {
            log.warn("Invoice {} vanished before its email outcome could be recorded", invoiceId);
        }
        return invoice;
    }
}
