package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.InvoiceLineItemRequest;
import com.juriscore.billing.domain.Invoice;
import com.juriscore.billing.domain.InvoiceLineItem;
import com.juriscore.billing.domain.InvoiceStatus;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic that turns requested lines into an invoice.
 *
 * <p>The assertion that matters most is repeated throughout: after every call,
 * {@code total == subtotal + tax − discount} exactly. That identity is what
 * {@code ck_invoices_total} enforces in the database, so a case where it does not hold is
 * a row PostgreSQL would refuse.
 */
class InvoiceCalculatorTest {

    private final InvoiceCalculator calculator = new InvoiceCalculator();

    private static Invoice blank() {
        Invoice invoice = new Invoice();
        invoice.setStatus(InvoiceStatus.DRAFT);
        return invoice;
    }

    private static InvoiceLineItemRequest line(String description, String quantity,
                                               String unitPrice, String taxRate) {
        return new InvoiceLineItemRequest(description, new BigDecimal(quantity),
                new BigDecimal(unitPrice), taxRate == null ? null : new BigDecimal(taxRate));
    }

    private static void assertTotalIdentityHolds(Invoice invoice) {
        assertThat(invoice.getTotalAmount())
                .as("total must equal subtotal + tax − discount, which is what "
                        + "ck_invoices_total asserts in the database")
                .isEqualByComparingTo(invoice.getSubtotal()
                        .add(invoice.getTaxAmount())
                        .subtract(invoice.getDiscountAmount()));
    }

    @Test
    void pricesASingleLine() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("Drafting", "2.500", "4000.00", "18.000")), null);

        InvoiceLineItem item = invoice.getLineItems().get(0);
        assertThat(item.getAmount()).isEqualByComparingTo("10000.00");
        assertThat(item.getTaxAmount()).isEqualByComparingTo("1800.00");
        assertThat(item.getSortOrder()).isZero();
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("10000.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("1800.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("11800.00");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("several lines sum exactly, and keep the order they were sent in")
    void pricesSeveralLines() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(
                line("Appearance", "1.000", "15000.00", "18.000"),
                line("Drafting", "3.500", "4000.00", "18.000"),
                line("Filing fee", "1.000", "250.00", "0.000"),
                line("Photocopying", "120.000", "2.50", "5.000")), null);

        assertThat(invoice.getLineItems()).extracting(InvoiceLineItem::getSortOrder)
                .containsExactly(0, 1, 2, 3);
        assertThat(invoice.getLineItems()).extracting(InvoiceLineItem::getAmount)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("15000.00", "14000.00", "250.00", "300.00");
        // Tax is per line and then summed, not computed once on the subtotal — so each
        // figure matches the one printed beside its own line.
        assertThat(invoice.getLineItems()).extracting(InvoiceLineItem::getTaxAmount)
                .extracting(BigDecimal::toPlainString)
                .containsExactly("2700.00", "2520.00", "0.00", "15.00");
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("29550.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("5235.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("34785.00");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("a one-paisa line survives the whole calculation")
    void theSmallestPossibleInvoice() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("Stamp", "1.000", "0.01", "0.000")), null);

        assertThat(invoice.getSubtotal()).isEqualByComparingTo("0.01");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.01");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("repeating quantities round once, at the line, and never again")
    void repeatingQuantities() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(
                line("Third of an hour", "0.333", "3000.00", "18.000"),
                line("Third of an hour", "0.333", "3000.00", "18.000"),
                line("Third of an hour", "0.333", "3000.00", "18.000")), null);

        // 0.333 × 3000 = 999.00 exactly; three of them is 2997.00, not 2999.99…
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("2997.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("539.46");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    void appliesADiscountAfterTax() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("Advice", "1.000", "10000.00", "18.000")),
                new BigDecimal("800.00"));

        assertThat(invoice.getSubtotal()).isEqualByComparingTo("10000.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("1800.00");
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("800.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("11000.00");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("a discount may take the invoice to exactly zero, but not below it")
    void discountBounds() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("Waived", "1.000", "1000.00", "0.000")),
                new BigDecimal("1000.00"));
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.00");
        assertTotalIdentityHolds(invoice);

        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Waived", "1.000", "1000.00", "0.000")), new BigDecimal("1000.01")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void refusesANegativeDiscount() {
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Advice", "1.000", "100.00", null)), new BigDecimal("-1.00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void refusesAnInvoiceWithNoLines() {
        assertThatThrownBy(() -> calculator.applyLines(blank(), List.of(), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one line");
        assertThatThrownBy(() -> calculator.applyLines(blank(), null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("quantity must be positive, price non-negative, rate a percentage")
    void refusesInvalidLines() {
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Zero", "0.000", "100.00", null)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Negative quantity", "-1.000", "100.00", null)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("greater than zero");
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Negative price", "1.000", "-100.00", null)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("Over 100%", "1.000", "100.00", "120.000")), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("percentage");
        assertThatThrownBy(() -> calculator.applyLines(blank(),
                List.of(line("  ", "1.000", "100.00", null)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("description");
    }

    @Test
    @DisplayName("a zero-value line is allowed: work done at no charge is still work recorded")
    void zeroValueLinesAreAllowed() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(
                line("Advice", "1.000", "5000.00", "18.000"),
                line("Courtesy call — no charge", "1.000", "0.00", "0.000")), null);

        assertThat(invoice.getLineItems()).hasSize(2);
        assertThat(invoice.getLineItems().get(1).getAmount()).isEqualByComparingTo("0.00");
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("5000.00");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("re-pricing replaces the old lines rather than appending to them")
    void reApplyingReplaces() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("First", "1.000", "100.00", null)), null);
        calculator.applyLines(invoice, List.of(
                line("Second", "1.000", "200.00", null),
                line("Third", "1.000", "300.00", null)), null);

        assertThat(invoice.getLineItems()).hasSize(2);
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("500.00");
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("a stored line round-trips back into a request without changing the arithmetic")
    void storedLinesRoundTrip() {
        Invoice invoice = blank();
        calculator.applyLines(invoice, List.of(line("Advice", "2.500", "4000.00", "18.000")), null);
        BigDecimal total = invoice.getTotalAmount();

        calculator.applyLines(invoice,
                invoice.getLineItems().stream().map(InvoiceCalculator::asRequest).toList(), null);

        assertThat(invoice.getTotalAmount()).isEqualByComparingTo(total);
        assertTotalIdentityHolds(invoice);
    }

    @Test
    @DisplayName("a very large invoice stays exact and stays bounded")
    void largeInvoices() {
        Invoice invoice = blank();
        List<InvoiceLineItemRequest> many = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(line("Line " + i, "1.000", "99999.99", "18.000"));
        }
        calculator.applyLines(invoice, many, null);

        assertThat(invoice.getSubtotal()).isEqualByComparingTo("19999998.00");
        // 18% of 99999.99 is 17999.9982, which rounds up to 18000.00 on each of the 200
        // lines. Taxing the subtotal instead would give 3599999.64 — the two differ by
        // 0.36, and the per-line figure is the one printed beside each line. This is the
        // documented consequence of rounding per line, asserted rather than assumed.
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("3600000.00");
        assertThat(invoice.getSubtotal().multiply(new BigDecimal("0.18")))
                .as("taxing the subtotal in one go gives a different answer, by design")
                .isEqualByComparingTo("3599999.64");
        assertTotalIdentityHolds(invoice);

        many.add(line("One too many", "1.000", "1.00", null));
        assertThatThrownBy(() -> calculator.applyLines(blank(), many, null))
                .isInstanceOf(ApiException.class).hasMessageContaining("more than 200");
    }
}
