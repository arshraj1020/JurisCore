package com.juriscore.casework.repository;

import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.casework.domain.LegalCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * The filter combinations are separate derived methods rather than one query with
 * optional parameters, following {@code UserRepository}: an HQL {@code :status is null
 * or c.status = :status} leaves Hibernate to infer an enum's type from a null, which is
 * a runtime failure waiting for the day somebody filters by nothing.
 */
public interface CaseRepository extends JpaRepository<LegalCase, UUID> {

    Optional<LegalCase> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndCaseNumber(UUID organizationId, String caseNumber);

    long countByOrganizationIdAndClientId(UUID organizationId, UUID clientId);

    Page<LegalCase> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<LegalCase> findByOrganizationIdAndStatus(UUID organizationId, CaseStatus status, Pageable pageable);

    Page<LegalCase> findByOrganizationIdAndClientId(UUID organizationId, UUID clientId, Pageable pageable);

    Page<LegalCase> findByOrganizationIdAndStatusAndClientId(UUID organizationId,
                                                             CaseStatus status,
                                                             UUID clientId,
                                                             Pageable pageable);

    @Query("""
            select c from LegalCase c
            where c.organizationId = :organizationId
              and (lower(c.title) like lower(concat('%', :search, '%'))
                   or lower(c.caseNumber) like lower(concat('%', :search, '%')))
            """)
    Page<LegalCase> search(@Param("organizationId") UUID organizationId,
                           @Param("search") String search,
                           Pageable pageable);
}
