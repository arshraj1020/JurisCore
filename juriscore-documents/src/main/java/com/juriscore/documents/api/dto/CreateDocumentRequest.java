package com.juriscore.documents.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Asks for an upload link. No bytes: the browser PUTs them straight to storage.
 *
 * <p>Bean Validation covers the shape; {@code DocumentUploadPolicy} covers the rules that
 * need configuration — the content-type allowlist and the size ceiling — so the two are
 * not duplicated in annotations that would drift from the configured values.
 */
public record CreateDocumentRequest(
        @Schema(description = "The name to show people. Never used as the storage key, and "
                + "must contain no path separator, no '..' and no control characters.",
                example = "Written statement.pdf")
        @NotBlank @Size(max = 255) String filename,

        @Schema(description = "Must be on the configured allowlist. Signed into the upload "
                + "link, so the upload has to declare the same type.",
                example = "application/pdf")
        @NotBlank @Size(max = 255) String contentType,

        @Schema(description = "Bytes. Checked against the maximum now, and again against "
                + "the size storage reports when the upload is completed.")
        @Positive long fileSize,

        @Size(max = 2000) String description) {
}
