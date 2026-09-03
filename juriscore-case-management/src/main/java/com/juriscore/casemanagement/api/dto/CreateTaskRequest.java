package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.TaskPriority;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** A task is created TODO. The case comes from the path, never the body. */
public record CreateTaskRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 4000) String description,
        @NotNull TaskPriority priority,
        @Schema(description = "An active member of your firm's staff. Null leaves the task "
                + "unassigned, which is allowed — work often exists before anybody has it.")
        UUID assignedToUserId,
        Instant dueAt) {
}
