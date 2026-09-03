package com.juriscore.casemanagement.repository;

import com.juriscore.casemanagement.domain.Hearing;
import com.juriscore.casemanagement.domain.HearingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The filter combinations are separate derived methods rather than one query with
 * optional parameters, following {@code CaseRepository}: an HQL {@code :status is null or
 * h.status = :status} leaves Hibernate to infer an enum's type from a null.
 *
 * <p>The date-range query is the exception and takes both bounds as non-null, which the
 * service guarantees before calling it.
 */
public interface HearingRepository extends JpaRepository<Hearing, UUID> {

    Optional<Hearing> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Page<Hearing> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<Hearing> findByOrganizationIdAndCaseId(UUID organizationId, UUID caseId, Pageable pageable);

    Page<Hearing> findByOrganizationIdAndCourtId(UUID organizationId, UUID courtId, Pageable pageable);

    Page<Hearing> findByOrganizationIdAndStatus(UUID organizationId, HearingStatus status,
                                                 Pageable pageable);

    Page<Hearing> findByOrganizationIdAndCaseIdAndStatus(UUID organizationId, UUID caseId,
                                                         HearingStatus status, Pageable pageable);

    @Query("""
            select h from Hearing h
            where h.organizationId = :organizationId
              and h.scheduledAt >= :from
              and h.scheduledAt < :to
            """)
    Page<Hearing> findScheduledBetween(@Param("organizationId") UUID organizationId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to,
                                       Pageable pageable);

    /** Backs the rule that a court in use cannot be retired out from under a listing. */
    boolean existsByOrganizationIdAndCourtIdAndStatus(UUID organizationId, UUID courtId,
                                                       HearingStatus status);

    long countByOrganizationIdAndCaseId(UUID organizationId, UUID caseId);
}
