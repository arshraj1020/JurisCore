package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An invoice is settled in full.
 *
 * <p>Published alongside {@code payment.recorded} rather than instead of it: one says
 * money arrived, the other says the invoice is finished, and a consumer usually cares
 * about exactly one of those.
 */
@Getter
public class InvoicePaidEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final BigDecimal totalAmount;
    private final String currency;

    public InvoicePaidEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                            UUID clientId, BigDecimal totalAmount, String currency) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.totalAmount = totalAmount;
        this.currency = currency;
    }

    @Override
    public String eventType() {
        return "invoice.paid";
    }
}
