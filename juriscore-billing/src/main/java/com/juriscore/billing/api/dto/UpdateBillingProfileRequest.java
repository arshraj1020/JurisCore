package com.juriscore.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Edit the firm's billing settings. There is no field for a payment credential.")
public record UpdateBillingProfileRequest(

        @Schema(description = "The version you last read, or null the first time.")
        Long version,

        @Size(max = 200) String legalName,
        @Size(max = 64) String taxRegistration,
        @Email @Size(max = 255) String billingEmail,
        @Size(max = 40) String billingPhone,
        @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @Size(max = 120) String city,
        @Size(max = 120) String state,
        @Size(max = 120) String country,
        @Size(max = 20) String postalCode,

        @Size(min = 3, max = 3)
        @Schema(description = "ISO 4217. Applies to new invoices only; existing ones keep theirs.")
        String defaultCurrency,

        @Pattern(regexp = "^[A-Z][A-Z0-9-]{0,11}$",
                message = "must start with a letter and contain only capitals, digits and hyphens")
        @Schema(description = "The INV in INV-2026-000001. Changing it does not renumber anything.",
                example = "INV")
        String invoicePrefix,

        @Size(max = 2000)
        @Schema(description = "Boilerplate copied onto new invoices — payment terms, for instance.")
        String invoiceNotes) {
}
