package com.juriscore.documents.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The two fields a person may change about a document.
 *
 * <p>Everything else is absent by design, and the absence is the control: content type,
 * size, storage key, case, organization and status have no field here, so no request can
 * carry them. Status in particular has no endpoint at all — the lifecycle is driven by
 * completion and deletion, not by an edit.
 */
public record UpdateDocumentRequest(
        @NotBlank @Size(max = 255) String filename,

        @Size(max = 2000) String description,

        @Schema(description = "The version you last read from DocumentResponse.version. "
                + "A stale value answers 409 CONCURRENT_MODIFICATION.")
        @NotNull Long version) {
}
