package com.juriscore.legalresearch.service;

/**
 * An embedding provider call failed or returned something this module cannot trust —
 * wrong item count, wrong vector width, a transport error, a non-2xx response. Always
 * caught by {@link JudgmentIngestionOrchestrator} and turned into a FAILED judgment with
 * this exception's message as the reason; never silently swallowed.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
