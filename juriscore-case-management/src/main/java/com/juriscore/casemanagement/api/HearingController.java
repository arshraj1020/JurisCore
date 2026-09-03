package com.juriscore.casemanagement.api;

import com.juriscore.casemanagement.api.dto.ChangeHearingStatusRequest;
import com.juriscore.casemanagement.api.dto.CreateHearingRequest;
import com.juriscore.casemanagement.api.dto.HearingResponse;
import com.juriscore.casemanagement.api.dto.UpdateHearingRequest;
import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.casemanagement.service.HearingService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Listings.
 *
 * <p>Roles mirror cases in casework, because a hearing is the matter in front of a court:
 * all staff read and schedule, only administrators and lawyers record what the bench did.
 * A clerk maintains the listing; deciding it was heard is not maintenance.
 */
@RestController
@RequestMapping("/api/v1/hearings")
@RequiredArgsConstructor
@Tag(name = "Hearings", description = "Listings of matters before courts")
public class HearingController {

    private final HearingService hearingService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List hearings",
            description = "Filter by case, court or status, or give both from and to for a "
                    + "date range — the cause list. A range takes precedence over the other filters.")
    public ApiResponse<PageResponse<HearingResponse>> list(
            @RequestParam(required = false) UUID caseId,
            @RequestParam(required = false) UUID courtId,
            @RequestParam(required = false) HearingStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "scheduledAt") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                hearingService.list(organizationId, caseId, courtId, status, from, to, pageable),
                HearingResponse::from));
    }

    @GetMapping("/{hearingId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one hearing")
    public ApiResponse<HearingResponse> byId(@PathVariable UUID hearingId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(HearingResponse.from(hearingService.getScoped(hearingId, organizationId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List a matter before a court",
            description = "The case and the court must both belong to your firm, and the court "
                    + "must not be retired. Writes a HEARING_SCHEDULED entry to the case timeline.")
    public ApiResponse<HearingResponse> schedule(@Valid @RequestBody CreateHearingRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(HearingResponse.from(hearingService.schedule(organizationId, request)),
                "Hearing scheduled successfully");
    }

    @PutMapping("/{hearingId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Edit a hearing's details",
            description = "Status cannot be changed here. Send the version you last read; a "
                    + "stale one answers 409 CONCURRENT_MODIFICATION.")
    public ApiResponse<HearingResponse> update(@PathVariable UUID hearingId,
                                               @Valid @RequestBody UpdateHearingRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                HearingResponse.from(hearingService.update(hearingId, organizationId, request)),
                "Hearing updated successfully");
    }

    @PatchMapping("/{hearingId}/status")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER')")
    @Operation(summary = "Record what happened",
            description = "SCHEDULED to COMPLETED, ADJOURNED or CANCELLED; ADJOURNED back to "
                    + "SCHEDULED or on to CANCELLED. COMPLETED and CANCELLED are terminal. "
                    + "Anything else answers 409 ILLEGAL_STATE_TRANSITION. Each move writes the "
                    + "matching entry to the case timeline.")
    public ApiResponse<HearingResponse> changeStatus(
            @PathVariable UUID hearingId,
            @Valid @RequestBody ChangeHearingStatusRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(HearingResponse.from(hearingService.changeStatus(
                        hearingId, organizationId, request.status(), request.outcome())),
                "Hearing status updated successfully");
    }
}
