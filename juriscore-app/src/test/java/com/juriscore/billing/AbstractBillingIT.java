package com.juriscore.billing;

import com.juriscore.casework.AbstractCaseworkIT;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scaffolding for the Phase 5 billing integration tests.
 *
 * <p>Everything goes through the real API, following the rule the casework fixtures set: a
 * firm exists because somebody signed up, a client because somebody added one, an invoice
 * because somebody raised one. Seeding rows directly would let a test pass against a state
 * the application cannot actually produce — and for money that is exactly the class of
 * false confidence worth avoiding.
 */
public abstract class AbstractBillingIT extends AbstractCaseworkIT {

    /** A firm with a client and a matter — the starting point for every billing test. */
    protected record Ledger(Firm firm, String clientId, String caseId) {
    }

    protected Ledger openLedger(String firmName, String email) throws Exception {
        Firm firm = registerFirm(firmName, email);
        String clientId = createClient(firm.adminToken(), "Asha Menon", "asha@menon.test");
        String caseId = createCase(firm.adminToken(), clientId, "Menon v. Iyer");
        return new Ledger(firm, clientId, caseId);
    }

    /** One line: two and a half hours at ₹4,000, taxed at 18%. Totals ₹11,800. */
    protected String invoiceBody(String clientId, String caseId) {
        return """
                {
                  "clientId": "%s",
                  %s
                  "currency": "INR",
                  "notes": "Fees for March",
                  "lineItems": [
                    {"description":"Drafting written statement","quantity":2.500,
                     "unitPrice":4000.00,"taxRate":18.000}
                  ]
                }
                """.formatted(clientId, caseId == null ? "" : "\"caseId\": \"" + caseId + "\",");
    }

    protected String invoiceBody(String clientId, String caseId, String lineItemsJson,
                                 String extra) {
        return """
                {
                  "clientId": "%s",
                  %s
                  "currency": "INR",
                  %s
                  "lineItems": %s
                }
                """.formatted(clientId, caseId == null ? "" : "\"caseId\": \"" + caseId + "\",",
                extra == null ? "" : extra + ",", lineItemsJson);
    }

    /** Raises a draft and returns its id. */
    protected String draft(String token, String clientId, String caseId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(clientId, caseId)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    /** Raises a draft and issues it, so it can be paid. */
    protected String issued(String token, String clientId, String caseId) throws Exception {
        String invoiceId = draft(token, clientId, caseId);
        issue(token, invoiceId, versionOf(invoiceId), null, null, 200);
        return invoiceId;
    }

    /** Raises and issues an invoice whose due date is already in the past. */
    protected String overdueCandidate(String token, String clientId) throws Exception {
        String invoiceId = draft(token, clientId, null);
        LocalDate issueDate = LocalDate.now(ZoneOffset.UTC).minusDays(60);
        issue(token, invoiceId, versionOf(invoiceId), issueDate, issueDate.plusDays(30), 200);
        return invoiceId;
    }

    protected void issue(String token, String invoiceId, long version, LocalDate issueDate,
                         LocalDate dueDate, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/issue")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d%s%s}
                                """.formatted(version,
                                issueDate == null ? "" : ",\"issueDate\":\"" + issueDate + "\"",
                                dueDate == null ? "" : ",\"dueDate\":\"" + dueDate + "\"")))
                .andExpect(status().is(expectedStatus));
    }

    protected void cancel(String token, String invoiceId, long version, int expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/cancel")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"reason\":\"Raised in error\"}".formatted(version)))
                .andExpect(status().is(expectedStatus));
    }

    protected String paymentBody(String amount, String currency) {
        return """
                {"amount":%s,"currency":"%s","method":"UPI","reference":"UTR 220414512345"}
                """.formatted(amount, currency);
    }

    protected void pay(String token, String invoiceId, String amount, int expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(amount, "INR")))
                .andExpect(status().is(expectedStatus));
    }

    // ------------------------------------------------------------- database inspection

    protected String statusOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM billing.invoices WHERE id = ?::uuid", String.class, invoiceId);
    }

    protected long versionOf(String invoiceId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM billing.invoices WHERE id = ?::uuid", Long.class, invoiceId);
        return version == null ? -1 : version;
    }

    protected String numberOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT invoice_number FROM billing.invoices WHERE id = ?::uuid",
                String.class, invoiceId);
    }

    protected BigDecimal totalOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT total_amount FROM billing.invoices WHERE id = ?::uuid",
                BigDecimal.class, invoiceId);
    }

    protected BigDecimal paidOn(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM billing.payments WHERE invoice_id = ?::uuid",
                BigDecimal.class, invoiceId);
    }

    protected long paymentCount(String invoiceId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.payments WHERE invoice_id = ?::uuid",
                Long.class, invoiceId);
        return count == null ? 0 : count;
    }

    protected UUID organizationOf(String invoiceId) {
        return jdbcTemplate.queryForObject(
                "SELECT organization_id FROM billing.invoices WHERE id = ?::uuid",
                UUID.class, invoiceId);
    }
}
