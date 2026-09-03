package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeTaskStatusRequest(@NotNull TaskStatus status) {
}
