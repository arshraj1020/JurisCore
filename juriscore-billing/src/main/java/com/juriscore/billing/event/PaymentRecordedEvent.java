package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Money has been recorded against an invoice.
 *
 * <p>Amount, currency and the resulting balance, and nothing else. Deliberately no
 * {@code reference}: a cheque number or bank narration is the firm's and its client's
 * business, and there is no consumer in this system that needs it.
 */
@Getter
public class PaymentRecordedEvent extends AbstractDomainEvent {

    private final UUID paymentId;
    private final UUID invoiceId;
    private final String invoiceNumber;
    private final BigDecimal amount;
    private final String currency;
    private final BigDecimal amountDue;

    public PaymentRecordedEvent(UUID organizationId, UUID paymentId, UUID invoiceId,
                                String invoiceNumber, BigDecimal amount, String currency,
                                BigDecimal amountDue) {
        super(organizationId);
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.amount = amount;
        this.currency = currency;
        this.amountDue = amountDue;
    }

    @Override
    public String eventType() {
        return "payment.recorded";
    }
}
