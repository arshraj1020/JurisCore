package com.juriscore.legalresearch.domain;

/**
 * Where a judgment stands in the ingestion pipeline. Mirrored by
 * {@code ck_legal_judgments_status}.
 *
 * <p>Each step is written by the ingestion listener, not the upload request — a judgment
 * starts life the moment its source document is registered, and only advances once the
 * underlying object has actually been confirmed, downloaded and processed. Modeled the
 * same way {@link com.juriscore.documents.domain.DocumentStatus} tracks the split between
 * "a row exists" and "storage confirmed it".
 */
public enum JudgmentExtractionStatus {

    /** Registered against a source document; extraction has not started. */
    PENDING,

    /** Text is being pulled from the source object. */
    EXTRACTING,

    /** Text extracted and split into {@link LegalChunk} rows; embeddings not yet computed. */
    CHUNKED,

    /** Every chunk has an embedding. */
    EMBEDDED,

    /** Fully indexed and eligible for retrieval. */
    READY,

    /** Extraction, chunking or embedding failed. See the judgment's failure reason. */
    FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }

    public boolean isSearchable() {
        return this == READY;
    }
}
