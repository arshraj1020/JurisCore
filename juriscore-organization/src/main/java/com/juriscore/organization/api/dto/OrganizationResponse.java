package com.juriscore.organization.api.dto;

import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.domain.OrganizationStatus;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String slug,
        OrganizationStatus status,
        String contactEmail,
        String contactPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String country,
        String postalCode,
        String timezone,
        String registrationNumber,
        Instant createdAt) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getSlug(),
                organization.getStatus(),
                organization.getContactEmail(),
                organization.getContactPhone(),
                organization.getAddressLine1(),
                organization.getAddressLine2(),
                organization.getCity(),
                organization.getState(),
                organization.getCountry(),
                organization.getPostalCode(),
                organization.getTimezone(),
                organization.getRegistrationNumber(),
                organization.getCreatedAt());
    }
}
