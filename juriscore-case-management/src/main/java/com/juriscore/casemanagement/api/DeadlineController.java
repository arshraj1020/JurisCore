package com.juriscore.casemanagement.api;

import com.juriscore.casemanagement.api.dto.ChangeDeadlineStatusRequest;
import com.juriscore.casemanagement.api.dto.DeadlineRequest;
import com.juriscore.casemanagement.api.dto.DeadlineResponse;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.service.DeadlineService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Dates matters have to meet.
 *
 * <p>Same roles as tasks, and the same one exception: removal belongs to an
 * administrator. Phase 3 records dates and does no arithmetic on them — there is no
 * limitation calculator here.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Deadlines", description = "Dates a matter has to meet")
public class DeadlineController {

    private final DeadlineService deadlineService;

    @GetMapping("/api/v1/cases/{caseId}/deadlines")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List the deadlines on a matter")
    public ApiResponse<PageResponse<DeadlineResponse>> listForCase(
            @PathVariable UUID caseId,
            @RequestParam(required = false) DeadlineStatus status,
            @PageableDefault(size = 20, sort = "dueAt") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                deadlineService.listForCase(caseId, organizationId, status, pageable),
                DeadlineResponse::from));
    }

    @PostMapping("/api/v1/cases/{caseId}/deadlines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Record a deadline on a matter",
            description = "dueAt is taken as given; nothing computes it. Writes a "
                    + "DEADLINE_CREATED entry to the case timeline.")
    public ApiResponse<DeadlineResponse> create(@PathVariable UUID caseId,
                                                @Valid @RequestBody DeadlineRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DeadlineResponse.from(deadlineService.create(caseId, organizationId, request)),
                "Deadline created successfully");
    }

    @GetMapping("/api/v1/deadlines/{deadlineId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one deadline")
    public ApiResponse<DeadlineResponse> byId(@PathVariable UUID deadlineId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DeadlineResponse.from(deadlineService.getScoped(deadlineId, organizationId)));
    }

    @PutMapping("/api/v1/deadlines/{deadlineId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Edit a deadline",
            description = "Status cannot be changed here. Send the version you last read.")
    public ApiResponse<DeadlineResponse> update(@PathVariable UUID deadlineId,
                                                @Valid @RequestBody DeadlineRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DeadlineResponse.from(deadlineService.update(deadlineId, organizationId, request)),
                "Deadline updated successfully");
    }

    @PatchMapping("/api/v1/deadlines/{deadlineId}/status")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Meet or withdraw a deadline",
            description = "OPEN to COMPLETED or CANCELLED, both terminal. Anything else answers "
                    + "409 ILLEGAL_STATE_TRANSITION.")
    public ApiResponse<DeadlineResponse> changeStatus(
            @PathVariable UUID deadlineId,
            @Valid @RequestBody ChangeDeadlineStatusRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(DeadlineResponse.from(
                        deadlineService.changeStatus(deadlineId, organizationId, request.status())),
                "Deadline status updated successfully");
    }

    @DeleteMapping("/api/v1/deadlines/{deadlineId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Remove a deadline",
            description = "Soft removal. To close one out instead, move it to COMPLETED or CANCELLED.")
    public ApiResponse<DeadlineResponse> remove(@PathVariable UUID deadlineId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                DeadlineResponse.from(deadlineService.remove(deadlineId, organizationId)),
                "Deadline removed successfully");
    }
}
