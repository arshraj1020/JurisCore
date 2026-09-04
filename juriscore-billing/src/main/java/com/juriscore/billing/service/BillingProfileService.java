package com.juriscore.billing.service;

import com.juriscore.billing.api.dto.UpdateBillingProfileRequest;
import com.juriscore.billing.domain.BillingProfile;
import com.juriscore.billing.repository.BillingProfileRepository;
import com.juriscore.common.security.TenantGuard;
import com.juriscore.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The firm's own billing details.
 *
 * <p>A firm that has never opened this screen has no row, and reading returns a
 * default-valued profile rather than a 404. That is not a convenience: a 404 would mean
 * every client of this API has to special-case "no profile yet" before it can render a
 * settings form, and every one of them would do it slightly differently.
 *
 * <p>Nothing here stores a payment credential. There is no field for one on the request,
 * no column for one on the table, and no code path that would know what to do with one —
 * JurisCore records that money arrived and never moves any, so it needs no secret.
 */
@Service
@RequiredArgsConstructor
public class BillingProfileService {

    private final BillingProfileRepository profileRepository;

    /** The firm's profile, or an unsaved one carrying the defaults. Never null, never 404. */
    @Transactional(readOnly = true)
    public BillingProfile forOrganization(UUID organizationId) {
        return profileRepository.findByOrganizationId(organizationId)
                .orElseGet(() -> defaults(organizationId));
    }

    /**
     * Creates the row on first save and updates it afterwards.
     *
     * <p>The version check runs only when a row already exists: the first PATCH has
     * nothing to be stale against, so it accepts a null version. Every later one follows
     * the same rule as the rest of the platform.
     */
    @Transactional
    public BillingProfile update(UUID organizationId, UpdateBillingProfileRequest request) {
        BillingProfile profile = profileRepository.findByOrganizationId(organizationId)
                .orElse(null);

        if (profile == null) {
            profile = defaults(organizationId);
        } else {
            TenantGuard.check(profile, ErrorCode.ORGANIZATION_NOT_FOUND);
            OptimisticVersion.require(profile, request.version());
        }

        profile.setLegalName(trim(request.legalName()));
        profile.setTaxRegistration(trim(request.taxRegistration()));
        profile.setBillingEmail(trim(request.billingEmail()));
        profile.setBillingPhone(trim(request.billingPhone()));
        profile.setAddressLine1(trim(request.addressLine1()));
        profile.setAddressLine2(trim(request.addressLine2()));
        profile.setCity(trim(request.city()));
        profile.setState(trim(request.state()));
        profile.setCountry(trim(request.country()));
        profile.setPostalCode(trim(request.postalCode()));
        profile.setInvoiceNotes(trim(request.invoiceNotes()));

        if (request.defaultCurrency() != null && !request.defaultCurrency().isBlank()) {
            profile.setDefaultCurrency(CurrencyCodes.require(request.defaultCurrency()));
        }
        if (request.invoicePrefix() != null && !request.invoicePrefix().isBlank()) {
            profile.setInvoicePrefix(request.invoicePrefix().trim());
        }

        return profileRepository.save(profile);
    }

    private BillingProfile defaults(UUID organizationId) {
        BillingProfile profile = new BillingProfile();
        profile.setOrganizationId(organizationId);
        profile.setDefaultCurrency(CurrencyCodes.DEFAULT);
        profile.setInvoicePrefix("INV");
        return profile;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
