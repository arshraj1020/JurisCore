package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** An invoice has been sent to a client and is now financially frozen. */
@Getter
public class InvoiceIssuedEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final BigDecimal totalAmount;
    private final String currency;
    private final LocalDate dueDate;

    public InvoiceIssuedEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                              UUID clientId, BigDecimal totalAmount, String currency,
                              LocalDate dueDate) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.dueDate = dueDate;
    }

    @Override
    public String eventType() {
        return "invoice.issued";
    }
}
