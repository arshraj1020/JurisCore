package com.juriscore.legalresearch.repository;

import com.juriscore.legalresearch.domain.JudgmentExtractionStatus;
import com.juriscore.legalresearch.domain.LegalJudgment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every method carries {@code organizationId} — no lookup that does not, the same rule
 * every other module's repositories follow (see {@code CaseDocumentRepository}).
 */
public interface LegalJudgmentRepository extends JpaRepository<LegalJudgment, UUID> {

    Optional<LegalJudgment> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<LegalJudgment> findBySourceDocumentIdAndOrganizationId(UUID sourceDocumentId, UUID organizationId);

    boolean existsByOrganizationIdAndExtractionStatus(UUID organizationId, JudgmentExtractionStatus status);
}
