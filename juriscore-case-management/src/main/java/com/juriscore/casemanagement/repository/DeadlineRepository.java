package com.juriscore.casemanagement.repository;

import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeadlineRepository extends JpaRepository<Deadline, UUID> {

    Optional<Deadline> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Deadline> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    Page<Deadline> findByOrganizationIdAndCaseIdAndDeletedAtIsNull(UUID organizationId, UUID caseId,
                                                                    Pageable pageable);

    Page<Deadline> findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNull(UUID organizationId,
                                                                            UUID caseId,
                                                                            DeadlineStatus status,
                                                                            Pageable pageable);
}
