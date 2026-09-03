package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.ReminderChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** The target cannot move: a reminder belongs to the thing it was set on. */
public record UpdateReminderRequest(
        @NotNull Instant remindAt,
        @NotNull ReminderChannel channel,
        @Size(max = 500) String note,
        @Schema(description = "The version you last read from ReminderResponse.version. "
                + "A stale value answers 409 CONCURRENT_MODIFICATION.")
        @NotNull Long version) {
}
