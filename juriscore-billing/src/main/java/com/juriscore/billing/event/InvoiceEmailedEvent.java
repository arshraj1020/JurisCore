package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * An invoice PDF was accepted by the email provider for delivery to the client.
 *
 * <p>"Accepted", precisely — not read, not even necessarily delivered. That is the
 * strongest claim any sender can make, and the audit summary is worded to match.
 *
 * <p>The recipient address travels on the event because who a bill was sent to is the
 * substance of the record; an audit row saying only "an invoice was emailed" would answer
 * none of the questions this trail exists to answer. No provider message id, no
 * credentials — those stay in the application log.
 */
@Getter
public class InvoiceEmailedEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final String recipient;

    public InvoiceEmailedEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                               UUID clientId, String recipient) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.recipient = recipient;
    }

    @Override
    public String eventType() {
        return "invoice.emailed";
    }
}
