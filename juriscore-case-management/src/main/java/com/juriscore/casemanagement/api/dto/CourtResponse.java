package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.Court;
import com.juriscore.casemanagement.domain.CourtType;

import java.time.Instant;
import java.util.UUID;

public record CourtResponse(
        UUID id,
        String name,
        CourtType courtType,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String country,
        String timezone,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static CourtResponse from(Court court) {
        return new CourtResponse(court.getId(), court.getName(), court.getCourtType(),
                court.getAddressLine1(), court.getAddressLine2(), court.getCity(), court.getState(),
                court.getCountry(), court.getTimezone(), court.isActive(), court.getCreatedAt(),
                court.getUpdatedAt(), court.getVersion());
    }
}
