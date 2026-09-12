package com.juriscore.legalresearch.repository;

import com.juriscore.legalresearch.domain.LegalChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Plain CRUD only for step 1. Hybrid retrieval (pgvector cosine + PostgreSQL full-text,
 * combined with Reciprocal Rank Fusion) is a native-query method added alongside the
 * retrieval service in a later step, once there is something to retrieve.
 */
public interface LegalChunkRepository extends JpaRepository<LegalChunk, UUID> {

    List<LegalChunk> findByJudgmentIdOrderByChunkIndexAsc(UUID judgmentId);

    long countByJudgmentId(UUID judgmentId);
}
