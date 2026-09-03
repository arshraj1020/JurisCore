package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.CaseEvent;
import com.juriscore.casework.domain.CaseEventType;

import java.time.Instant;
import java.util.UUID;

public record CaseEventResponse(
        UUID id,
        UUID caseId,
        CaseEventType eventType,
        UUID actorUserId,
        Instant occurredAt,
        String summary) {

    public static CaseEventResponse from(CaseEvent event) {
        return new CaseEventResponse(
                event.getId(),
                event.getCaseId(),
                event.getEventType(),
                event.getActorUserId(),
                event.getOccurredAt(),
                event.getSummary());
    }
}
