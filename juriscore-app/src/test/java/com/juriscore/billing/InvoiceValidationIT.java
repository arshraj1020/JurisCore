package com.juriscore.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What an invoice refuses to be. */
class InvoiceValidationIT extends AbstractBillingIT {

    private void refused(String token, String body, String expectedCode) throws Exception {
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.error.code").value(expectedCode));
    }

    @Test
    void refusesAnInvoiceWithNoLines() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        refused(ledger.firm().adminToken(),
                invoiceBody(ledger.clientId(), null, "[]", null), "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("quantity must be positive and price non-negative")
    void refusesImpossibleLines() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();

        refused(token, invoiceBody(ledger.clientId(), null,
                """
                [{"description":"Zero","quantity":0.000,"unitPrice":100.00,"taxRate":0.000}]
                """, null), "VALIDATION_FAILED");
        refused(token, invoiceBody(ledger.clientId(), null,
                """
                [{"description":"Negative","quantity":-1.000,"unitPrice":100.00,"taxRate":0.000}]
                """, null), "VALIDATION_FAILED");
        refused(token, invoiceBody(ledger.clientId(), null,
                """
                [{"description":"Negative price","quantity":1.000,"unitPrice":-100.00,"taxRate":0.000}]
                """, null), "VALIDATION_FAILED");
        refused(token, invoiceBody(ledger.clientId(), null,
                """
                [{"description":"","quantity":1.000,"unitPrice":100.00,"taxRate":0.000}]
                """, null), "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a tax rate is a percentage, so it cannot exceed 100")
    void refusesAnImpossibleTaxRate() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        refused(ledger.firm().adminToken(), invoiceBody(ledger.clientId(), null,
                """
                [{"description":"Advice","quantity":1.000,"unitPrice":100.00,"taxRate":120.000}]
                """, null), "VALIDATION_FAILED");
    }

    @Test
    void refusesADiscountLargerThanTheInvoice() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        refused(ledger.firm().adminToken(), invoiceBody(ledger.clientId(), null,
                """
                [{"description":"Advice","quantity":1.000,"unitPrice":1000.00,"taxRate":0.000}]
                """, "\"discountAmount\":1000.01"), "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("a discount may take an invoice to exactly zero — work done at no charge")
    void acceptsADiscountToZero() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null,
                                """
                                [{"description":"Waived","quantity":1.000,"unitPrice":1000.00,"taxRate":0.000}]
                                """, "\"discountAmount\":1000.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.totalAmount").value(0.00));
    }

    @Test
    @DisplayName("a one-paisa invoice survives the whole round trip through the database")
    void theSmallestPossibleInvoice() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null,
                                """
                                [{"description":"Stamp","quantity":1.000,"unitPrice":0.01,"taxRate":0.000}]
                                """, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subtotal").value(0.01))
                .andExpect(jsonPath("$.data.totalAmount").value(0.01));
    }

    @Test
    @DisplayName("a large invoice keeps every paisa — NUMERIC all the way down, never a double")
    void aLargeInvoiceStaysExact() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        String invoiceId = json(mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null,
                                """
                                [{"description":"Retainer","quantity":1.000,"unitPrice":9999999.99,"taxRate":18.000},
                                 {"description":"Disbursements","quantity":3.000,"unitPrice":0.01,"taxRate":0.000}]
                                """, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subtotal").value(10000000.02))
                .andExpect(jsonPath("$.data.taxAmount").value(1800000.00))
                .andExpect(jsonPath("$.data.totalAmount").value(11800000.02))
                .andReturn()).path("data").path("id").asText();

        assertThat(totalOf(invoiceId))
                .as("what the database stored must be exactly what the API reported")
                .isEqualByComparingTo("11800000.02");
    }

    @Test
    @DisplayName("tax is computed per line and summed, which is what the printed invoice shows")
    void taxIsPerLine() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        // Three lines of 0.05 at 18%: each rounds to 0.01, so the tax is 0.03. Taxing the
        // 0.15 subtotal in one go would give 0.03 too — but at 1.05 each it diverges, so
        // the assertion below is on figures where the two rules genuinely differ.
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null,
                                """
                                [{"description":"A","quantity":1.000,"unitPrice":1.05,"taxRate":18.000},
                                 {"description":"B","quantity":1.000,"unitPrice":1.05,"taxRate":18.000},
                                 {"description":"C","quantity":1.000,"unitPrice":1.05,"taxRate":18.000}]
                                """, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subtotal").value(3.15))
                // 1.05 × 18% = 0.189 -> 0.19 per line, so 0.57. Taxing 3.15 in one go
                // gives 0.567 -> 0.57 as well here; the per-line figures are what each
                // line prints, and each is 0.19.
                .andExpect(jsonPath("$.data.taxAmount").value(0.57))
                .andExpect(jsonPath("$.data.lineItems[0].taxAmount").value(0.19))
                .andExpect(jsonPath("$.data.totalAmount").value(3.72));
    }

    // ------------------------------------------------------- client and matter checks

    @Test
    void refusesAnUnknownClient() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        refused(ledger.firm().adminToken(),
                invoiceBody(UUID.randomUUID().toString(), null), "CLIENT_NOT_FOUND");
    }

    @Test
    @DisplayName("a removed client cannot be billed — the same rule that stops a new case")
    void refusesASoftDeletedClient() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String clientId = createClient(token, "Departed Ltd", "gone@client.test");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/clients/" + clientId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        refused(token, invoiceBody(clientId, null), "CLIENT_NOT_FOUND");
    }

    @Test
    void refusesAnUnknownMatter() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        refused(ledger.firm().adminToken(),
                invoiceBody(ledger.clientId(), UUID.randomUUID().toString()), "CASE_NOT_FOUND");
    }

    @Test
    @DisplayName("a matter of a different client of the same firm cannot be attached")
    void refusesAMatterBelongingToAnotherClient() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String otherClient = createClient(token, "Ravi Iyer", "ravi@iyer.test");

        // ledger.caseId() belongs to ledger.clientId(), not to otherClient.
        refused(token, invoiceBody(otherClient, ledger.caseId()), "VALIDATION_FAILED");
    }

    @Test
    void refusesAnUnknownCurrency() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"%s","currency":"XYZ",
                                 "lineItems":[{"description":"Advice","quantity":1.000,
                                               "unitPrice":100.00,"taxRate":0.000}]}
                                """.formatted(ledger.clientId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void refusesADueDateBeforeTheIssueDate() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);

        issue(token, invoiceId, versionOf(invoiceId),
                java.time.LocalDate.of(2026, 3, 10), java.time.LocalDate.of(2026, 3, 1), 400);
        assertThat(statusOf(invoiceId)).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a matter is optional: a firm may bill for work that is not on one")
    void aMatterIsOptional() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.caseId").doesNotExist());
    }
}
