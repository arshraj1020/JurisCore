package com.juriscore.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant boundary, on every billing verb.
 *
 * <p>404 and never 403, which is the platform's established convention and matters more
 * here than anywhere: a 403 on an invoice id confirms that invoice exists, and what a rival
 * firm bills is exactly the kind of thing an id should not be able to confirm.
 */
class InvoiceTenantIsolationIT extends AbstractBillingIT {

    @Test
    @DisplayName("another firm's invoice answers not-found on every verb")
    void aForeignInvoiceIsNotFoundEverywhere() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirInvoice = issued(theirs.firm().adminToken(), theirs.clientId(), null);
        String token = mine.firm().adminToken();

        mockMvc.perform(get("/api/v1/invoices/" + theirInvoice)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/invoices/" + theirInvoice + "/payments")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/invoices/" + theirInvoice)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"notes\":\"Hijacked\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));

        issue(token, theirInvoice, 0, null, null, 404);
        cancel(token, theirInvoice, 0, 404);
        pay(token, theirInvoice, "100.00", 404);
    }

    @Test
    @DisplayName("a refused cross-tenant request changes nothing at all")
    void aForeignRequestLeavesTheInvoiceUntouched() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirInvoice = issued(theirs.firm().adminToken(), theirs.clientId(), null);

        cancel(mine.firm().adminToken(), theirInvoice, versionOf(theirInvoice), 404);
        pay(mine.firm().adminToken(), theirInvoice, "100.00", 404);

        assertThat(statusOf(theirInvoice)).isEqualTo("ISSUED");
        assertThat(paymentCount(theirInvoice)).isZero();
    }

    @Test
    void aForeignListReturnsNothing() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        draft(mine.firm().adminToken(), mine.clientId(), null);
        draft(theirs.firm().adminToken(), theirs.clientId(), null);
        draft(theirs.firm().adminToken(), theirs.clientId(), null);

        mockMvc.perform(get("/api/v1/invoices")
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    @DisplayName("another firm's client cannot be billed")
    void aForeignClientCannotBeInvoiced() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(theirs.clientId(), null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.invoices", Long.class))
                .as("no row, and therefore no invoice number burned, for a client the caller "
                        + "cannot see")
                .isZero();
    }

    @Test
    @DisplayName("another firm's matter cannot be attached")
    void aForeignCaseCannotBeAttached() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(mine.clientId(), theirs.caseId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a filter naming another firm's client returns not-found, not an empty page")
    void aForeignFilterIsNotAWayToProbe() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(get("/api/v1/invoices").param("clientId", theirs.clientId())
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CLIENT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/invoices").param("caseId", theirs.caseId())
                        .header("Authorization", bearer(mine.firm().adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("CASE_NOT_FOUND"));
    }

    @Test
    @DisplayName("an id that never existed answers exactly as one belonging to somebody else")
    void enumerationRevealsNothing() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        String theirInvoice = issued(theirs.firm().adminToken(), theirs.clientId(), null);
        String token = mine.firm().adminToken();

        MvcResult forRandom = mockMvc.perform(get("/api/v1/invoices/" + UUID.randomUUID())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult forTheirs = mockMvc.perform(get("/api/v1/invoices/" + theirInvoice)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound()).andReturn();

        assertThat(json(forTheirs).path("error").path("code").asText())
                .as("the two answers must be indistinguishable, or invoice ids become an "
                        + "oracle for what other firms bill")
                .isEqualTo(json(forRandom).path("error").path("code").asText());
    }

    @Test
    @DisplayName("each firm's billing settings are its own")
    void billingProfilesAreScopedToTheFirm() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(patch("/api/v1/billing/profile")
                        .header("Authorization", bearer(mine.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"Sharma & Associates LLP\",\"invoicePrefix\":\"SA\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/billing/profile")
                        .header("Authorization", bearer(theirs.firm().adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.legalName").doesNotExist())
                .andExpect(jsonPath("$.data.invoicePrefix").value("INV"));
    }
}
