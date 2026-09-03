package com.juriscore.casemanagement.repository;

import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    /** Includes soft-deleted rows, so an old timeline entry still resolves. */
    Optional<Task> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Task> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    Page<Task> findByOrganizationIdAndCaseIdAndDeletedAtIsNull(UUID organizationId, UUID caseId,
                                                                Pageable pageable);

    Page<Task> findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNull(UUID organizationId,
                                                                        UUID caseId,
                                                                        TaskStatus status,
                                                                        Pageable pageable);

    Page<Task> findByOrganizationIdAndAssignedToUserIdAndDeletedAtIsNull(UUID organizationId,
                                                                         UUID assignedToUserId,
                                                                         Pageable pageable);
}
