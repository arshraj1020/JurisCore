package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.HearingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Neither status nor caseId is here, and both omissions are deliberate. Status has its
 * own endpoint so an ordinary edit cannot complete a hearing; the matter cannot move at
 * all, because a hearing that changed cases would leave one matter's timeline describing
 * something that now belongs to another.
 */
public record UpdateHearingRequest(
        @NotNull UUID courtId,
        @NotNull HearingType hearingType,
        @NotNull Instant scheduledAt,
        @Min(1) @Max(1440) Integer durationMinutes,
        @Size(max = 200) String judgeName,
        @Size(max = 120) String courtroom,
        @Size(max = 1000) String purpose,
        @Size(max = 4000) String outcome,
        @Schema(description = "The version you last read from HearingResponse.version. "
                + "A stale value answers 409 CONCURRENT_MODIFICATION.")
        @NotNull Long version) {
}
