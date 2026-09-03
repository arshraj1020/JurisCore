package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.Hearing;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.domain.HearingType;

import java.time.Instant;
import java.util.UUID;

public record HearingResponse(
        UUID id,
        UUID caseId,
        UUID courtId,
        HearingType hearingType,
        HearingStatus status,
        Instant scheduledAt,
        Integer durationMinutes,
        String judgeName,
        String courtroom,
        String purpose,
        String outcome,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static HearingResponse from(Hearing hearing) {
        return new HearingResponse(hearing.getId(), hearing.getCaseId(), hearing.getCourtId(),
                hearing.getHearingType(), hearing.getStatus(), hearing.getScheduledAt(),
                hearing.getDurationMinutes(), hearing.getJudgeName(), hearing.getCourtroom(),
                hearing.getPurpose(), hearing.getOutcome(), hearing.getCreatedAt(),
                hearing.getUpdatedAt(), hearing.getVersion());
    }
}
