package com.juriscore.organization.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Firm profile edit. The slug is deliberately absent: handles are immutable. */
public record UpdateOrganizationRequest(
        @NotBlank @Size(max = 200) String name,
        @Email @Size(max = 255) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Size(max = 120) String country,
        @Size(max = 20) String postalCode,
        @Size(max = 64) String timezone,
        @Size(max = 120) String registrationNumber) {
}
