package com.juriscore.organization.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.organization.api.dto.OrganizationResponse;
import com.juriscore.organization.api.dto.UpdateOrganizationRequest;
import com.juriscore.organization.service.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizations")
@RequiredArgsConstructor
@Tag(name = "Organizations", description = "Law firm profile and tenant settings")
public class OrganizationController {

    private final OrganizationService organizationService;

    /**
     * The caller's own firm.
     *
     * <p>The tenant comes from {@link CurrentUser#requireOrganizationId()} — that is, from
     * the access token — and there is deliberately no path variable, query parameter or
     * header that could name a different one. Reading another firm's profile is a separate
     * endpoint below, and it requires SUPER_ADMIN.
     *
     * <p>The {@code @PreAuthorize} is not redundant even though the security chain already
     * requires authentication for everything unmatched. It states the rule where the
     * handler is read rather than in a list of path patterns two modules away, and it
     * narrows the endpoint to firm staff, which is the only audience the product has for
     * it: a CLIENT is an external party who sees what has been shared with them, not the
     * firm's registration number and billing address. A SUPER_ADMIN is refused here too,
     * for a different reason — they belong to no firm, so "the caller's own firm" is not a
     * question that has an answer, and {@code requireOrganizationId()} would throw.
     */
    @GetMapping("/current")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Profile of the firm the caller belongs to (firm staff only)")
    public ApiResponse<OrganizationResponse> current() {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(OrganizationResponse.from(organizationService.getById(organizationId)));
    }

    @PutMapping("/current")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Update the firm profile (firm administrators only)")
    public ApiResponse<OrganizationResponse> updateCurrent(@Valid @RequestBody UpdateOrganizationRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                OrganizationResponse.from(organizationService.update(organizationId, request)),
                "Organization updated successfully");
    }

    @GetMapping("/{organizationId}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Look up any firm (platform administrators only)")
    public ApiResponse<OrganizationResponse> byId(@PathVariable UUID organizationId) {
        return ApiResponse.ok(OrganizationResponse.from(organizationService.getById(organizationId)));
    }
}
