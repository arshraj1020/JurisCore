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

class ReminderStatusPolicyTest {

    @ParameterizedTest(name = "SCHEDULED -> {0} is allowed")
    @CsvSource({"SENT", "CANCELLED"})
    void scheduledLeadsToBothEndings(ReminderStatus to) {
        assertThat(ReminderStatusPolicy.permits(ReminderStatus.SCHEDULED, to)).isTrue();
        assertThatCode(() -> ReminderStatusPolicy.requireTransition(ReminderStatus.SCHEDULED, to))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SENT -> SENT is refused, which is the transition that would double-fire")
    void anAlreadySentReminderCannotFireAgain() {
        assertThat(ReminderStatusPolicy.permits(ReminderStatus.SENT, ReminderStatus.SENT)).isFalse();

        assertThatThrownBy(() -> ReminderStatusPolicy.requireTransition(
                ReminderStatus.SENT, ReminderStatus.SENT))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            "SENT, SCHEDULED", "SENT, CANCELLED",
            "CANCELLED, SCHEDULED", "CANCELLED, SENT", "CANCELLED, CANCELLED",
            "SCHEDULED, SCHEDULED"
    })
    void refusesEverythingElse(ReminderStatus from, ReminderStatus to) {
        assertThat(ReminderStatusPolicy.permits(from, to)).isFalse();
        assertThatThrownBy(() -> ReminderStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("SENT means published, not delivered — nothing in this platform delivers")
    void sentIsTerminal() {
        assertThat(ReminderStatus.SENT.isTerminal()).isTrue();
        assertThat(ReminderStatusPolicy.allowedFrom(ReminderStatus.SENT)).isEmpty();
    }
}
