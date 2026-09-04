package com.juriscore.billing.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The lifecycle, cell by cell. */
class InvoiceStatusPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "DRAFT, ISSUED", "DRAFT, CANCELLED",
            "ISSUED, PARTIALLY_PAID", "ISSUED, PAID", "ISSUED, OVERDUE", "ISSUED, CANCELLED",
            "PARTIALLY_PAID, PAID", "PARTIALLY_PAID, OVERDUE", "PARTIALLY_PAID, CANCELLED",
            "OVERDUE, PARTIALLY_PAID", "OVERDUE, PAID", "OVERDUE, CANCELLED"})
    void allowedMoves(InvoiceStatus from, InvoiceStatus to) {
        assertThat(InvoiceStatusPolicy.permits(from, to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Issuing is one-way; nothing goes back to being a draft.
            "ISSUED, DRAFT", "PAID, DRAFT", "CANCELLED, DRAFT", "OVERDUE, DRAFT",
            // A draft has not been sent, so it cannot be paid or late.
            "DRAFT, PAID", "DRAFT, PARTIALLY_PAID", "DRAFT, OVERDUE",
            // Both endings are terminal. Unwinding either is a credit note, and Phase 5
            // has none — quietly allowing it would let a PATCH rewrite settled history.
            "PAID, ISSUED", "PAID, PARTIALLY_PAID", "PAID, OVERDUE", "PAID, CANCELLED",
            "CANCELLED, ISSUED", "CANCELLED, PAID", "CANCELLED, PARTIALLY_PAID",
            "CANCELLED, OVERDUE",
            // An overdue invoice does not become un-issued.
            "OVERDUE, ISSUED", "PARTIALLY_PAID, ISSUED"})
    void refusedMoves(InvoiceStatus from, InvoiceStatus to) {
        assertThat(InvoiceStatusPolicy.permits(from, to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(InvoiceStatus.class)
    @DisplayName("no status may move to itself — a no-op move is a caller mistake, not a success")
    void selfTransitionsAreRefused(InvoiceStatus status) {
        assertThat(InvoiceStatusPolicy.permits(status, status)).isFalse();
    }

    @Test
    void terminalStatesHaveNoExits() {
        assertThat(InvoiceStatusPolicy.allowedFrom(InvoiceStatus.PAID)).isEmpty();
        assertThat(InvoiceStatusPolicy.allowedFrom(InvoiceStatus.CANCELLED)).isEmpty();
        assertThat(InvoiceStatus.PAID.isTerminal()).isTrue();
        assertThat(InvoiceStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void everyStatusIsInTheTable() {
        for (InvoiceStatus status : InvoiceStatus.values()) {
            assertThat(InvoiceStatusPolicy.allowedFrom(status))
                    .as("%s must appear in the policy, even if only with no exits", status)
                    .isNotNull();
        }
        assertThat(InvoiceStatusPolicy.allowedFrom(InvoiceStatus.DRAFT))
                .isEqualTo(Set.of(InvoiceStatus.ISSUED, InvoiceStatus.CANCELLED));
    }

    @Test
    void nullsAreRefusedRatherThanCrashing() {
        assertThat(InvoiceStatusPolicy.permits(null, InvoiceStatus.PAID)).isFalse();
        assertThat(InvoiceStatusPolicy.permits(InvoiceStatus.DRAFT, null)).isFalse();
    }

    @Test
    @DisplayName("a refused transition is a 409, matching every other lifecycle in the platform")
    void refusedTransitionsAre409() {
        assertThatThrownBy(() -> InvoiceStatusPolicy.requireTransition(
                InvoiceStatus.PAID, InvoiceStatus.ISSUED))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    void statusesKnowWhatTheyPermit() {
        assertThat(InvoiceStatus.DRAFT.isEditable()).isTrue();
        assertThat(InvoiceStatus.ISSUED.isEditable()).isFalse();
        assertThat(InvoiceStatus.DRAFT.acceptsPayment()).isFalse();
        assertThat(InvoiceStatus.ISSUED.acceptsPayment()).isTrue();
        assertThat(InvoiceStatus.PARTIALLY_PAID.acceptsPayment()).isTrue();
        assertThat(InvoiceStatus.OVERDUE.acceptsPayment()).isTrue();
        assertThat(InvoiceStatus.PAID.acceptsPayment()).isFalse();
        assertThat(InvoiceStatus.CANCELLED.acceptsPayment()).isFalse();
    }
}
