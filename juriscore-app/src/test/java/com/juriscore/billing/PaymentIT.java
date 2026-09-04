package com.juriscore.billing;

import com.juriscore.billing.event.InvoicePaidEvent;
import com.juriscore.billing.event.PaymentRecordedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recording money against an invoice, and what the balance does in response. */
class PaymentIT extends AbstractBillingIT {

    @Test
    @DisplayName("a part payment moves the invoice to PARTIALLY_PAID and shows the balance")
    void aPartPayment() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        events.clear();

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("4000.00", "INR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(4000.00))
                .andExpect(jsonPath("$.data.currency").value("INR"))
                .andExpect(jsonPath("$.data.method").value("UPI"))
                .andExpect(jsonPath("$.data.reference").value("UTR 220414512345"))
                .andExpect(jsonPath("$.data.paymentDate").isNotEmpty());

        assertThat(statusOf(invoiceId)).isEqualTo("PARTIALLY_PAID");
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.amountPaid").value(4000.00))
                .andExpect(jsonPath("$.data.amountDue").value(7800.00))
                .andExpect(jsonPath("$.data.paidAt").doesNotExist());

        assertThat(events.require(PaymentRecordedEvent.class).getAmountDue())
                .isEqualByComparingTo("7800.00");
        assertThat(events.latest(InvoicePaidEvent.class))
                .as("a part payment does not settle the invoice")
                .isEmpty();
    }

    @Test
    @DisplayName("payments accumulate, and the one that clears the balance settles the invoice")
    void severalPaymentsSettleTheInvoice() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        pay(token, invoiceId, "4000.00", 201);
        pay(token, invoiceId, "3000.00", 201);
        assertThat(statusOf(invoiceId)).isEqualTo("PARTIALLY_PAID");
        events.clear();

        pay(token, invoiceId, "4800.00", 201);

        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
        assertThat(paidOn(invoiceId)).isEqualByComparingTo("11800.00");
        assertThat(paymentCount(invoiceId)).isEqualTo(3);
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.data.amountDue").value(0.00))
                .andExpect(jsonPath("$.data.paidAt").isNotEmpty());

        assertThat(events.require(InvoicePaidEvent.class).getTotalAmount())
                .isEqualByComparingTo("11800.00");
    }

    @Test
    @DisplayName("overpayment is refused down to the last paisa, and nothing is written")
    void overpaymentIsRefused() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "11799.99", 201);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("0.02", "INR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(paymentCount(invoiceId)).isEqualTo(1);
        assertThat(paidOn(invoiceId)).isEqualByComparingTo("11799.99");
        assertThat(statusOf(invoiceId)).isEqualTo("PARTIALLY_PAID");

        // The exact remaining paisa is payable — the boundary is inclusive.
        pay(token, invoiceId, "0.01", 201);
        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
    }

    @Test
    @DisplayName("a payment in another currency is refused, never converted")
    void aCurrencyMismatchIsRefused() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("100.00", "USD")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(paymentCount(invoiceId)).isZero();
    }

    @Test
    @DisplayName("a draft has not been sent to anybody, so it cannot be paid")
    void aDraftCannotBePaid() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = draft(token, ledger.clientId(), null);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("100.00", "INR")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
    }

    @Test
    void aCancelledInvoiceCannotBePaid() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        cancel(token, invoiceId, versionOf(invoiceId), 200);

        pay(token, invoiceId, "100.00", 409);
        assertThat(paymentCount(invoiceId)).isZero();
    }

    @Test
    void aSettledInvoiceTakesNoMoreMoney() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "11800.00", 201);

        pay(token, invoiceId, "0.01", 409);
        assertThat(paymentCount(invoiceId)).isEqualTo(1);
    }

    @Test
    void aZeroOrNegativePaymentIsRefused() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        pay(token, invoiceId, "0.00", 400);
        pay(token, invoiceId, "-100.00", 400);
        assertThat(paymentCount(invoiceId)).isZero();
    }

    @Test
    @DisplayName("cancelling an invoice does not delete the money already recorded against it")
    void cancellingKeepsRecordedPayments() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);
        pay(token, invoiceId, "4000.00", 201);

        cancel(token, invoiceId, versionOf(invoiceId), 200);

        assertThat(statusOf(invoiceId)).isEqualTo("CANCELLED");
        assertThat(paymentCount(invoiceId))
                .as("a cancellation says the invoice should not have been raised, not that "
                        + "the money never arrived")
                .isEqualTo(1);
        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1));
    }

    @Test
    void listsPaymentsMostRecentFirst() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000.00,"currency":"INR","paymentDate":"2026-03-01",
                                 "method":"CHEQUE","reference":"000123"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":2000.00,"currency":"INR","paymentDate":"2026-03-15",
                                 "method":"BANK_TRANSFER"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].paymentDate").value("2026-03-15"))
                .andExpect(jsonPath("$.data.items[0].method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.data.items[1].method").value("CHEQUE"));
    }

    @Test
    @DisplayName("an overdue invoice still accepts money — it arrives late, and usually does")
    void anOverdueInvoiceCanBePaid() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = overdueCandidate(token, ledger.clientId());
        jdbcTemplate.update(
                "UPDATE billing.invoices SET status = 'OVERDUE' WHERE id = ?::uuid", invoiceId);

        pay(token, invoiceId, "4000.00", 201);
        assertThat(statusOf(invoiceId)).isEqualTo("PARTIALLY_PAID");

        pay(token, invoiceId, "7800.00", 201);
        assertThat(statusOf(invoiceId)).isEqualTo("PAID");
    }
}
