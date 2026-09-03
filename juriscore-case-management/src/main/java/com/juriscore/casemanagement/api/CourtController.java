package com.juriscore.casemanagement.api;

import com.juriscore.casemanagement.api.dto.CourtRequest;
import com.juriscore.casemanagement.api.dto.CourtResponse;
import com.juriscore.casemanagement.service.CourtService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The firm's list of courts.
 *
 * <p>Reference data, so the roles match the client book in casework: all staff read it,
 * administrators and clerks maintain it, only an administrator retires an entry.
 * {@code SUPER_ADMIN} and {@code CLIENT} appear in no list and are refused.
 *
 * <p>No endpoint takes an organization id. It comes from the access token, every time.
 */
@RestController
@RequestMapping("/api/v1/courts")
@RequiredArgsConstructor
@Tag(name = "Courts", description = "Benches the firm appears before")
public class CourtController {

    private final CourtService courtService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List your firm's courts",
            description = "Retired courts are excluded unless includeRetired is true.")
    public ApiResponse<PageResponse<CourtResponse>> list(
            @RequestParam(defaultValue = "false") boolean includeRetired,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                courtService.list(organizationId, includeRetired, pageable), CourtResponse::from));
    }

    @GetMapping("/{courtId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one court",
            description = "Returns retired courts too, so an older hearing still resolves to a name.")
    public ApiResponse<CourtResponse> byId(@PathVariable UUID courtId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(CourtResponse.from(courtService.getScoped(courtId, organizationId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Add a court")
    public ApiResponse<CourtResponse> create(@Valid @RequestBody CourtRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(CourtResponse.from(courtService.create(organizationId, request)),
                "Court created successfully");
    }

    @PutMapping("/{courtId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'CLERK')")
    @Operation(summary = "Update a court",
            description = "Send the version you last read; a stale one answers 409.")
    public ApiResponse<CourtResponse> update(@PathVariable UUID courtId,
                                             @Valid @RequestBody CourtRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                CourtResponse.from(courtService.update(courtId, organizationId, request)),
                "Court updated successfully");
    }

    @DeleteMapping("/{courtId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Retire a court",
            description = "Deactivation, not deletion: hearings held before it keep resolving. "
                    + "Refused while the court still has scheduled hearings.")
    public ApiResponse<CourtResponse> retire(@PathVariable UUID courtId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(CourtResponse.from(courtService.retire(courtId, organizationId)),
                "Court retired successfully");
    }
}
