package com.juriscore.casemanagement.repository;

import com.juriscore.casemanagement.domain.Court;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries {@code organizationId}. There is deliberately no lookup that does
 * not — the same rule the casework repositories follow, for the same reason.
 */
public interface CourtRepository extends JpaRepository<Court, UUID> {

    /** Includes retired courts: an old hearing has to keep resolving to a name. */
    Optional<Court> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The "still usable" lookup — for edits, retirement, and new hearings. */
    Optional<Court> findByIdAndOrganizationIdAndActiveTrue(UUID id, UUID organizationId);

    Page<Court> findByOrganizationIdAndActiveTrue(UUID organizationId, Pageable pageable);

    Page<Court> findByOrganizationId(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndActiveTrue(UUID organizationId, String name);
}
