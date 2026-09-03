package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.DeadlineStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeDeadlineStatusRequest(@NotNull DeadlineStatus status) {
}
