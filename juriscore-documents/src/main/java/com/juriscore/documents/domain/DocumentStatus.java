package com.juriscore.documents.domain;

/**
 * Where a document stands. Mirrored by {@code ck_case_documents_status}.
 *
 * <p>The lifecycle exists because the bytes and the metadata are written by two different
 * parties at two different times: the row is created when a link is issued, and the object
 * appears — or does not — some time later, uploaded by the browser directly. A document is
 * therefore a claim until storage confirms it, and {@link #UPLOADING} is that claim.
 */
public enum DocumentStatus {

    /**
     * A link has been issued. The object may not exist, may be half-written, or may never
     * arrive. Nothing downloads in this state.
     */
    UPLOADING,

    /** Confirmed against storage. The only state that yields a download link. */
    AVAILABLE,

    /** Soft-deleted. Terminal. */
    DELETED,

    /** The upload was attempted and refused — wrong size, wrong type, or never finished. */
    FAILED;

    public boolean isTerminal() {
        return this == DELETED;
    }

    public boolean isDownloadable() {
        return this == AVAILABLE;
    }
}
