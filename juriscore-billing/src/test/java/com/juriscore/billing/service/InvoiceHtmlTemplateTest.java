package com.juriscore.billing.service;

import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceLineItem;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.casework.domain.Client;
import com.juriscore.casework.domain.ClientType;
import com.juriscore.organization.domain.Organization;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTML/CSS an invoice PDF is built from — the one piece of {@code InvoicePdfService}
 * that can be tested as a plain string builder, with no PDF library, no database and no
 * image I/O involved.
 */
class InvoiceHtmlTemplateTest {

    private Organization organization(String name) {
        Organization organization = new Organization();
        organization.setName(name);
        organization.setContactEmail("reception@example.test");
        organization.setContactPhone("+91 22 5550 1000");
        return organization;
    }

    private BillingProfile blankProfile() {
        BillingProfile profile = new BillingProfile();
        profile.setDefaultCurrency("INR");
        profile.setInvoicePrefix("INV");
        return profile;
    }

    private Client client(String displayName) {
        Client client = new Client();
        client.setDisplayName(displayName);
        client.setClientType(ClientType.INDIVIDUAL);
        return client;
    }

    private Invoice invoice(String invoiceNumber) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceNumber(invoiceNumber);
        invoice.setClientId(UUID.randomUUID());
        invoice.setStatus(InvoiceStatus.ISSUED);
        invoice.setIssueDate(LocalDate.of(2026, 4, 1));
        invoice.setDueDate(LocalDate.of(2026, 5, 1));
        invoice.setCurrency("INR");

        InvoiceLineItem line = new InvoiceLineItem();
        line.setDescription("Drafting written statement");
        line.setQuantity(new BigDecimal("2.500"));
        line.setUnitPrice(new BigDecimal("4000.00"));
        line.setAmount(new BigDecimal("10000.00"));
        line.setTaxRate(new BigDecimal("18.000"));
        line.setTaxAmount(new BigDecimal("1800.00"));
        line.setSortOrder(0);
        invoice.addLineItem(line);

        invoice.setSubtotal(new BigDecimal("10000.00"));
        invoice.setTaxAmount(new BigDecimal("1800.00"));
        invoice.setDiscountAmount(BigDecimal.ZERO.setScale(2));
        invoice.setTotalAmount(new BigDecimal("11800.00"));
        return invoice;
    }

    @Test
    void rendersTheFirmsOwnIdentityAndNeverJurisCores() {
        BillingProfile profile = blankProfile();
        profile.setLegalName("Sharma & Associates");
        profile.setTaxRegistration("29ABCDE1234F1Z5");

        String html = InvoiceHtmlTemplate.render(invoice("INV-2026-000001"), profile,
                organization("Sharma & Associates Legal Services"), client("Asha Menon"),
                null, BigDecimal.ZERO.setScale(2));

        assertThat(html).contains("Sharma &amp; Associates");
        assertThat(html).contains("29ABCDE1234F1Z5");
        assertThat(html).contains("INV-2026-000001");
        assertThat(html).contains("Asha Menon");
        assertThat(html).doesNotContainIgnoringCase("jurisCore");
    }

    @Test
    void fallsBackToTheOrganizationWhenTheBillingProfileIsBlank() {
        BillingProfile profile = blankProfile(); // no legal name, no tax registration, no address

        String html = InvoiceHtmlTemplate.render(invoice("INV-2026-000002"), profile,
                organization("Menon Legal LLP"), client("Ravi Iyer"), null,
                BigDecimal.ZERO.setScale(2));

        assertThat(html).contains("Menon Legal LLP");
        assertThat(html).contains("reception@example.test");
    }

    @Test
    void omitsMissingOptionalFieldsRatherThanPrintingNull() {
        BillingProfile profile = blankProfile(); // legalName, taxRegistration, address all null
        Organization organization = new Organization();
        organization.setName("Menon Legal LLP"); // no contact email/phone/address either

        Client client = client("Ravi Iyer"); // no address, no email, no phone

        String html = InvoiceHtmlTemplate.render(invoice("INV-2026-000003"), profile,
                organization, client, null, BigDecimal.ZERO.setScale(2));

        assertThat(html.toLowerCase()).doesNotContain(">null<");
        assertThat(html).doesNotContain("Tax reg.");
    }

    @Test
    void escapesUntrustedTextSoItCannotBreakTheMarkup() {
        BillingProfile profile = blankProfile();
        profile.setLegalName("Sharma & Associates");
        Client client = client("O'Brien <Legal> & Co");

        String html = InvoiceHtmlTemplate.render(invoice("INV-2026-000004"), profile,
                organization("Sharma & Associates"), client, null, BigDecimal.ZERO.setScale(2));

        assertThat(html).contains("O&#39;Brien &lt;Legal&gt; &amp; Co");
        assertThat(html).doesNotContain("<Legal>");
    }

    @Test
    void printsTheNotesThatAreOnTheInvoice() {
        BillingProfile profile = blankProfile();
        profile.setLegalName("Sharma & Associates");

        Invoice invoice = invoice("INV-2026-000005");
        // What InvoiceService.create leaves behind: either the caller's own note, or the
        // firm's standing invoiceNotes copied onto the invoice at creation. By the time a
        // PDF is rendered the distinction is already settled, and this field is the answer.
        invoice.setNotes("Payment due within 30 days by bank transfer.");

        String html = InvoiceHtmlTemplate.render(invoice, profile,
                organization("Sharma & Associates"), client("Asha Menon"), null,
                BigDecimal.ZERO.setScale(2));

        assertThat(html).contains("Payment due within 30 days by bank transfer.");
    }

    @Test
    void doesNotResurrectTheFirmsStandingNotesOntoAnInvoiceThatHasNone() {
        // The firm has boilerplate, this invoice deliberately does not: either it was raised
        // before the firm adopted those terms, or somebody cleared it (UpdateInvoiceRequest
        // treats a null note as a clear). Re-reading the profile at render time would undo
        // that, and would make an already-issued invoice print terms adopted after it was
        // sent. The copy happens once, in InvoiceService.create, and not again here.
        BillingProfile profile = blankProfile();
        profile.setLegalName("Sharma & Associates");
        profile.setInvoiceNotes("Payment due within 30 days by bank transfer.");

        Invoice invoice = invoice("INV-2026-000005");
        invoice.setNotes(null);

        String html = InvoiceHtmlTemplate.render(invoice, profile,
                organization("Sharma & Associates"), client("Asha Menon"), null,
                BigDecimal.ZERO.setScale(2));

        assertThat(html).doesNotContain("Payment due within 30 days by bank transfer.");
        assertThat(html).doesNotContain("Notes &amp; payment instructions");
    }

    @Test
    void showsWhatWasAlreadyPaidWhenAPartPaymentHasBeenRecorded() {
        BillingProfile profile = blankProfile();
        profile.setLegalName("Sharma & Associates");

        String html = InvoiceHtmlTemplate.render(invoice("INV-2026-000006"), profile,
                organization("Sharma & Associates"), client("Asha Menon"), null,
                new BigDecimal("5000.00"));

        assertThat(html).contains("Balance due");
        assertThat(html).contains("6,800.00 INR");
    }
}
