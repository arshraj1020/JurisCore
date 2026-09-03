package com.juriscore.documents.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The document, plus the one-time link to push the file to.
 *
 * <p>The only place a presigned URL appears in the whole API surface, and it is returned
 * once, to the caller that was just authorized, over the same TLS connection as the rest
 * of the response. It is never stored, never logged and never published on an event.
 */
@Schema(description = "A registered document and the link to upload its bytes to. PUT the "
        + "file to uploadUrl with the same Content-Type, then call the complete endpoint.")
public record DocumentUploadResponse(
        DocumentResponse document,

        @Schema(description = "PUT the file here. Single use in practice, and dead after "
                + "expiresAt. Treat it as a credential: do not log it or pass it on.")
        String uploadUrl,

        @Schema(description = "The HTTP method the link is signed for. Nothing else works.",
                example = "PUT")
        String uploadMethod,

        @Schema(description = "Send exactly this Content-Type — it is signed into the link, "
                + "so storage rejects an upload that declares anything else.")
        String requiredContentType,

        Instant expiresAt,

        @Schema(description = "Seconds until the link stops working.")
        long expiresInSeconds) {
}
