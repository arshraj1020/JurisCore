package com.juriscore.billing;

import com.juriscore.billing.event.InvoiceCancelledEvent;
import com.juriscore.billing.event.InvoiceCreatedEvent;
import com.juriscore.billing.event.InvoiceIssuedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole invoice flow: raise, edit, issue, cancel — and what each of those freezes. */
class InvoiceLifecycleIT extends AbstractBillingIT {

    @Test
    @DisplayName("raising an invoice numbers it, prices it and leaves it a draft")
    void raisingADraft() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();

        MvcResult result = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), ledger.caseId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.clientId").value(ledger.clientId()))
                .andExpect(jsonPath("$.data.caseId").value(ledger.caseId()))
                .andExpect(jsonPath("$.data.subtotal").value(10000.00))
                .andExpect(jsonPath("$.data.taxAmount").value(1800.00))
                .andExpect(jsonPath("$.data.discountAmount").value(0.00))
                .andExpect(jsonPath("$.data.totalAmount").value(11800.00))
                .andExpect(jsonPath("$.data.amountPaid").value(0.00))
                .andExpect(jsonPath("$.data.amountDue").value(11800.00))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist())
                .andExpect(jsonPath("$.data.lineItems[0].amount").value(10000.00))
                .andExpect(jsonPath("$.data.lineItems[0].taxAmount").value(1800.00))
                .andExpect(jsonPath("$.data.lineItems[0].sortOrder").value(0))
                .andReturn();

        int year = LocalDate.now(ZoneOffset.UTC).getYear();
        assertThat(json(result).path("data").path("invoiceNumber").asText())
                .isEqualTo("INV-%d-000001".formatted(year));
        assertThat(events.require(InvoiceCreatedEvent.class).eventType())
                .isEqualTo("invoice.created");
    }

    @Test
    @DisplayName("a firm's invoice numbers run in sequence, and are its own")
    void numberingIsPerFirmAndSequential() throws Exception {
        Ledger mine = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        Ledger theirs = openLedger("Kulkarni Chambers", "ravi@kulkarni-legal.test");
        int year = LocalDate.now(ZoneOffset.UTC).getYear();

        String first = draft(mine.firm().adminToken(), mine.clientId(), null);
        String second = draft(mine.firm().adminToken(), mine.clientId(), null);
        String other = draft(theirs.firm().adminToken(), theirs.clientId(), null);

        assertThat(numberOf(first)).isEqualTo("INV-%d-000001".formatted(year));
        assertThat(numberOf(second)).isEqualTo("INV-%d-000002".formatted(year));
        assertThat(numberOf(other))
                .as("each firm counts on its own; the number is unique within a firm, not globally")
                .isEqualTo("INV-%d-000001".formatted(year));
    }

    @Test
    @DisplayName("a client cannot choose its own invoice number — there is no field for one")
    void theInvoiceNumberCannotBeSupplied() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        int year = LocalDate.now(ZoneOffset.UTC).getYear();

        MvcResult result = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(ledger.firm().adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"%s","currency":"INR",
                                 "invoiceNumber":"INV-1999-000001","organizationId":"%s",
                                 "subtotal":1.00,"taxAmount":0.00,"totalAmount":1.00,
                                 "status":"PAID",
                                 "lineItems":[{"description":"Advice","quantity":1.000,
                                               "unitPrice":500.00,"taxRate":0.000}]}
                                """.formatted(ledger.clientId(), java.util.UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.totalAmount").value(500.00))
                .andReturn();

        String invoiceId = json(result).path("data").path("id").asText();
        assertThat(numberOf(invoiceId)).isEqualTo("INV-%d-000001".formatted(year));
        assertThat(organizationOf(invoiceId)).hasToString(ledger.firm().id());
    }

    // ------------------------------------------------------------------------- editing

    @Test
    void aDraftCanBeRepricedEntirely() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), ledger.caseId());

        mockMvc.perform(patch("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d,"notes":"Revised","discountAmount":800.00,
                                 "lineItems":[
                                   {"description":"Appearance","quantity":1.000,
                                    "unitPrice":15000.00,"taxRate":18.000},
                                   {"description":"Filing fee","quantity":1.000,
                                    "unitPrice":250.00,"taxRate":0.000}]}
                                """.formatted(versionOf(invoiceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotal").value(15250.00))
                .andExpect(jsonPath("$.data.taxAmount").value(2700.00))
                .andExpect(jsonPath("$.data.discountAmount").value(800.00))
                .andExpect(jsonPath("$.data.totalAmount").value(17150.00))
                .andExpect(jsonPath("$.data.lineItems.length()").value(2))
                .andExpect(jsonPath("$.data.notes").value("Revised"));
    }

    @Test
    @DisplayName("a stale version loses, and the winning edit stands")
    void concurrentEditsAreRefusedOnAStaleVersion() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);
        long versionBothRead = versionOf(invoiceId);

        mockMvc.perform(patch("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"notes\":\"First\"}".formatted(versionBothRead)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"notes\":\"Second\"}".formatted(versionBothRead)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(get("/api/v1/invoices/" + invoiceId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.notes").value("First"));
    }

    // -------------------------------------------------------------------------- issuing

    @Test
    void issuingFreezesTheInvoiceAndStampsItsDates() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);
        events.clear();

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/issue")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d,"issueDate":"2026-03-01","dueDate":"2026-03-31"}
                                """.formatted(versionOf(invoiceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.issueDate").value("2026-03-01"))
                .andExpect(jsonPath("$.data.dueDate").value("2026-03-31"));

        assertThat(events.require(InvoiceIssuedEvent.class).getDueDate())
                .isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    @DisplayName("issuing with no dates defaults to today and thirty days")
    void issuingDefaultsTheDates() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);

        issue(token, invoiceId, versionOf(invoiceId), null, null, 200);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.issueDate").value(today.toString()))
                .andExpect(jsonPath("$.data.dueDate").value(today.plusDays(30).toString()));
    }

    @Test
    @DisplayName("once issued, only the notes may change — the figures are a 409")
    void anIssuedInvoiceIsFinanciallyFrozen() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(patch("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d,\"notes\":\"Chased by phone\"}"
                                .formatted(versionOf(invoiceId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes").value("Chased by phone"));

        mockMvc.perform(patch("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d,"lineItems":[{"description":"Cheaper",
                                 "quantity":1.000,"unitPrice":1.00,"taxRate":0.000}]}
                                """.formatted(versionOf(invoiceId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));

        assertThat(totalOf(invoiceId)).isEqualByComparingTo("11800.00");
    }

    @Test
    @DisplayName("issuing twice is a 409, whatever the invoice's contents look like")
    void issuingTwiceIsRefused() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/issue")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":%d}".formatted(versionOf(invoiceId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    // ----------------------------------------------------------------------- cancelling

    @Test
    void cancellingIsTerminalAndRecordsTheReason() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        events.clear();

        cancel(token, invoiceId, versionOf(invoiceId), 200);

        assertThat(statusOf(invoiceId)).isEqualTo("CANCELLED");
        assertThat(events.require(InvoiceCancelledEvent.class).eventType())
                .isEqualTo("invoice.cancelled");

        mockMvc.perform(get("/api/v1/invoices/" + invoiceId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.data.notes").value(org.hamcrest.Matchers
                        .containsString("Cancelled: Raised in error")));

        // Terminal: neither issuing nor cancelling again is allowed.
        issue(token, invoiceId, versionOf(invoiceId), null, null, 409);
        cancel(token, invoiceId, versionOf(invoiceId), 409);
    }

    @Test
    @DisplayName("a draft can be withdrawn: a number is already burned, so there is no delete")
    void aDraftCanBeCancelled() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);

        cancel(token, invoiceId, versionOf(invoiceId), 200);

        assertThat(statusOf(invoiceId)).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM billing.invoices WHERE id = ?::uuid", Long.class, invoiceId))
                .as("the row stays, so the firm can account for the gap in its numbering")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a settled invoice cannot be withdrawn — that would be rewriting history")
    void aSettledInvoiceCannotBeCancelled() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "11800.00", 201);

        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
        cancel(token, invoiceId, versionOf(invoiceId), 409);
        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
    }

    // -------------------------------------------------------------------------- reading

    @Test
    void listsNewestFirstAndFiltersByStatusClientAndMatter() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String otherClient = createClient(token, "Ravi Iyer", "ravi@iyer.test");

        String first = draft(token, ledger.clientId(), ledger.caseId());
        String second = draft(token, otherClient, null);
        issue(token, second, versionOf(second), null, null, 200);

        mockMvc.perform(get("/api/v1/invoices").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(second))
                // A page carries the balance but not the lines. Absent rather than empty:
                // an invoice always has at least one line, so an omitted field can only
                // mean "not included in a list view".
                .andExpect(jsonPath("$.data.items[0].totalAmount").value(11800.00))
                .andExpect(jsonPath("$.data.items[0].amountDue").value(11800.00))
                .andExpect(jsonPath("$.data.items[0].lineItems").doesNotExist());

        // Fetching one invoice does return them.
        mockMvc.perform(get("/api/v1/invoices/" + second).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineItems.length()").value(1))
                .andExpect(jsonPath("$.data.lineItems[0].amount").value(10000.00));

        mockMvc.perform(get("/api/v1/invoices").param("status", "DRAFT")
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));

        mockMvc.perform(get("/api/v1/invoices").param("clientId", otherClient)
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(second));

        mockMvc.perform(get("/api/v1/invoices").param("caseId", ledger.caseId())
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first));
    }

    @Test
    void anUnknownInvoiceIsNotFound() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/invoices/" + java.util.UUID.randomUUID())
                        .header("Authorization", bearer(ledger.firm().adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("INVOICE_NOT_FOUND"));
    }
}
