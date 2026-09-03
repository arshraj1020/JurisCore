package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.ReminderChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** The target comes from the path, so a reminder can never be pointed at two things. */
public record CreateReminderRequest(
        @NotNull Instant remindAt,
        @Schema(description = "How the firm intends this to reach somebody. Recorded and "
                + "published on the domain event; nothing in this release delivers anything.")
        @NotNull ReminderChannel channel,
        @Size(max = 500) String note) {
}
