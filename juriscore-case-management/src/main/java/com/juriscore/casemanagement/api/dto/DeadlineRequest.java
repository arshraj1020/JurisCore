package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.DeadlineType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Create and edit share a shape. {@code dueAt} is taken as given — Phase 3 computes no
 * dates from statute and applies no limitation rules.
 */
public record DeadlineRequest(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 4000) String description,
        @NotNull DeadlineType deadlineType,
        @NotNull Instant dueAt,
        @Schema(description = "Where the date came from — an order, a direction, a rule. "
                + "Free text: nothing here is interpreted.")
        @Size(max = 255) String source,
        @Schema(description = "The version you last read from DeadlineResponse.version. "
                + "Required on update; a stale value answers 409 CONCURRENT_MODIFICATION. "
                + "Ignored when creating.")
        Long version) {
}
