package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.ClientType;
import com.juriscore.common.email.EmailAttachment;
import com.juriscore.common.email.EmailMessage;
import com.juriscore.organization.domain.Organization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The covering note a client receives with their invoice — the one part of invoice email
 * that can be read as plain text, and therefore the part worth pinning word by word.
 */
class InvoiceEmailContentTest {

    private static final EmailAttachment PDF =
            new EmailAttachment("invoice-INV-2026-000001.pdf", "application/pdf", new byte[] {1, 2});

    private Organization organization() {
        Organization organization = new Organization();
        organization.setName("Sharma & Associates Legal Services");
        organization.setContactEmail("reception@sharma-legal.test");
        organization.setContactPhone("+91 22 5550 1000");
        return organization;
    }

    private BillingProfile profile() {
        BillingProfile profile = new BillingProfile();
        profile.setDefaultCurrency("INR");
        profile.setInvoicePrefix("INV");
        profile.setLegalName("Sharma & Associates LLP");
        profile.setBillingEmail("accounts@sharma-legal.test");
        profile.setBillingPhone("+91 22 5555 0000");
        profile.setAddressLine1("4th Floor, Fort Chambers");
        profile.setCity("Mumbai");
        return profile;
    }

    private BillingProfile blankProfile() {
        BillingProfile profile = new BillingProfile();
        profile.setDefaultCurrency("INR");
        profile.setInvoicePrefix("INV");
        return profile;
    }

    private Client client() {
        Client client = new Client();
        client.setDisplayName("Asha Menon");
        client.setClientType(ClientType.INDIVIDUAL);
        client.setEmail("asha@menon.test");
        return client;
    }

    private Invoice invoice(InvoiceStatus status) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber("INV-2026-000001");
        invoice.setClientId(UUID.randomUUID());
        invoice.setStatus(status);
        invoice.setIssueDate(LocalDate.of(2026, 4, 1));
        invoice.setDueDate(LocalDate.of(2026, 5, 1));
        invoice.setCurrency("INR");
        invoice.setSubtotal(new BigDecimal("10000.00"));
        invoice.setTaxAmount(new BigDecimal("1800.00"));
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(2));
        invoice.setTotalAmount(new BigDecimal("11800.00"));
        invoice.setNotes("Payment due within 30 days by NEFT.");
        return invoice;
    }

    private EmailMessage build(Invoice invoice, BillingProfile profile, BigDecimal amountPaid) {
        return InvoiceEmailContent.build(invoice, profile, organization(), client(),
                amountPaid, PDF);
    }

    @Test
    @DisplayName("the message comes from the firm, replies go to the firm, and JurisCore is nowhere in it")
    void addressesTheMessageInTheFirmsName() {
        EmailMessage message = build(invoice(InvoiceStatus.ISSUED), profile(),
                BigDecimal.ZERO.setScale(2));

        assertThat(message.fromDisplayName()).isEqualTo("Sharma & Associates LLP");
        assertThat(message.replyTo()).isEqualTo("accounts@sharma-legal.test");
        assertThat(message.toAddress()).isEqualTo("asha@menon.test");
        assertThat(message.toName()).isEqualTo("Asha Menon");
        assertThat(message.subject()).isEqualTo("Invoice INV-2026-000001 from Sharma & Associates LLP");
        assertThat(message.textBody()).doesNotContainIgnoringCase("juriscore");
        assertThat(message.hasAttachment()).isTrue();
        assertThat(message.attachment().fileName()).isEqualTo("invoice-INV-2026-000001.pdf");
    }

    @Test
    void statesTheInvoiceNumberDatesTotalAndTheFirmsOwnTerms() {
        String body = build(invoice(InvoiceStatus.ISSUED), profile(),
                BigDecimal.ZERO.setScale(2)).textBody();

        assertThat(body).contains("Dear Asha Menon,");
        assertThat(body).contains("INV-2026-000001");
        assertThat(body).contains("1 Apr 2026");
        assertThat(body).contains("1 May 2026");
        assertThat(body).contains("11,800.00 INR");
        // Copied onto the invoice when it was raised, so the note and the PDF agree.
        assertThat(body).contains("Payment due within 30 days by NEFT.");
        assertThat(body).contains("Sharma & Associates LLP");
    }

    @Test
    @DisplayName("what is owed is spelt out, not left to be inferred from the total")
    void describesThePaymentPosition() {
        assertThat(build(invoice(InvoiceStatus.ISSUED), profile(), BigDecimal.ZERO.setScale(2))
                .textBody()).contains("11,800.00 INR outstanding");

        assertThat(build(invoice(InvoiceStatus.PARTIALLY_PAID), profile(), new BigDecimal("5000.00"))
                .textBody()).contains("5,000.00 INR received, 6,800.00 INR outstanding");

        assertThat(build(invoice(InvoiceStatus.OVERDUE), profile(), BigDecimal.ZERO.setScale(2))
                .textBody()).contains("past its due date");

        assertThat(build(invoice(InvoiceStatus.PAID), profile(), new BigDecimal("11800.00"))
                .textBody()).contains("Paid in full");
    }

    @Test
    void fallsBackToTheOrganizationWhenTheBillingProfileIsBlank() {
        EmailMessage message = build(invoice(InvoiceStatus.ISSUED), blankProfile(),
                BigDecimal.ZERO.setScale(2));

        assertThat(message.fromDisplayName()).isEqualTo("Sharma & Associates Legal Services");
        assertThat(message.replyTo()).isEqualTo("reception@sharma-legal.test");
        assertThat(message.textBody()).contains("reception@sharma-legal.test");
    }

    @Test
    void omitsMissingOptionalDetailRatherThanPrintingNull() {
        Organization bare = new Organization();
        bare.setName("Menon Legal LLP");
        Invoice invoice = invoice(InvoiceStatus.ISSUED);
        invoice.setNotes(null);
        invoice.setDueDate(null);

        EmailMessage message = InvoiceEmailContent.build(invoice, blankProfile(), bare, client(),
                BigDecimal.ZERO.setScale(2), PDF);

        assertThat(message.textBody()).doesNotContain("null");
        assertThat(message.textBody()).doesNotContain("Due date:");
        assertThat(message.replyTo()).isNull();
    }
}
