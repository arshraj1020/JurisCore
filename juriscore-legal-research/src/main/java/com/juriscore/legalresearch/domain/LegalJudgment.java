package com.juriscore.legalresearch.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A judgment being ingested for Legal Precedent Intelligence.
 *
 * <p>This is deliberately NOT a duplicate of {@code documents.case_documents}: the bytes,
 * upload lifecycle and access control all stay owned by the documents module. This row
 * exists only for the facts documents has no reason to know — extracted case metadata,
 * chunking/embedding progress — joined back to the source document by
 * {@link #sourceDocumentId} (a plain UUID, no FK, same cross-schema rule every other
 * module follows).
 *
 * <p>Every metadata field is nullable and is left null rather than guessed when
 * extraction cannot confidently identify it — see the hallucination-prevention rule this
 * schema exists to make enforceable. Nothing downstream may treat a null field as
 * "unknown, fill in something plausible".
 */
@Entity
@Table(name = "legal_judgments", schema = "legal_research")
@Getter
@Setter
@NoArgsConstructor
public class LegalJudgment extends TenantAwareEntity {

    @Column(name = "source_document_id", nullable = false, updatable = false)
    private UUID sourceDocumentId;

    @Column(name = "case_name", length = 500)
    private String caseName;

    @Column(name = "court", length = 255)
    private String court;

    @Column(name = "decided_date")
    private LocalDate decidedDate;

    /** Free text for now (e.g. "J. Smith, J. Rao"); kept simple until step 2 needs more. */
    @Column(name = "judges", length = 1000)
    private String judges;

    @Column(name = "citation", length = 255)
    private String citation;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_status", nullable = false, length = 32)
    private JudgmentExtractionStatus extractionStatus = JudgmentExtractionStatus.PENDING;

    @Column(name = "failure_reason", length = 2000)
    private String failureReason;
}
