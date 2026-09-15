package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * An attempt to email an invoice was refused by the provider, or never reached it.
 *
 * <p>A failed attempt is audited as deliberately as a successful one. A firm asking "was
 * this ever sent?" needs to see that somebody tried at 14:05 and it did not go, and the
 * absence of a row cannot say that.
 *
 * <p>{@link #reasonCode} is the short token {@code EmailDeliveryException} carries —
 * {@code MessageRejected}, {@code NOT_CONFIGURED} — never the provider's full response.
 * Audit summaries are read by firm administrators and copied into every backup, and
 * {@code AuditRedaction} exists precisely because a provider payload is the kind of thing
 * that quietly carries an identifier nobody meant to publish.
 */
@Getter
public class InvoiceEmailFailedEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;
    private final String recipient;
    private final String reasonCode;

    public InvoiceEmailFailedEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                                   UUID clientId, String recipient, String reasonCode) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
        this.recipient = recipient;
        this.reasonCode = reasonCode;
    }

    @Override
    public String eventType() {
        return "invoice.email_failed";
    }
}
