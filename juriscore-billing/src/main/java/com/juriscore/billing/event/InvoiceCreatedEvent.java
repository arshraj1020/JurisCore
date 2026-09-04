package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A draft invoice exists.
 *
 * <p>Carries the total and the currency because a consumer that has to fetch the invoice
 * to know what it is worth defeats the point of the event, and both are figures the firm's
 * own staff already see. It carries no line items: what a firm charged for is more
 * detailed than any consumer of this event needs, and an event bus is a poor place to
 * widen the blast radius of a leak.
 */
@Getter
public class InvoiceCreatedEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final UUID caseId;
    private final BigDecimal totalAmount;
    private final String currency;

    public InvoiceCreatedEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                               UUID clientId, UUID caseId, BigDecimal totalAmount, String currency) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.caseId = caseId;
        this.totalAmount = totalAmount;
        this.currency = currency;
    }

    @Override
    public String eventType() {
        return "invoice.created";
    }
}
