package com.juriscore.legalresearch.service;

import java.util.List;

/**
 * Provider-agnostic port for turning text into vectors. The only implementation shipped
 * in step 2 calls OpenAI ({@link OpenAiEmbeddingClient}); a deterministic
 * {@link FakeEmbeddingClient} backs the test profile so ingestion tests exercise the whole
 * pipeline without a network call or a real API key. Swapping providers (Anthropic, a
 * self-hosted model) is a new implementation of this interface plus a config change —
 * nothing in {@link JudgmentIngestionOrchestrator} or the domain layer changes.
 *
 * <p>Every implementation must return vectors of exactly
 * {@link com.juriscore.legalresearch.domain.EmbeddingSchema#VECTOR_DIMENSIONS} dimensions
 * — the orchestrator checks this and fails the judgment rather than writing a
 * wrong-width vector into a fixed-width pgvector column.
 */
public interface EmbeddingClient {

    /** The model name this client is configured for, for logging/diagnostics only. */
    String modelName();

    /**
     * Embeds a batch of chunk texts, preserving order: result.get(i) is the embedding for
     * texts.get(i). Batched rather than one-at-a-time so a real HTTP-backed implementation
     * can make one round trip per judgment's chunks instead of one per chunk.
     *
     * @throws EmbeddingException if the provider call fails or returns something unusable
     *                            (wrong count, wrong dimensionality, malformed response) —
     *                            never silently returns a partial or fabricated result
     */
    List<float[]> embedBatch(List<String> texts);
}
