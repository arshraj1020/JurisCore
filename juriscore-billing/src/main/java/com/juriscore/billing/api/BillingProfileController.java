package com.juriscore.billing.api;

import com.juriscore.billing.api.dto.BillingProfileResponse;
import com.juriscore.billing.api.dto.UpdateBillingProfileRequest;
import com.juriscore.billing.service.BillingProfileService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The firm's billing settings.
 *
 * <p>{@code FIRM_ADMIN} only, on both verbs. Reading is restricted as well as writing
 * because this is the firm's own commercial identity — its legal name, tax registration
 * and billing address — and there is no reason a clerk or a fee earner needs it to do
 * their work. {@code Role} already describes billing configuration as the firm
 * administrator's, and this is that sentence enforced.
 *
 * <p>{@code SUPER_ADMIN} is refused by {@code requireOrganizationId()} before any handler
 * body runs: it has no organization of its own, and a platform role must not read a firm's
 * commercial details by virtue of being a platform role.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Billing settings", description = "A firm's own invoicing details")
public class BillingProfileController {

    private final BillingProfileService billingProfileService;

    @GetMapping("/api/v1/billing/profile")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Your firm's billing settings",
            description = "A firm that has never saved these gets the defaults, with a null "
                    + "id and version, rather than a 404.")
    public ApiResponse<BillingProfileResponse> current() {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(BillingProfileResponse.from(
                billingProfileService.forOrganization(organizationId)));
    }

    @PatchMapping("/api/v1/billing/profile")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Edit your firm's billing settings",
            description = "Send version null the first time and the version you last read "
                    + "afterwards. Changing the currency or prefix affects new invoices only.")
    public ApiResponse<BillingProfileResponse> update(
            @Valid @RequestBody UpdateBillingProfileRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                BillingProfileResponse.from(billingProfileService.update(organizationId, request)),
                "Billing settings updated successfully");
    }
}
