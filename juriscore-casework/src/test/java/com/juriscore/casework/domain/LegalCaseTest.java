package com.juriscore.casework.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition rules live on the entity, so they are tested on the entity: a service
 * that forgets to ask the policy is a bug the service's own tests would not catch.
 */
class LegalCaseTest {

    private LegalCase legalCase;

    @BeforeEach
    void openACase() {
        legalCase = new LegalCase();
        legalCase.setOrganizationId(UUID.randomUUID());
        legalCase.setCaseNumber("CASE-2026-000001");
        legalCase.setTitle("Menon v. Iyer");
        legalCase.setClientId(UUID.randomUUID());
        legalCase.setStatus(CaseStatus.OPEN);
        legalCase.setOpenedAt(Instant.now());
    }

    @Test
    void movingToClosedStampsTheClosingTime() {
        Instant when = Instant.parse("2026-09-01T10:15:30Z");

        legalCase.transitionTo(CaseStatus.CLOSED, when);

        assertThat(legalCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(legalCase.getClosedAt()).isEqualTo(when);
        assertThat(legalCase.isClosed()).isTrue();
    }

    @Test
    @DisplayName("a move that is not a closure leaves no closing time behind")
    void nonClosingMovesLeaveClosedAtNull() {
        legalCase.transitionTo(CaseStatus.IN_PROGRESS, Instant.now());

        assertThat(legalCase.getClosedAt())
                .as("ck_cases_closed_at refuses a row where status and closed_at disagree")
                .isNull();
    }

    @Test
    void aRefusedTransitionChangesNothing() {
        legalCase.transitionTo(CaseStatus.CLOSED, Instant.now());
        Instant closedAt = legalCase.getClosedAt();

        assertThatThrownBy(() -> legalCase.transitionTo(CaseStatus.IN_PROGRESS, Instant.now()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);

        assertThat(legalCase.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(legalCase.getClosedAt())
                .as("the failed reopen must not have cleared the closing time on its way out")
                .isEqualTo(closedAt);
    }
}
