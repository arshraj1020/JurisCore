package com.juriscore.billing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The format only. That two concurrent callers never get the same number is a property of
 * {@code SELECT … FOR UPDATE} and a unique index, so it is asserted against a real database
 * in {@code InvoiceNumberIT} rather than here.
 */
class InvoiceNumberGeneratorTest {

    @Test
    void formatsSixDigitsUnderTheFirmsPrefix() {
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 1)).isEqualTo("INV-2026-000001");
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 42)).isEqualTo("INV-2026-000042");
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 999999)).isEqualTo("INV-2026-999999");
    }

    @Test
    @DisplayName("a firm's own prefix is used, so two firms' numbering can look different")
    void honoursTheConfiguredPrefix() {
        assertThat(InvoiceNumberGenerator.format("SHARMA", 2026, 7)).isEqualTo("SHARMA-2026-000007");
        assertThat(InvoiceNumberGenerator.format("BILL-A", 2027, 7)).isEqualTo("BILL-A-2027-000007");
    }

    @Test
    @DisplayName("past a million the number grows rather than wrapping or truncating")
    void doesNotWrap() {
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 1000000)).isEqualTo("INV-2026-1000000");
    }

    @Test
    void sortsChronologicallyAsAString() {
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 9))
                .isLessThan(InvoiceNumberGenerator.format("INV", 2026, 10));
        assertThat(InvoiceNumberGenerator.format("INV", 2026, 999999))
                .isLessThan(InvoiceNumberGenerator.format("INV", 2027, 1));
    }
}
