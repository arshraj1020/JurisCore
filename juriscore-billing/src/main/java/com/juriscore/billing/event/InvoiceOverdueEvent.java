package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An issued invoice has passed its due date.
 *
 * <p>Published by the sweep, after commit, once per transition — the sweep only claims
 * invoices that are still ISSUED or PARTIALLY_PAID, so a rerun over the same rows finds
 * nothing and publishes nothing. An invoice that goes overdue, receives a part payment and
 * goes overdue again does produce a second event, because that is a second thing that
 * happened; the notification dedupe key is what stops that reaching somebody's inbox
 * twice.
 */
@Getter
public class InvoiceOverdueEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final BigDecimal amountDue;
    private final String currency;
    private final LocalDate dueDate;

    public InvoiceOverdueEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                               UUID clientId, BigDecimal amountDue, String currency,
                               LocalDate dueDate) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.amountDue = amountDue;
        this.currency = currency;
        this.dueDate = dueDate;
    }

    @Override
    public String eventType() {
        return "invoice.overdue";
    }
}
