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

class TaskStatusPolicyTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "TODO,        IN_PROGRESS",
            "TODO,        COMPLETED",
            "TODO,        CANCELLED",
            "IN_PROGRESS, TODO",
            "IN_PROGRESS, COMPLETED",
            "IN_PROGRESS, CANCELLED"
    })
    void permitsTheSixLegalMoves(TaskStatus from, TaskStatus to) {
        assertThat(TaskStatusPolicy.permits(from, to)).isTrue();
        assertThatCode(() -> TaskStatusPolicy.requireTransition(from, to)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("work can be put back down: IN_PROGRESS returns to TODO")
    void workCanBePutBackDown() {
        assertThat(TaskStatusPolicy.permits(TaskStatus.IN_PROGRESS, TaskStatus.TODO)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is refused")
    @CsvSource({
            "COMPLETED, TODO", "COMPLETED, IN_PROGRESS", "COMPLETED, CANCELLED", "COMPLETED, COMPLETED",
            "CANCELLED, TODO", "CANCELLED, IN_PROGRESS", "CANCELLED, COMPLETED", "CANCELLED, CANCELLED",
            "TODO, TODO", "IN_PROGRESS, IN_PROGRESS"
    })
    void refusesEverythingElse(TaskStatus from, TaskStatus to) {
        assertThat(TaskStatusPolicy.permits(from, to)).isFalse();
        assertThatThrownBy(() -> TaskStatusPolicy.requireTransition(from, to))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.ILLEGAL_STATE_TRANSITION);
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void everyStatusIsCovered(TaskStatus status) {
        Set<TaskStatus> allowed = TaskStatusPolicy.allowedFrom(status);
        assertThat(allowed).isNotNull().doesNotContain(status);
        if (!status.isTerminal()) {
            assertThat(allowed).isNotEmpty();
        } else {
            assertThat(allowed).isEmpty();
        }
    }

    @Test
    void refusesNulls() {
        assertThat(TaskStatusPolicy.permits(null, TaskStatus.COMPLETED)).isFalse();
        assertThat(TaskStatusPolicy.permits(TaskStatus.TODO, null)).isFalse();
    }
}
