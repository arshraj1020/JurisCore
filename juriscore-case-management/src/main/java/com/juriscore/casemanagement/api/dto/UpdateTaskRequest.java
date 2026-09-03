package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.TaskPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Status is absent so a routine edit cannot quietly complete somebody's work. */
public record UpdateTaskRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 4000) String description,
        @NotNull TaskPriority priority,
        UUID assignedToUserId,
        Instant dueAt,
        @Schema(description = "The version you last read from TaskResponse.version. "
                + "A stale value answers 409 CONCURRENT_MODIFICATION.")
        @NotNull Long version) {
}
