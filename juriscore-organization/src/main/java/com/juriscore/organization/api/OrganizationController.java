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

    @GetMapping("/current")
    @Operation(summary = "Profile of the firm the caller belongs to")
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
