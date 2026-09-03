package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.HearingType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Status is absent: a hearing is created SCHEDULED, and moves only through its own endpoint. */
public record CreateHearingRequest(
        @NotNull UUID caseId,
        @NotNull UUID courtId,
        @NotNull HearingType hearingType,
        @NotNull Instant scheduledAt,
        @Min(1) @Max(1440) Integer durationMinutes,
        @Size(max = 200) String judgeName,
        @Size(max = 120) String courtroom,
        @Size(max = 1000) String purpose) {
}
