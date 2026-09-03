package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineStatusPolicyTest {

    @ParameterizedTest(name = "OPEN -> {0} is allowed")
    @CsvSource({"COMPLETED", "CANCELLED"})
    void openLeadsToBothEndings(DeadlineStatus to) {
        assertThat(DeadlineStatusPolicy.permits(DeadlineStatus.OPEN, to)).isTrue();
        assertThatCode(() -> DeadlineStatusPolicy.requireTransition(DeadlineStatus.OPEN, to))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            "COMPLETED, OPEN", "COMPLETED, CANCELLED", "COMPLETED, COMPLETED",
            "CANCELLED, OPEN", "CANCELLED, COMPLETED", "CANCELLED, CANCELLED",
            "OPEN, OPEN"
    })
    void refusesEverythingElse(DeadlineStatus from, DeadlineStatus to) {
        assertThat(DeadlineStatusPolicy.permits(from, to)).isFalse();
        assertThatThrownBy(() -> DeadlineStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @Test
    @DisplayName("there is no OVERDUE state — whether a date has passed is the clock's business")
    void thereIsNoOverdueState() {
        assertThat(DeadlineStatus.values())
                .as("storing overdue would mean a row that is wrong between the date passing "
                        + "and some job noticing")
                .containsExactlyInAnyOrder(DeadlineStatus.OPEN, DeadlineStatus.COMPLETED,
                        DeadlineStatus.CANCELLED);
    }
}
