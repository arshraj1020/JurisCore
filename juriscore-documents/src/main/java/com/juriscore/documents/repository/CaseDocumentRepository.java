package com.juriscore.documents.repository;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries {@code organizationId}. There is deliberately no lookup that does
 * not — the same rule the casework and case-management repositories follow.
 *
 * <p>The list methods carry {@code DeletedAtIsNull} as well, and the ordering is baked
 * into the method name rather than left to a caller's {@code Pageable}: newest first, with
 * the id as a tiebreak, matching the partial index the migration creates. Several
 * documents uploaded in the same second would otherwise page unstably.
 */
public interface CaseDocumentRepository extends JpaRepository<CaseDocument, UUID> {

    /**
     * Includes deleted rows. Used only where the caller has already decided how to treat
     * one — the object cleaner needs the storage key of a row that is already DELETED.
     */
    Optional<CaseDocument> findByIdAndOrganizationId(UUID id, UUID organizationId);

    /** The lookup every request path uses. A deleted document answers not-found. */
    Optional<CaseDocument> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, UUID organizationId);

    Page<CaseDocument> findByOrganizationIdAndCaseIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID caseId, Pageable pageable);

    Page<CaseDocument> findByOrganizationIdAndCaseIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
            UUID organizationId, UUID caseId, DocumentStatus status, Pageable pageable);
}
