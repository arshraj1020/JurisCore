package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;

import java.time.Instant;
import java.util.UUID;

public record CaseResponse(
        UUID id,
        String caseNumber,
        String title,
        String description,
        UUID clientId,
        CaseStatus status,
        Instant openedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static CaseResponse from(LegalCase legalCase) {
        return new CaseResponse(
                legalCase.getId(),
                legalCase.getCaseNumber(),
                legalCase.getTitle(),
                legalCase.getDescription(),
                legalCase.getClientId(),
                legalCase.getStatus(),
                legalCase.getOpenedAt(),
                legalCase.getClosedAt(),
                legalCase.getCreatedAt(),
                legalCase.getUpdatedAt(),
                legalCase.getVersion());
    }
}
