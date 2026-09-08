package com.juriscore.billing;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The decimal wire contract: every money-shaped field leaves this API as a JSON string.
 *
 * <p>This is not a formatting preference. The frontend keeps money as strings all the way
 * to the formatter precisely so that no invoice figure is ever routed through a
 * JavaScript double; when the backend sent numbers instead, that code met a
 * {@code number} where it expected a {@code String}, called a string method on it, and
 * took the whole React tree down with it. The assertions below therefore check the JSON
 * <em>node type</em>, not just the value — {@code value("11800.00")} alone would pass
 * against a number too, which is exactly how the mismatch survived the previous suite.
 */
class InvoiceDecimalContractIT extends AbstractBillingIT {

    /** Every decimal field on an invoice response. */
    private static final List<String> INVOICE_DECIMALS = List.of(
            "subtotal", "taxAmount", "discountAmount", "totalAmount", "amountPaid", "amountDue");

    private static final List<String> LINE_DECIMALS = List.of(
            "quantity", "unitPrice", "amount", "taxRate", "taxAmount");

    @Test
    @DisplayName("an invoice's decimals are JSON strings, at full scale, on create and on read")
    void invoiceDecimalsAreStrings() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();

        MvcResult created = mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), ledger.caseId())))
                .andExpect(status().isCreated())
                .andReturn();

        assertDecimalsAreStrings(json(created).path("data"));

        String invoiceId = json(created).path("data").path("id").asText();
        MvcResult fetched = mockMvc.perform(get("/api/v1/invoices/" + invoiceId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode invoice = json(fetched).path("data");
        assertDecimalsAreStrings(invoice);

        // The exact digits the server decided on, scale and all. A JSON number would have
        // dropped the trailing zeros, and "2.5 hours" is not what the invoice says.
        assertThat(invoice.path("totalAmount").asText()).isEqualTo("11800.00");
        assertThat(invoice.path("lineItems").get(0).path("quantity").asText()).isEqualTo("2.500");
        assertThat(invoice.path("lineItems").get(0).path("unitPrice").asText()).isEqualTo("4000.00");
    }

    @Test
    @DisplayName("list responses carry string decimals too")
    void listDecimalsAreStrings() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), ledger.caseId())))
                .andExpect(status().isCreated());

        MvcResult listed = mockMvc.perform(get("/api/v1/invoices")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode summary = json(listed).path("data").path("items").get(0);
        for (String field : INVOICE_DECIMALS) {
            assertThat(summary.path(field).isTextual())
                    .as("list item field '%s' must be a JSON string, was %s",
                            field, summary.path(field).getNodeType())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a payment's amount is a JSON string")
    void paymentAmountIsAString() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();
        String invoiceId = issued(token, ledger.clientId(), null);

        MvcResult payment = mockMvc.perform(post("/api/v1/invoices/" + invoiceId + "/payments")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("4000.00", "INR")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value("4000.00"))
                .andReturn();

        assertThat(json(payment).path("data").path("amount").isTextual()).isTrue();
    }

    /**
     * Numbers are still accepted <em>into</em> the API.
     *
     * <p>The contract change is one-directional on purpose: Jackson reads a
     * {@code BigDecimal} from a JSON number or a JSON string either way, so no existing
     * client that posts {@code "quantity": 2.5} is broken by it. The other fixtures in
     * this suite post numeric line items; this asserts that stays true deliberately
     * rather than by accident.
     */
    @Test
    @DisplayName("numeric decimals are still accepted in request bodies")
    void numericRequestBodiesStillWork() throws Exception {
        Ledger ledger = openLedger("Sharma & Associates", "asha@sharma-legal.test");
        String token = ledger.firm().adminToken();

        mockMvc.perform(post("/api/v1/invoices")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invoiceBody(ledger.clientId(), null, """
                                [{"description":"Conference","quantity":1.500,
                                  "unitPrice":2000.00,"taxRate":18.000}]
                                """, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.subtotal").value("3000.00"))
                .andExpect(jsonPath("$.data.lineItems[0].quantity").value("1.500"));
    }

    private void assertDecimalsAreStrings(JsonNode invoice) {
        for (String field : INVOICE_DECIMALS) {
            assertThat(invoice.path(field).isTextual())
                    .as("invoice field '%s' must be a JSON string, was %s",
                            field, invoice.path(field).getNodeType())
                    .isTrue();
        }
        JsonNode line = invoice.path("lineItems").get(0);
        assertThat(line).as("the invoice should carry its lines").isNotNull();
        for (String field : LINE_DECIMALS) {
            assertThat(line.path(field).isTextual())
                    .as("line item field '%s' must be a JSON string, was %s",
                            field, line.path(field).getNodeType())
                    .isTrue();
        }
    }
}
