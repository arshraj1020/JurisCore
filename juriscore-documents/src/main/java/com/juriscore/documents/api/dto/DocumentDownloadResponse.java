package com.juriscore.documents.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A short-lived link to fetch one document.
 *
 * <p>Issued only after the caller has been authorized and only for a document that is
 * AVAILABLE and not deleted. It arrives as a download rather than something the browser
 * renders in place, which is the mitigation that matters given the platform does no
 * content inspection.
 */
@Schema(description = "A time-limited download link. Nothing in this platform is served "
        + "from a permanent or public URL.")
public record DocumentDownloadResponse(
        String downloadUrl,
        String filename,
        String contentType,
        long fileSize,
        Instant expiresAt,
        long expiresInSeconds) {
}
