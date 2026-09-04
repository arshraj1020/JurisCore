package com.juriscore.billing.domain;

import com.juriscore.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The entity's own invariants: the timestamps that shadow the status cannot drift from it. */
class InvoiceTest {

    private static Invoice draft() {
        Invoice invoice = new Invoice();
        invoice.setStatus(InvoiceStatus.DRAFT);
        return invoice;
    }

    @Test
    void issuingStampsNeitherTimestamp() {
        Invoice invoice = draft();
        invoice.transitionTo(InvoiceStatus.ISSUED, Instant.now());

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoice.getPaidAt()).isNull();
        assertThat(invoice.getCancelledAt()).isNull();
    }

    @Test
    @DisplayName("paid_at is set exactly when the status is PAID — the database asserts the same pairing")
    void payingStampsPaidAt() {
        Instant when = Instant.parse("2026-03-15T10:00:00Z");
        Invoice invoice = draft();
        invoice.transitionTo(InvoiceStatus.ISSUED, when);
        invoice.transitionTo(InvoiceStatus.PAID, when);

        assertThat(invoice.getPaidAt()).isEqualTo(when);
        assertThat(invoice.getCancelledAt()).isNull();
    }

    @Test
    void cancellingStampsCancelledAt() {
        Instant when = Instant.parse("2026-03-15T10:00:00Z");
        Invoice invoice = draft();
        invoice.transitionTo(InvoiceStatus.CANCELLED, when);

        assertThat(invoice.getCancelledAt()).isEqualTo(when);
        assertThat(invoice.getPaidAt()).isNull();
    }

    @Test
    @DisplayName("moving through PARTIALLY_PAID leaves neither timestamp set")
    void partialPaymentStampsNothing() {
        Invoice invoice = draft();
        invoice.transitionTo(InvoiceStatus.ISSUED, Instant.now());
        invoice.transitionTo(InvoiceStatus.PARTIALLY_PAID, Instant.now());

        assertThat(invoice.getPaidAt()).isNull();
        assertThat(invoice.getCancelledAt()).isNull();
    }

    @Test
    void aRefusedTransitionChangesNothing() {
        Invoice invoice = draft();
        invoice.transitionTo(InvoiceStatus.ISSUED, Instant.now());
        invoice.transitionTo(InvoiceStatus.PAID, Instant.now());
        Instant paidAt = invoice.getPaidAt();

        assertThatThrownBy(() -> invoice.transitionTo(InvoiceStatus.CANCELLED, Instant.now()))
                .isInstanceOf(ApiException.class);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getPaidAt()).isEqualTo(paidAt);
        assertThat(invoice.getCancelledAt()).isNull();
    }

    @Test
    void linesAreAttachedToTheirInvoiceAndReplacedWholesale() {
        Invoice invoice = draft();
        InvoiceLineItem item = new InvoiceLineItem();
        item.setDescription("Drafting");
        invoice.addLineItem(item);

        assertThat(invoice.getLineItems()).containsExactly(item);
        assertThat(item.getInvoice()).isSameAs(invoice);

        invoice.clearLineItems();
        assertThat(invoice.getLineItems()).isEmpty();
    }

    @Test
    @DisplayName("a new invoice starts at zero rather than null, so the total identity always holds")
    void moneyStartsAtZero() {
        Invoice invoice = new Invoice();
        assertThat(invoice.getSubtotal()).isEqualByComparingTo("0.00");
        assertThat(invoice.getTaxAmount()).isEqualByComparingTo("0.00");
        assertThat(invoice.getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0.00");
    }
}
