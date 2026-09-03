package com.juriscore.casework.service;

import com.juriscore.casework.api.dto.AssignLawyerRequest;
import com.juriscore.casework.domain.CaseAssignment;
import com.juriscore.casework.domain.CaseEventType;
import com.juriscore.casework.domain.LegalCase;
import com.juriscore.casework.event.CaseLawyerAssignedEvent;
import com.juriscore.casework.event.CaseLawyerUnassignedEvent;
import com.juriscore.casework.repository.CaseAssignmentRepository;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Staffing a matter.
 *
 * <h2>The lead invariant</h2>
 *
 * <p>A matter with lawyers on it has exactly one lead. Two halves, enforced in two
 * different places on purpose:
 *
 * <ul>
 *   <li><strong>At most one</strong> is {@code uk_case_assignments_lead}, a partial
 *       unique index. Even two administrators promoting different lawyers at the same
 *       instant cannot both win.</li>
 *   <li><strong>At least one</strong> is this class, which refuses to unassign the lead
 *       unless another assignee is promoted in the same transaction.</li>
 * </ul>
 *
 * <p>Where a promotion and a demotion both happen, they are flushed in order —
 * demote first — because the unique index is checked per statement. Two dirty entities
 * flushed together in Hibernate's own order would be a coin toss.
 *
 * <p>Assignments do not touch the case row, so staffing a matter never makes a colleague
 * editing its title lose their work to a 409.
 */
@Service
@RequiredArgsConstructor
public class CaseAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(CaseAssignmentService.class);

    private final CaseAccess caseAccess;
    private final CaseAssignmentRepository assignmentRepository;
    private final LawyerDirectory lawyerDirectory;
    private final CaseTimelineService timeline;
    private final EventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<CaseAssignment> list(UUID caseId, UUID organizationId) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        return assignmentRepository.findByOrganizationIdAndCaseIdOrderByAssignedAtAsc(
                organizationId, legalCase.getId());
    }

    @Transactional
    public CaseAssignment assign(UUID caseId, UUID organizationId, UUID actorUserId,
                                 AssignLawyerRequest request) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);
        UUID lawyerUserId = request.lawyerUserId();

        // Answers 404 for a user in another firm, 400 for one here who is not an active
        // lawyer. Asked before anything is written, so a rejected assignment leaves no trace.
        lawyerDirectory.requireAssignableLawyer(lawyerUserId, organizationId);

        if (assignmentRepository.existsByOrganizationIdAndCaseIdAndLawyerUserId(
                organizationId, legalCase.getId(), lawyerUserId)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "That lawyer is already assigned to this case");
        }

        boolean firstAssignment =
                assignmentRepository.countByOrganizationIdAndCaseId(organizationId, legalCase.getId()) == 0;
        boolean shouldLead = firstAssignment || request.leadRequested();

        if (shouldLead && !firstAssignment) {
            demoteCurrentLead(organizationId, legalCase.getId());
        }

        CaseAssignment assignment = new CaseAssignment();
        assignment.setOrganizationId(organizationId);
        assignment.setCaseId(legalCase.getId());
        assignment.setLawyerUserId(lawyerUserId);
        assignment.setLead(shouldLead);
        assignment.setAssignedAt(Instant.now());
        assignment.setAssignedBy(actorUserId);
        CaseAssignment saved = assignmentRepository.saveAndFlush(assignment);

        timeline.append(legalCase, CaseEventType.LAWYER_ASSIGNED, actorUserId,
                "Lawyer " + lawyerUserId + " assigned" + (shouldLead ? " as lead" : ""));

        log.info("Lawyer {} assigned to case {}{}", lawyerUserId, legalCase.getId(),
                shouldLead ? " as lead" : "");
        eventPublisher.publish(new CaseLawyerAssignedEvent(organizationId, legalCase.getId(),
                legalCase.getCaseNumber(), lawyerUserId, shouldLead));
        return saved;
    }

    /**
     * Takes a lawyer off a matter.
     *
     * @param newLeadUserId another lawyer already assigned to this matter, to take the
     *                      lead. Required when removing the current lead, ignored
     *                      otherwise. Removing the only lawyer is therefore refused —
     *                      there is nobody to promote, and a staffed matter with no lead
     *                      is the state this rule exists to prevent.
     */
    @Transactional
    public void unassign(UUID caseId, UUID organizationId, UUID actorUserId,
                         UUID lawyerUserId, UUID newLeadUserId) {
        LegalCase legalCase = caseAccess.require(caseId, organizationId);

        CaseAssignment assignment = assignmentRepository
                .findByOrganizationIdAndCaseIdAndLawyerUserId(organizationId, legalCase.getId(), lawyerUserId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That lawyer is not assigned to this case"));

        UUID promoted = null;
        if (assignment.isLead()) {
            promoted = promotionFor(organizationId, legalCase.getId(), lawyerUserId, newLeadUserId);
        }

        assignmentRepository.delete(assignment);
        // Forces the DELETE out before the promotion's UPDATE. Both touch
        // uk_case_assignments_lead, which is checked per statement.
        assignmentRepository.flush();

        if (promoted != null) {
            CaseAssignment successor = assignmentRepository
                    .findByOrganizationIdAndCaseIdAndLawyerUserId(organizationId, legalCase.getId(), promoted)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_ARGUMENT,
                            "The lawyer being promoted to lead is not assigned to this case"));
            successor.setLead(true);
            assignmentRepository.saveAndFlush(successor);
        }

        timeline.append(legalCase, CaseEventType.LAWYER_UNASSIGNED, actorUserId,
                "Lawyer " + lawyerUserId + " unassigned"
                        + (promoted != null ? "; lawyer " + promoted + " promoted to lead" : ""));

        log.info("Lawyer {} unassigned from case {}", lawyerUserId, legalCase.getId());
        eventPublisher.publish(new CaseLawyerUnassignedEvent(organizationId, legalCase.getId(),
                legalCase.getCaseNumber(), lawyerUserId, promoted));
    }

    /** Validates the caller's replacement before anything is deleted. */
    private UUID promotionFor(UUID organizationId, UUID caseId, UUID leavingUserId, UUID newLeadUserId) {
        if (newLeadUserId == null) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "The lead lawyer cannot be unassigned. Promote another assigned lawyer to "
                            + "lead in the same request, using newLeadUserId.");
        }
        if (newLeadUserId.equals(leavingUserId)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "The lawyer being unassigned cannot also be the new lead");
        }
        if (!assignmentRepository.existsByOrganizationIdAndCaseIdAndLawyerUserId(
                organizationId, caseId, newLeadUserId)) {
            throw new ApiException(ErrorCode.INVALID_ARGUMENT,
                    "The new lead must already be assigned to this case");
        }
        return newLeadUserId;
    }

    private void demoteCurrentLead(UUID organizationId, UUID caseId) {
        Optional<CaseAssignment> currentLead =
                assignmentRepository.findByOrganizationIdAndCaseIdAndLeadTrue(organizationId, caseId);
        currentLead.ifPresent(lead -> {
            lead.setLead(false);
            assignmentRepository.saveAndFlush(lead);
        });
    }
}
