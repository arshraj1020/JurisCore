package com.juriscore.legalresearch.service;

/**
 * Provider-agnostic port for LLM calls (query understanding, evidence-grounded legal
 * analysis, relationship/contradiction classification). Deliberately unused by step 2 —
 * ingestion needs no LLM, only embeddings — and deliberately declared now, alongside
 * {@link EmbeddingClient}, so the retrieval/analysis work landing in a later step has a
 * seam to implement against rather than reaching for an SDK type directly. Kept inside
 * {@code juriscore-legal-research}, not {@code juriscore-common}: no other module has any
 * reason to call an LLM.
 */
public interface LlmClient {

    /** The model name this client is configured for, for logging/diagnostics only. */
    String modelName();
}
