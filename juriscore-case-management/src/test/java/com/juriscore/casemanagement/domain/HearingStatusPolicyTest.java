package com.juriscore.casemanagement.domain;

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
 * The hearing lifecycle, cell by cell.
 *
 * <p>The interesting half of a state machine is the moves it refuses, and those are the
 * ones nobody remembers to test one at a time.
 */
class HearingStatusPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "SCHEDULED, COMPLETED",
            "SCHEDULED, ADJOURNED",
            "SCHEDULED, CANCELLED",
            "ADJOURNED, SCHEDULED",
            "ADJOURNED, CANCELLED"
    })
    void permitsTheFiveLegalMoves(HearingStatus from, HearingStatus to) {
        assertThat(HearingStatusPolicy.permits(from, to)).isTrue();
        assertThatCode(() -> HearingStatusPolicy.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            // Both endings are terminal.
            "COMPLETED, SCHEDULED", "COMPLETED, ADJOURNED", "COMPLETED, CANCELLED", "COMPLETED, COMPLETED",
            "CANCELLED, SCHEDULED", "CANCELLED, ADJOURNED", "CANCELLED, COMPLETED", "CANCELLED, CANCELLED",
            // A hearing that has not happened cannot be adjourned into having happened.
            "ADJOURNED, COMPLETED",
            // A no-op move is a caller mistake, not a silent success.
            "SCHEDULED, SCHEDULED", "ADJOURNED, ADJOURNED"
    })
    void refusesEverythingElse(HearingStatus from, HearingStatus to) {
        assertThat(HearingStatusPolicy.permits(from, to)).isFalse();

        assertThatThrownBy(() -> HearingStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("adjourned is not an ending — a hearing put off can be relisted")
    void adjournedLeadsBackToScheduled() {
        assertThat(HearingStatusPolicy.allowedFrom(HearingStatus.ADJOURNED))
                .contains(HearingStatus.SCHEDULED);
        assertThat(HearingStatus.ADJOURNED.isTerminal()).isFalse();
    }

    @Test
    void bothEndingsAreTerminal() {
        assertThat(HearingStatusPolicy.allowedFrom(HearingStatus.COMPLETED)).isEmpty();
        assertThat(HearingStatusPolicy.allowedFrom(HearingStatus.CANCELLED)).isEmpty();
        assertThat(HearingStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(HearingStatus.CANCELLED.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(HearingStatus.class)
    @DisplayName("every status has an entry, so a new one cannot be added and forgotten")
    void everyStatusIsCovered(HearingStatus status) {
        Set<HearingStatus> allowed = HearingStatusPolicy.allowedFrom(status);
        assertThat(allowed).isNotNull().doesNotContain(status);
        if (!status.isTerminal()) {
            assertThat(allowed).as("%s is not terminal, so it must lead somewhere", status).isNotEmpty();
        }
    }

    @Test
    void refusesNulls() {
        assertThat(HearingStatusPolicy.permits(null, HearingStatus.COMPLETED)).isFalse();
        assertThat(HearingStatusPolicy.permits(HearingStatus.SCHEDULED, null)).isFalse();
    }

    @Test
    @DisplayName("the refusal is a 409 — the request was well formed, the state was not")
    void refusalIsAConflict() {
        assertThat(ErrorCode.ILLEGAL_STATE_TRANSITION.status().value()).isEqualTo(409);
    }
}
