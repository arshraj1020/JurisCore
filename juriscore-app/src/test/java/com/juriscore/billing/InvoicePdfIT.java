package com.juriscore.billing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/invoices/{id}/pdf} — Phase 7's first milestone.
 *
 * <p>Three things this test suite exists to prove, beyond "the endpoint returns bytes":
 * the PDF is issued in the <em>firm's</em> identity and never JurisCore's; a firm that has
 * never touched its billing settings still gets a usable document rather than an error;
 * and the same authorization and tenant-isolation rules that guard every other invoice
 * endpoint guard this one too, because a PDF is a more complete copy of an invoice than
 * the JSON is, not a lesser one.
 */
class InvoicePdfIT extends AbstractBillingIT {

    @Test
    @DisplayName("the PDF carries the firm's identity, not JurisCore's, and is audited")
    void downloadsAPdfBrandedAsTheFirmAndRecordsItInTheAuditTrail() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String admin = ledger.firm().adminToken();

        mockMvc.perform(patch("/api/v1/billing/profile")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Sharma & Associates LLP",
                                 "taxRegistration":"29ABCDE1234F1Z5",
                                 "billingEmail":"billing@sharma-legal.test",
                                 "invoiceNotes":"Payment due within 30 days by NEFT."}
                                """))
                .andExpect(status().isOk());

        String invoiceId = issued(admin, ledger.clientId(), null);
        String invoiceNumber = numberOf(invoiceId);

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("invoice-" + invoiceNumber)))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        assertThat(pdf).isNotEmpty();
        String text = extractText(pdf);

        assertThat(text).contains("Sharma & Associates LLP");
        assertThat(text).contains("29ABCDE1234F1Z5");
        assertThat(text).contains(invoiceNumber);
        assertThat(text).contains("Asha Menon"); // the client from openLedger
        // The invoice's own note, not the firm's standing one: invoiceBody() supplies
        // "Fees for March", and InvoiceService.create keeps a caller's note in preference to
        // copying the profile's. See theFirmsStandingNotesReachAnInvoiceRaisedWithoutItsOwn
        // for the other half of that rule.
        assertThat(text).contains("Fees for March");
        assertThat(text).doesNotContainIgnoringCase("jurisCore");

        mockMvc.perform(get("/api/v1/audit").param("action", "invoice.pdf_downloaded")
                        .param("entityId", invoiceId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].entityType").value("INVOICE"));
    }

    @Test
    @DisplayName("a firm's standing payment instructions print on an invoice raised without its own")
    void theFirmsStandingNotesReachAnInvoiceRaisedWithoutItsOwn() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha6@sharma-legal.test");
        String admin = ledger.firm().adminToken();

        mockMvc.perform(patch("/api/v1/billing/profile")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Sharma & Associates LLP",
                                 "invoiceNotes":"Payment due within 30 days by NEFT."}
                                """))
                .andExpect(status().isOk());

        // No "notes" in the body, so InvoiceService.create copies the firm's boilerplate onto
        // the invoice. That copy — not a lookup at render time — is how standing payment
        // instructions reach the PDF, and it is what freezes them against later edits to the
        // firm's settings.
        MvcResult created = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null, """
                                [{"description":"Appearance before the tribunal","quantity":1.000,
                                  "unitPrice":15000.00,"taxRate":18.000}]
                                """, null)))
                .andExpect(status().isCreated())
                .andReturn();
        String invoiceId = json(created).path("data").path("id").asText();

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(extractText(result.getResponse().getContentAsByteArray()))
                .contains("Payment due within 30 days by NEFT.");
    }

    @Test
    void aFirmThatHasNeverTouchedBillingSettingsStillGetsAUsablePdf() throws Exception {
        // No PATCH to /api/v1/billing/profile at all: BillingProfileService answers with
        // an unsaved, all-null profile, and the PDF must fall back to the organization's
        // own name rather than fail or print nothing.
        Ledger ledger = openLedger("Menon Legal LLP", "priya@menon-legal.test");
        String invoiceId = issued(ledger.firm().adminToken(), ledger.clientId(), null);

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isOk())
                .andReturn();

        String text = extractText(result.getResponse().getContentAsByteArray());
        assertThat(text).contains("Menon Legal LLP");
        assertThat(text.toLowerCase()).doesNotContain("null");
    }

    @Test
    void readingIsOpenToFirmStaffAndDeniedToOthers() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha2@sharma-legal.test");
        String admin = ledger.firm().adminToken();
        String lawyer = inviteAndActivate(ledger.firm(), "ravi2@sharma-legal.test", "LAWYER");
        String clerk = inviteAndActivate(ledger.firm(), "clerk2@sharma-legal.test", "CLERK");
        String clientRole = inviteAndActivate(ledger.firm(), "portal2@sharma-legal.test", "CLIENT");
        String platform = platformAdminToken("asha2@sharma-legal.test");
        String invoiceId = issued(admin, ledger.clientId(), null);

        for (String token : new String[] {admin, lawyer, clerk}) {
            mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }
        for (String token : new String[] {clientRole, platform}) {
            mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isForbidden());
        }

        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/pdf"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aFirmCannotDownloadAnotherFirmsInvoicePdf() throws Exception {
        Ledger ours = openLedger("Sharma & Associates", "asha3@sharma-legal.test");
        Ledger theirs = openLedger("Iyer & Co", "leela@iyer-legal.test");
        String theirInvoiceId = issued(theirs.firm().adminToken(), theirs.clientId(), null);

        mockMvc.perform(get("/api/v1/invoices/" + theirInvoiceId + "/pdf")
                        .header("Authorization", bearer(ours.firm().adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aNonExistentInvoiceIsNotFound() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha4@sharma-legal.test");
        mockMvc.perform(get("/api/v1/invoices/" + java.util.UUID.randomUUID() + "/pdf")
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDraftInvoiceCanAlsoBeDownloadedBeforeItIsIssued() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha5@sharma-legal.test");
        String draftId = draft(ledger.firm().adminToken(), ledger.clientId(), null);

        MvcResult result = mockMvc.perform(get("/api/v1/invoices/" + draftId + "/pdf")
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isOk())
                .andReturn();

        String text = extractText(result.getResponse().getContentAsByteArray());
        assertThat(text).contains("Draft");
    }

    private static String extractText(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
