package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.casework.domain.Client;
import com.juriscore.common.email.EmailAttachment;
import com.juriscore.common.email.EmailMessage;
import com.juriscore.organization.domain.Organization;

import java.math.BigDecimal;

/**
 * Writes the covering note an invoice PDF travels with.
 *
 * <p>A pure function of its arguments, in its own class for the same reason
 * {@code InvoiceHtmlTemplate} is: the wording of a letter to a client is worth reading in
 * one place and worth testing without a provider, a database or a byte of PDF.
 *
 * <h2>The firm is the sender</h2>
 *
 * <p>Every line of this message is written in the firm's voice. The display name is the
 * firm's, the reply-to is the firm's billing mailbox, the sign-off is the firm's, and this
 * product's name appears nowhere — the same rule the PDF itself follows. A client
 * receiving a bill should see their solicitors, not their solicitors' software.
 *
 * <h2>Scalar fields only</h2>
 *
 * <p>Nothing here touches {@code invoice.getLineItems()}. {@code open-in-view} is off and
 * {@code InvoiceEmailService} assembles this outside any transaction, so a lazy
 * association read here would be a {@code LazyInitializationException} in production and
 * — because a unit test builds its fixture eagerly — a green test. The line-by-line detail
 * is in the attachment, where a reader wants it anyway.
 */
final class InvoiceEmailContent {

    private InvoiceEmailContent() {
    }

    static EmailMessage build(Invoice invoice, BillingProfile profile, Organization organization,
                              Client client, BigDecimal amountPaid, EmailAttachment attachment) {
        String firmName = firstNonBlank(profile.getLegalName(), organization.getName());
        return new EmailMessage(
                firmName,
                firstNonBlank(profile.getBillingEmail(), organization.getContactEmail()),
                client.getEmail(),
                client.getDisplayName(),
                subject(invoice, firmName),
                body(invoice, profile, organization, client, amountPaid, firmName),
                attachment);
    }

    /** {@code Invoice INV-2026-000001 from Sharma & Associates LLP} — searchable, and it
     * says who it is from before the client has opened anything. */
    static String subject(Invoice invoice, String firmName) {
        return "Invoice " + invoice.getInvoiceNumber() + " from " + firmName;
    }

    static String body(Invoice invoice, BillingProfile profile, Organization organization,
                       Client client, BigDecimal amountPaid, String firmName) {
        String currency = invoice.getCurrency();
        BigDecimal amountDue = invoice.getTotalAmount().subtract(amountPaid);

        StringBuilder text = new StringBuilder(800);
        text.append("Dear ").append(client.getDisplayName()).append(",\n\n");
        text.append("Please find attached invoice ").append(invoice.getInvoiceNumber())
                .append(" from ").append(firmName).append(".\n\n");

        append(text, "Invoice number", invoice.getInvoiceNumber());
        append(text, "Issue date", InvoicePdfService.date(invoice.getIssueDate()));
        append(text, "Due date", InvoicePdfService.date(invoice.getDueDate()));
        append(text, "Total", InvoicePdfService.money(invoice.getTotalAmount(), currency));
        append(text, "Status", paymentLine(invoice, amountPaid, amountDue, currency));

        // The firm's standing terms, already copied onto the invoice when it was raised —
        // see InvoiceService.create. Read from the invoice, never from the profile, so the
        // covering note says exactly what the attached PDF says.
        if (isSet(invoice.getNotes())) {
            text.append('\n').append(invoice.getNotes().trim()).append('\n');
        }

        text.append("\nIf you have any questions about this invoice, please reply to this "
                + "email");
        String contact = contactLine(profile, organization);
        text.append(isSet(contact) ? " or contact us on " + contact + ".\n" : ".\n");

        text.append("\nKind regards,\n").append(firmName).append('\n');
        String address = postalAddress(profile, organization);
        if (isSet(address)) {
            text.append(address).append('\n');
        }
        return text.toString();
    }

    /**
     * What the client actually owes, in one line.
     *
     * <p>Spelt out rather than left to be inferred from the total: an invoice that has been
     * part-paid, settled, or is already late are three different letters to receive, and a
     * client reading "Total: 11,800.00 INR" on a bill they paid last week will write back.
     */
    private static String paymentLine(Invoice invoice, BigDecimal amountPaid,
                                      BigDecimal amountDue, String currency) {
        if (invoice.getStatus() == InvoiceStatus.PAID || amountDue.signum() <= 0) {
            return "Paid in full — no payment is due";
        }
        String due = InvoicePdfService.money(amountDue, currency) + " outstanding";
        if (amountPaid.signum() > 0) {
            due = InvoicePdfService.money(amountPaid, currency) + " received, " + due;
        }
        if (invoice.getStatus() == InvoiceStatus.OVERDUE) {
            due = due + " (past its due date)";
        }
        return due;
    }

    private static String contactLine(BillingProfile profile, Organization organization) {
        String phone = firstNonBlank(profile.getBillingPhone(), organization.getContactPhone());
        String email = firstNonBlank(profile.getBillingEmail(), organization.getContactEmail());
        if (isSet(phone) && isSet(email)) {
            return phone + " or " + email;
        }
        return isSet(phone) ? phone : email;
    }

    private static String postalAddress(BillingProfile profile, Organization organization) {
        String fromProfile = join(profile.getAddressLine1(), profile.getAddressLine2(),
                profile.getCity(), profile.getState(), profile.getCountry(),
                profile.getPostalCode());
        return isSet(fromProfile) ? fromProfile
                : join(organization.getAddressLine1(), organization.getAddressLine2(),
                        organization.getCity(), organization.getState(),
                        organization.getCountry(), organization.getPostalCode());
    }

    /** Omits the line entirely when there is nothing to put on it — an invoice with no due
     * date prints no due date, rather than "Due date: not set". */
    private static void append(StringBuilder text, String label, String value) {
        if (isSet(value)) {
            text.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String join(String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (isSet(part)) {
                joined.append(joined.isEmpty() ? "" : ", ").append(part.trim());
            }
        }
        return joined.toString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (isSet(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
