package com.juriscore.casework.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole lifecycle, cell by cell.
 *
 * <p>Written as a matrix rather than as a handful of happy paths because the interesting
 * half of a state machine is the moves it refuses, and those are the ones nobody
 * remembers to test one at a time.
 */
class CaseStatusPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "OPEN,        IN_PROGRESS",
            "OPEN,        ON_HOLD",
            "OPEN,        CLOSED",
            "IN_PROGRESS, ON_HOLD",
            "IN_PROGRESS, CLOSED",
            "ON_HOLD,     IN_PROGRESS",
            "ON_HOLD,     CLOSED"
    })
    void permitsTheSevenLegalMoves(CaseStatus from, CaseStatus to) {
        assertThat(CaseStatusPolicy.permits(from, to)).isTrue();
        assertThatCode(() -> CaseStatusPolicy.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Backwards, which no state allows.
            "IN_PROGRESS, OPEN",
            "ON_HOLD,     OPEN",
            // CLOSED is terminal: a matter is not reopened in Phase 2.
            "CLOSED,      OPEN",
            "CLOSED,      IN_PROGRESS",
            "CLOSED,      ON_HOLD",
            // A move to the status the case already holds is a caller mistake, not a
            // silent success — otherwise a double-submitted "close" reads as fine.
            "OPEN,        OPEN",
            "IN_PROGRESS, IN_PROGRESS",
            "ON_HOLD,     ON_HOLD",
            "CLOSED,      CLOSED"
    })
    void refusesEverythingElse(CaseStatus from, CaseStatus to) {
        assertThat(CaseStatusPolicy.permits(from, to)).isFalse();

        assertThatThrownBy(() -> CaseStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("the refusal is a 409, not a 400 — the request was well formed, the state was not")
    void refusalIsAConflict() {
        assertThat(ErrorCode.ILLEGAL_STATE_TRANSITION.status().value()).isEqualTo(409);
    }

    @Test
    void closedIsTerminal() {
        assertThat(CaseStatusPolicy.allowedFrom(CaseStatus.CLOSED)).isEmpty();
        assertThat(CaseStatus.CLOSED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(CaseStatus.class)
    @DisplayName("every status has an entry, so a new one cannot be added and silently forgotten")
    void everyStatusIsCoveredByThePolicy(CaseStatus status) {
        Set<CaseStatus> allowed = CaseStatusPolicy.allowedFrom(status);

        assertThat(allowed).isNotNull();
        assertThat(allowed).doesNotContain(status);
        if (status != CaseStatus.CLOSED) {
            assertThat(allowed)
                    .as("%s is not terminal, so it must lead somewhere", status)
                    .isNotEmpty();
        }
    }

    @Test
    void refusesNulls() {
        assertThat(CaseStatusPolicy.permits(null, CaseStatus.CLOSED)).isFalse();
        assertThat(CaseStatusPolicy.permits(CaseStatus.OPEN, null)).isFalse();
    }
}
