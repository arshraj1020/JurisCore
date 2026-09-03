package com.juriscore.casework.api.dto;

import com.juriscore.casework.domain.CaseAssignment;

import java.time.Instant;
import java.util.UUID;

public record CaseAssignmentResponse(
        UUID id,
        UUID caseId,
        UUID lawyerUserId,
        boolean lead,
        Instant assignedAt,
        UUID assignedBy) {

    public static CaseAssignmentResponse from(CaseAssignment assignment) {
        return new CaseAssignmentResponse(
                assignment.getId(),
                assignment.getCaseId(),
                assignment.getLawyerUserId(),
                assignment.isLead(),
                assignment.getAssignedAt(),
                assignment.getAssignedBy());
    }
}
