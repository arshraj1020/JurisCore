package com.juriscore.casework.api;

import com.juriscore.casework.api.dto.CaseResponse;
import com.juriscore.casework.api.dto.ChangeCaseStatusRequest;
import com.juriscore.casework.api.dto.CreateCaseRequest;
import com.juriscore.casework.api.dto.UpdateCaseRequest;
import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.service.CaseService;
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

import java.util.UUID;

/**
 * Matters.
 *
 * <p>Cases are firm-wide: every member of staff reads every matter the firm holds, and
 * an unassigned lawyer sees the same list as an assigned one. A matter belonging to
 * another firm is reported as not found, never as forbidden.
 */
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
@Tag(name = "Cases", description = "Matters, their details and their lifecycle")
public class CaseController {

    private final CaseService caseService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List your firm's matters")
    public ApiResponse<PageResponse<CaseResponse>> list(
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(required = false) UUID clientId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "openedAt") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                caseService.list(organizationId, status, clientId, search, pageable),
                CaseResponse::from));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one matter")
    public ApiResponse<CaseResponse> byId(@PathVariable UUID caseId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(CaseResponse.from(caseService.getScoped(caseId, organizationId)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Open a matter",
            description = "The case number is issued by the system, unique within the firm. "
                    + "The client must be a live client of the same firm.")
    public ApiResponse<CaseResponse> create(@Valid @RequestBody CreateCaseRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        UUID actorUserId = CurrentUser.requireUserId();
        return ApiResponse.ok(
                CaseResponse.from(caseService.create(organizationId, actorUserId, request)),
                "Case created successfully");
    }

    @PutMapping("/{caseId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Edit a matter's details",
            description = "Send the version you last read. A stale version means somebody "
                    + "else saved in between and answers 409 CONCURRENT_MODIFICATION. Status "
                    + "cannot be changed here.")
    public ApiResponse<CaseResponse> update(@PathVariable UUID caseId,
                                            @Valid @RequestBody UpdateCaseRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                CaseResponse.from(caseService.update(caseId, organizationId, request)),
                "Case updated successfully");
    }

    @PatchMapping("/{caseId}/status")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER')")
    @Operation(summary = "Move a matter through its lifecycle",
            description = "OPEN to IN_PROGRESS, ON_HOLD or CLOSED; IN_PROGRESS to ON_HOLD or "
                    + "CLOSED; ON_HOLD to IN_PROGRESS or CLOSED. CLOSED is terminal. Anything "
                    + "else answers 409 ILLEGAL_STATE_TRANSITION.")
    public ApiResponse<CaseResponse> changeStatus(@PathVariable UUID caseId,
                                                  @Valid @RequestBody ChangeCaseStatusRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        UUID actorUserId = CurrentUser.requireUserId();
        return ApiResponse.ok(
                CaseResponse.from(
                        caseService.changeStatus(caseId, organizationId, actorUserId, request.status())),
                "Case status updated successfully");
    }
}
