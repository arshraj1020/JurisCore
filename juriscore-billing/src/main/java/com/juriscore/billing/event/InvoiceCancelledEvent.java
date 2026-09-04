package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** An invoice has been withdrawn. Terminal: nothing follows this for that invoice. */
@Getter
public class InvoiceCancelledEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;

    public InvoiceCancelledEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                                 UUID clientId) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
    }

    @Override
    public String eventType() {
        return "invoice.cancelled";
    }
}
