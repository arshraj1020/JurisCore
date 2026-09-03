package com.juriscore.casework.repository;

import com.juriscore.casework.domain.CaseAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CaseAssignmentRepository extends JpaRepository<CaseAssignment, UUID> {

    List<CaseAssignment> findByOrganizationIdAndCaseIdOrderByAssignedAtAsc(UUID organizationId, UUID caseId);

    Optional<CaseAssignment> findByOrganizationIdAndCaseIdAndLawyerUserId(UUID organizationId,
                                                                          UUID caseId,
                                                                          UUID lawyerUserId);

    Optional<CaseAssignment> findByOrganizationIdAndCaseIdAndLeadTrue(UUID organizationId, UUID caseId);

    boolean existsByOrganizationIdAndCaseIdAndLawyerUserId(UUID organizationId, UUID caseId, UUID lawyerUserId);

    long countByOrganizationIdAndCaseId(UUID organizationId, UUID caseId);
}
