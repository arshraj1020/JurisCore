package com.juriscore.billing;

import com.juriscore.app.email.RecordingEmailSender;
import com.juriscore.common.email.EmailMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/v1/invoices/{id}/email} — Phase 7's second milestone.
 *
 * <p>Delivery runs through {@link RecordingEmailSender}, selected by the test profile: the
 * real service, the real state rules, the real PDF, against a double that captures the
 * message instead of sending it. That is what makes the assertions below worth making —
 * the recipient, the subject and the attached bytes asserted here are the ones SES would
 * have been handed.
 *
 * <p>The property this file exists to defend, above all the others: an invoice is never
 * recorded as emailed unless a provider accepted it, and an attempt that failed is never
 * silent.
 */
class InvoiceEmailIT extends AbstractBillingIT {

    @Autowired
    private RecordingEmailSender emails;

    @BeforeEach
    void clearMailbox() {
        emails.clear();
    }

    private void email(String token, String invoiceId, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/email")
                        .header("Authorization", bearer(token)))
                .andExpect(status().is(expectedStatus));
    }

    private String emailStatusOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT email_status FROM billing.invoices WHERE id = ?::uuid",
                String.class, invoiceId);
    }

    private String emailRecipientOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT email_recipient FROM billing.invoices WHERE id = ?::uuid",
                String.class, invoiceId);
    }

    @Test
    @DisplayName("an issued invoice reaches the client as a PDF from the firm, and is audited")
    void emailsAnIssuedInvoiceToItsClient() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String admin = ledger.firm().adminToken();

        mockMvc.perform(patch("/api/v1/billing/profile")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Sharma & Associates LLP",
                                 "billingEmail":"accounts@sharma-legal.test"}
                                """))
                .andExpect(status().isOk());

        String invoiceId = issued(admin, ledger.clientId(), null);
        String invoiceNumber = numberOf(invoiceId);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/email")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invoiceNumber").value(invoiceNumber))
                .andExpect(jsonPath("$.data.recipient").value("asha@menon.test"))
                .andExpect(jsonPath("$.data.fileName").value("invoice-" + invoiceNumber + ".pdf"))
                .andExpect(jsonPath("$.data.sentAt").isNotEmpty());

        // What the provider was actually handed.
        assertThat(emails.sentMessages()).hasSize(1);
        EmailMessage message = emails.lastMessage().orElseThrow();
        assertThat(message.toAddress()).isEqualTo("asha@menon.test");
        assertThat(message.toName()).isEqualTo("Asha Menon");
        assertThat(message.fromDisplayName()).isEqualTo("Sharma & Associates LLP");
        assertThat(message.replyTo()).isEqualTo("accounts@sharma-legal.test");
        assertThat(message.subject())
                .isEqualTo("Invoice " + invoiceNumber + " from Sharma & Associates LLP");
        assertThat(message.textBody()).doesNotContainIgnoringCase("juriscore");

        // The attachment is the same document the download endpoint serves.
        assertThat(message.hasAttachment()).isTrue();
        assertThat(message.attachment().fileName()).isEqualTo("invoice-" + invoiceNumber + ".pdf");
        assertThat(message.attachment().contentType()).isEqualTo("application/pdf");
        String attachedText = extractText(message.attachment().content());
        assertThat(attachedText).contains(invoiceNumber);
        assertThat(attachedText).contains("Sharma & Associates LLP");
        assertThat(attachedText).contains("Asha Menon");
        assertThat(attachedText).doesNotContainIgnoringCase("juriscore");
        // The attachment IS the download endpoint's document — InvoiceEmailService calls the
        // same renderer rather than building a second one. Compared as extracted text, not
        // as bytes: two renders of the same invoice differ only in the creation timestamp
        // PDF writers embed, and asserting on bytes would fail for a reason that says
        // nothing about the invoice.
        assertThat(attachedText).isEqualTo(extractText(downloadedPdf(admin, invoiceId)));

        // Delivery state, persisted and visible on the invoice.
        assertThat(emailStatusOf(invoiceId)).isEqualTo("SENT");
        assertThat(emailRecipientOf(invoiceId)).isEqualTo("asha@menon.test");
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.emailStatus").value("SENT"))
                .andExpect(jsonPath("$.data.emailRecipient").value("asha@menon.test"))
                .andExpect(jsonPath("$.data.emailLastAttemptAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.emailed")
                        .param("entityId", invoiceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].entityType").value("INVOICE"))
                .andExpect(jsonPath("$.data.items[0].summary")
                        .value(org.hamcrest.Matchers.containsString("asha@menon.test")));
    }

    @Test
    @DisplayName("a provider refusal is recorded as a failure and never as a send")
    void doesNotCallItSentWhenTheProviderRefuses() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha2@sharma-legal.test");
        String admin = ledger.firm().adminToken();
        String invoiceId = issued(admin, ledger.clientId(), null);

        emails.failNextSend("MessageRejected");

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/email")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("EMAIL_DELIVERY_FAILED"))
                // The provider's own words stay in the log: nothing about SES, the sending
                // identity or the account reaches the client.
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("MessageRejected"))));

        assertThat(emails.sentMessages()).isEmpty();
        assertThat(emailStatusOf(invoiceId)).isEqualTo("FAILED");
        assertThat(emailRecipientOf(invoiceId)).isEqualTo("asha@menon.test");

        // The attempt is audited, reason code and all — silence would be indistinguishable
        // from nobody ever having tried.
        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.email_failed")
                        .param("entityId", invoiceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].summary")
                        .value(org.hamcrest.Matchers.containsString("MessageRejected")));
        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.emailed")
                        .param("entityId", invoiceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    @DisplayName("a draft has not been issued to anybody, so it cannot be emailed to anybody")
    void refusesToEmailADraft() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha3@sharma-legal.test");
        String admin = ledger.firm().adminToken();
        String draftId = draft(admin, ledger.clientId(), null);

        mockMvc.perform(post("/api/v1/invoices/" + draftId + "/email")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        assertThat(emails.sentMessages()).isEmpty();
        assertThat(emailStatusOf(draftId)).isEqualTo("NOT_SENT");
    }

    @Test
    void refusesToEmailAWithdrawnInvoice() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha4@sharma-legal.test");
        String admin = ledger.firm().adminToken();
        String invoiceId = issued(admin, ledger.clientId(), null);
        cancel(admin, invoiceId, versionOf(invoiceId), 200);

        email(admin, invoiceId, 409);

        assertThat(emails.sentMessages()).isEmpty();
        assertThat(emailStatusOf(invoiceId)).isEqualTo("NOT_SENT");
    }

    @Test
    @DisplayName("a client with no address on file is a 400, not a failed send")
    void refusesWhenThereIsNowhereToSendIt() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha5@sharma-legal.test");
        String admin = firm.adminToken();
        String clientId = createClient(admin, "Anonymous Trust", null);
        String invoiceId = issued(admin, clientId, null);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/email")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(emails.sentMessages()).isEmpty();
        // Nothing was attempted, so nothing is recorded as attempted.
        assertThat(emailStatusOf(invoiceId)).isEqualTo("NOT_SENT");
    }

    @Test
    @DisplayName("sending a bill to a client is the administrator's alone")
    void emailingIsForAdministratorsOnly() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha6@sharma-legal.test");
        String admin = ledger.firm().adminToken();
        String lawyer = inviteAndActivate(ledger.firm(), "ravi6@sharma-legal.test", "LAWYER");
        String clerk = inviteAndActivate(ledger.firm(), "clerk6@sharma-legal.test", "CLERK");
        String clientRole = inviteAndActivate(ledger.firm(), "portal6@sharma-legal.test", "CLIENT");
        String platform = platformAdminToken("asha6@sharma-legal.test");
        String invoiceId = issued(admin, ledger.clientId(), null);

        for (String token : new String[] {lawyer, clerk, clientRole, platform}) {
            email(token, invoiceId, 403);
        }
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/email"))
                .andExpect(status().isUnauthorized());

        // Refused before anything was sent, and the invoice is untouched by the refusals.
        assertThat(emails.sentMessages()).isEmpty();
        assertThat(emailStatusOf(invoiceId)).isEqualTo("NOT_SENT");

        email(admin, invoiceId, 200);
        assertThat(emails.sentMessages()).hasSize(1);
    }

    @Test
    void aFirmCannotEmailAnotherFirmsInvoice() throws Exception {
        Ledger ours = openLedger("Sharma & Associates", "asha7@sharma-legal.test");
        Ledger theirs = openLedger("Iyer & Co", "leela7@iyer-legal.test");
        String theirInvoiceId = issued(theirs.firm().adminToken(), theirs.clientId(), null);

        email(ours.firm().adminToken(), theirInvoiceId, 404);

        assertThat(emails.sentMessages()).isEmpty();
        assertThat(emailStatusOf(theirInvoiceId)).isEqualTo("NOT_SENT");
    }

    @Test
    void aNonExistentInvoiceIsNotFound() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha8@sharma-legal.test");
        email(ledger.firm().adminToken(), UUID.randomUUID().toString(), 404);
        assertThat(emails.sentMessages()).isEmpty();
    }

    /** The bytes the download endpoint serves, for comparison with the attachment. */
    private byte[] downloadedPdf(String token, String invoiceId) throws Exception {
        return mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
