package com.juriscore.billing.api.dto;

import com.juriscore.billing.domain.BillingProfile;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * The firm's billing settings.
 *
 * <p>A firm that has never opened this screen has no row, and the service answers with
 * defaults rather than a 404 — so {@code id} and {@code version} are null until something
 * is saved, and a client sends {@code version: null} on its first PATCH.
 */
@Schema(description = "Billing settings for your firm. Contains no payment credentials, because none are stored.")
public record BillingProfileResponse(
        UUID id,
        String legalName,
        @Schema(description = "Recorded and printed only. No statutory validation or filing is performed.")
        String taxRegistration,
        String billingEmail,
        String billingPhone,
        String addressLine1,
        String addressLine2,
        String city,
        String state,
        String country,
        String postalCode,
        String defaultCurrency,
        String invoicePrefix,
        String invoiceNotes,
        Instant createdAt,
        Instant updatedAt,
        Long version) {

    public static BillingProfileResponse from(BillingProfile profile) {
        return new BillingProfileResponse(
                profile.getId(),
                profile.getLegalName(),
                profile.getTaxRegistration(),
                profile.getBillingEmail(),
                profile.getBillingPhone(),
                profile.getAddressLine1(),
                profile.getAddressLine2(),
                profile.getCity(),
                profile.getState(),
                profile.getCountry(),
                profile.getPostalCode(),
                profile.getDefaultCurrency(),
                profile.getInvoicePrefix(),
                profile.getInvoiceNotes(),
                profile.getCreatedAt(),
                profile.getUpdatedAt(),
                profile.getId() == null ? null : profile.getVersion());
    }
}
