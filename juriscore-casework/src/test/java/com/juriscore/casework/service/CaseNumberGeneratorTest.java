package com.juriscore.casework.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the formatting is unit-testable here — the part that matters, the row lock, needs
 * a real database and two real transactions, which is {@code CaseNumberIT}'s job.
 */
class CaseNumberGeneratorTest {

    @Test
    void formatsTheDocumentedShape() {
        assertThat(CaseNumberGenerator.format(2026, 1)).isEqualTo("CASE-2026-000001");
    }

    @Test
    @DisplayName("pads to six digits, and keeps going past a million rather than truncating")
    void padsAndDoesNotTruncate() {
        assertThat(CaseNumberGenerator.format(2026, 42)).isEqualTo("CASE-2026-000042");
        assertThat(CaseNumberGenerator.format(2026, 999_999)).isEqualTo("CASE-2026-999999");
        assertThat(CaseNumberGenerator.format(2026, 1_000_000)).isEqualTo("CASE-2026-1000000");
    }

    @Test
    @DisplayName("numbers sort in issue order as plain strings, within a year")
    void zeroPaddingKeepsLexicalOrderMeaningful() {
        // Without the padding, CASE-2026-10 would sort before CASE-2026-9 in any UI that
        // sorts by case number as text, which is most of them.
        assertThat(CaseNumberGenerator.format(2026, 9))
                .isLessThan(CaseNumberGenerator.format(2026, 10));
    }
}
