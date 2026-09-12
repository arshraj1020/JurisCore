package com.juriscore.legalresearch.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.util.UUID;

/**
 * One retrievable passage of a {@link LegalJudgment}.
 *
 * <p>{@link #paragraphNumber} and {@link #pageNumber} are what let a citation produced
 * later point at an exact location in the source judgment rather than "the document
 * somewhere" — they are carried through from extraction/chunking and are null, never a
 * guessed value, when the source structure did not make them extractable.
 *
 * <p>{@link #embedding} is nullable and populated by a separate embedding step, not at
 * chunk-creation time — a chunk can exist (and be findable via full-text search) before it
 * has a vector. Its width is fixed at {@link EmbeddingSchema#VECTOR_DIMENSIONS} by the
 * database column; see that class for why the dimension can't be a runtime setting.
 *
 * <p>{@code search_vector} is a database-generated column (PostgreSQL full-text search,
 * not mapped here) — Hibernate never writes it, only the migration's GIN index reads it.
 */
@Entity
@Table(name = "legal_chunks", schema = "legal_research")
@Getter
@Setter
@NoArgsConstructor
public class LegalChunk extends TenantAwareEntity {

    @Column(name = "judgment_id", nullable = false, updatable = false)
    private UUID judgmentId;

    @Column(name = "chunk_index", nullable = false, updatable = false)
    private int chunkIndex;

    @Column(name = "chunk_text", nullable = false, updatable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "paragraph_number")
    private Integer paragraphNumber;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Type(PgVectorType.class)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private float[] embedding;
}
