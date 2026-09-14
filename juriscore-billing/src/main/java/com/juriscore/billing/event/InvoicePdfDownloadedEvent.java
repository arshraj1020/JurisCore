package com.juriscore.billing.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * A firm-branded PDF of an invoice was rendered and handed to the caller.
 *
 * <p>There is deliberately no separate "generated" event alongside this one. In this
 * milestone a PDF exists only for the moment it is streamed back to a download request —
 * nothing stores one — so "generated" and "downloaded" are the same occurrence, and a
 * second event describing it would be exactly the kind of audit row that records its own
 * downstream effect rather than saying anything new (see
 * {@code DomainEventAuditListener}'s note on {@code notification.created}). If a later
 * phase adds emailing an invoice, that is a genuinely distinct action and gets its own
 * event then.
 */
@Getter
public class InvoicePdfDownloadedEvent extends AbstractDomainEvent {

    private final UUID invoiceId;
    private final String invoiceNumber;
    private final UUID clientId;

    public InvoicePdfDownloadedEvent(UUID organizationId, UUID invoiceId, String invoiceNumber,
                                     UUID clientId) {
        super(organizationId);
        this.invoiceId = invoiceId;
        this.invoiceNumber = invoiceNumber;
        this.clientId = clientId;
    }

    @Override
    public String eventType() {
        return "invoice.pdf_downloaded";
    }
}
