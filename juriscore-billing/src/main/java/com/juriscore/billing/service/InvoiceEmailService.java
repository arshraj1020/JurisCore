package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.service.ClientService;
import com.juriscore.common.email.EmailAttachment;
import com.juriscore.common.email.EmailDeliveryException;
import com.juriscore.common.email.EmailMessage;
import com.juriscore.common.email.EmailSender;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Emails a client their invoice, as a PDF attached to a note from the firm.
 *
 * <h2>Nothing here is transactional, and that is the design</h2>
 *
 * <p>Sending is a network call to a mail provider that can take seconds and can hang. A
 * transaction spanning it would hold a pooled database connection open for the whole
 * round-trip, and a firm sending a batch of invoices would exhaust the pool on a feature
 * that writes three columns. So each read below runs in its own short transaction inside
 * the service that owns it, the provider is called with no transaction open at all, and
 * the outcome is written afterwards by {@link InvoiceEmailRecorder} in one of its own.
 *
 * <p>The consequence to know about: the entities read here are detached by the time this
 * method sees them, and {@code open-in-view} is off. Only scalar fields are touched.
 * {@code invoice.getLineItems()} is never read outside {@link InvoicePdfService}, which
 * does its work inside its own transaction.
 *
 * <h2>Success is the only thing that is reported as success</h2>
 *
 * <p>A refusal from the provider is recorded as FAILED, audited, and returned to the caller
 * as an error. There is no path on which an invoice is marked SENT without a provider
 * having accepted the message, and no path on which a refusal is swallowed — the two
 * failure modes that would matter most to a firm that thinks it has billed a client.
 *
 * <h2>No queue, deliberately</h2>
 *
 * <p>The platform has no job runner. Building one for a single button would be a larger,
 * riskier change than the feature it serves, and it would replace an error the user can
 * see with a silence they cannot. The send is synchronous; the person who pressed the
 * button learns what happened. If invoice email ever becomes a bulk operation, that is the
 * moment to put it on the queue this architecture already has settings for — not before.
 */
@Service
@RequiredArgsConstructor
public class InvoiceEmailService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceEmailService.class);

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;
    private final BillingProfileService billingProfileService;
    private final OrganizationService organizationService;
    private final ClientService clientService;
    private final EmailSender emailSender;
    private final InvoiceEmailRecorder recorder;

    /** What the caller is told about a message the provider accepted. */
    public record Receipt(String invoiceNumber, String recipient, Instant sentAt,
                          String fileName) {
    }

    /**
     * Sends the invoice to the address held for its client.
     *
     * @throws ApiException 404 for another firm's invoice, 409 for a draft or a cancelled
     *                      one, 400 when the client has no address on file, 502 when the
     *                      provider refused it
     */
    public Receipt email(UUID invoiceId, UUID organizationId) {
        Invoice invoice = invoiceService.require(invoiceId, organizationId);
        requireSendableState(invoice);

        Client client = clientService.getScoped(invoice.getClientId(), organizationId);
        String recipient = requireRecipient(client);

        BillingProfile profile = billingProfileService.forOrganization(organizationId);
        Organization organization = organizationService.getById(organizationId);
        BigDecimal amountPaid = invoiceService.amountPaid(invoiceId, organizationId);

        // The same renderer the download endpoint uses, producing the same bytes. Nothing
        // about the PDF is duplicated here, and a change to the invoice template reaches
        // the attachment for free.
        InvoicePdfService.RenderedInvoice pdf = invoicePdfService.render(invoiceId, organizationId);
        EmailMessage message = InvoiceEmailContent.build(invoice, profile, organization, client,
                amountPaid, new EmailAttachment(pdf.fileName(), PDF_CONTENT_TYPE, pdf.bytes()));

        try {
            emailSender.send(message);
        } catch (EmailDeliveryException e) {
            recorder.recordFailure(invoiceId, organizationId, recipient, e.reasonCode());
            // The provider's own words are in the log, not in this message. An
            // ApiException's message is returned to the client verbatim by
            // GlobalExceptionHandler, and a provider response is exactly the kind of text
            // that carries an account identifier or an endpoint nobody meant to publish.
            log.error("Invoice {} could not be emailed to its client", invoice.getInvoiceNumber(), e);
            throw new ApiException(ErrorCode.EMAIL_DELIVERY_FAILED,
                    "The invoice could not be emailed. It has not been sent, and the attempt "
                            + "has been recorded. Please try again shortly.");
        }

        Instant sentAt = recorder.recordSent(invoiceId, organizationId, recipient);
        return new Receipt(invoice.getInvoiceNumber(), recipient,
                sentAt == null ? Instant.now() : sentAt, pdf.fileName());
    }

    /**
     * A draft has not been issued to anybody and a cancelled invoice has been withdrawn;
     * emailing either is a 409 rather than a quiet no-op, for the reason
     * {@code InvoiceService.update} gives about frozen invoices — a person who believes
     * they sent a bill must not be left believing it.
     */
    private void requireSendableState(Invoice invoice) {
        if (!invoice.getStatus().canBeEmailed()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "An invoice in " + invoice.getStatus() + " cannot be emailed. Issue it "
                            + "first; a cancelled invoice cannot be sent at all.");
        }
    }

    /**
     * The recipient is the client's own address, held on the client record since Phase 2.
     * There is no per-invoice recipient field and this feature does not add one: a bill
     * goes to the client it bills, and a second copy of that address would be a second
     * thing to keep correct.
     */
    private String requireRecipient(Client client) {
        String email = client.getEmail();
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This client has no email address on file. Add one to the client record "
                            + "before emailing their invoice.");
        }
        return email;
    }
}
