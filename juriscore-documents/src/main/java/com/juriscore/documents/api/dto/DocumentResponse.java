package com.juriscore.documents.api.dto;

import com.juriscore.documents.domain.CaseDocument;
import com.juriscore.documents.domain.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A document as clients see it.
 *
 * <p>No {@code storageKey}. The bucket layout is internal, and a client that knows it can
 * start reasoning about — or guessing at — other keys. Everything a caller legitimately
 * needs is here; the key is reachable only through the download endpoint, which hands back
 * a signed URL for one object and nothing else.
 */
@Schema(description = "Document metadata. The file itself is fetched with a short-lived "
        + "link from the download endpoint; it is never served from a permanent URL.")
public record DocumentResponse(
        UUID id,
        UUID caseId,
        String filename,
        String contentType,
        long fileSize,
        DocumentStatus status,
        String description,
        Instant uploadedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy,
        UUID updatedBy,
        long version) {

    public static DocumentResponse from(CaseDocument document) {
        return new DocumentResponse(
                document.getId(),
                document.getCaseId(),
                document.getOriginalFilename(),
                document.getContentType(),
                document.getFileSize(),
                document.getStatus(),
                document.getDescription(),
                document.getUploadedAt(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                document.getCreatedBy(),
                document.getUpdatedBy(),
                document.getVersion());
    }
}
