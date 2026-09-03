package com.juriscore.casework.api;

import com.juriscore.casework.api.dto.AssignLawyerRequest;
import com.juriscore.casework.api.dto.CaseAssignmentResponse;
import com.juriscore.casework.service.CaseAssignmentService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Who is working a matter.
 *
 * <p>Staffing is a firm-administration decision: only {@code FIRM_ADMIN} may write here.
 * A lawyer cannot put themselves or a colleague on a case, and a clerk — who may
 * maintain case data — may not either.
 */
@RestController
@RequestMapping("/api/v1/cases/{caseId}/assignments")
@RequiredArgsConstructor
@Tag(name = "Case assignments", description = "Lawyers staffed to a matter, and which of them leads")
public class CaseAssignmentController {

    private final CaseAssignmentService assignmentService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List the lawyers on a matter")
    public ApiResponse<List<CaseAssignmentResponse>> list(@PathVariable UUID caseId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(assignmentService.list(caseId, organizationId).stream()
                .map(CaseAssignmentResponse::from)
                .toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Assign a lawyer to a matter",
            description = "The lawyer must be an active LAWYER in your firm. The first lawyer "
                    + "assigned becomes lead; pass lead=true to move the lead to somebody else.")
    public ApiResponse<CaseAssignmentResponse> assign(@PathVariable UUID caseId,
                                                      @Valid @RequestBody AssignLawyerRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        UUID actorUserId = CurrentUser.requireUserId();
        return ApiResponse.ok(CaseAssignmentResponse.from(
                        assignmentService.assign(caseId, organizationId, actorUserId, request)),
                "Lawyer assigned successfully");
    }

    @DeleteMapping("/{lawyerUserId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Take a lawyer off a matter",
            description = "Removing the lead requires newLeadUserId naming another lawyer "
                    + "already assigned to the matter, who takes the lead in the same "
                    + "transaction. A staffed matter is never left without a lead.")
    public ApiResponse<Void> unassign(@PathVariable UUID caseId,
                                      @PathVariable UUID lawyerUserId,
                                      @RequestParam(required = false) UUID newLeadUserId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        UUID actorUserId = CurrentUser.requireUserId();
        assignmentService.unassign(caseId, organizationId, actorUserId, lawyerUserId, newLeadUserId);
        return ApiResponse.message("Lawyer unassigned successfully");
    }
}
