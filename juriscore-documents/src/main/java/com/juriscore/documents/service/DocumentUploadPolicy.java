package com.juriscore.documents.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * What the platform will accept, checked before a link is ever issued.
 *
 * <p>All of it is server-side. The client is told the rules through OpenAPI, and none of
 * that telling is trusted: filename, content type and size all arrive from a caller who
 * may be hostile, and the object they eventually PUT is written by the browser directly to
 * S3 without passing through here at all.
 *
 * <h2>What this does not do</h2>
 *
 * <p>No malware scanning, no content inspection, no magic-byte check. The platform has no
 * scanning infrastructure and Phase 4 does not add any, so a PDF that is really something
 * else will be accepted as a PDF. Saying so plainly is better than a check that looks like
 * one and is not: the mitigations that are real are the content-type allowlist, the size
 * ceiling, the fact that nothing is ever served from a public URL, and that downloads go
 * out as {@code Content-Disposition: attachment} rather than being rendered.
 */
@Component
@RequiredArgsConstructor
public class DocumentUploadPolicy {

    private final DocumentProperties properties;

    /**
     * @throws ApiException {@code VALIDATION_FAILED} (400) with a message naming the rule
     *                      that was broken
     */
    public void validate(String filename, String contentType, long fileSize) {
        validateFilename(filename);
        validateContentType(contentType);
        validateFileSize(fileSize);
    }

    /**
     * The filename never becomes part of the object key, so this is not the control that
     * prevents traversal — the key is built from UUIDs and cannot contain it. This is the
     * layer that keeps a hostile name out of the database, out of the
     * {@code Content-Disposition} header on the way back down, and off any screen that
     * renders it.
     */
    public void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A filename is required");
        }
        String trimmed = filename.trim();
        if (trimmed.length() > properties.getMaxFilenameLength()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The filename must be at most " + properties.getMaxFilenameLength()
                            + " characters");
        }
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The filename must not contain a path separator");
        }
        if (trimmed.contains("..")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The filename must not contain '..'");
        }
        if (trimmed.equals(".")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That is not a filename");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Control characters, including NUL and the CR/LF that would let a name split
            // the Content-Disposition header it ends up inside.
            if (c < 0x20 || c == 0x7F) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED,
                        "The filename must not contain control characters");
            }
        }
    }

    public void validateContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A content type is required");
        }
        if (!isAllowed(contentType)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Files of type " + normalise(contentType) + " are not accepted");
        }
    }

    public void validateFileSize(long fileSize) {
        if (fileSize <= 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The file size must be greater than zero");
        }
        if (fileSize > properties.getMaxFileSize()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The file must be at most " + properties.getMaxFileSize() + " bytes");
        }
    }

    public boolean isAllowed(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalised = normalise(contentType);
        return properties.getAllowedContentTypes().stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(normalised));
    }

    public long maxFileSize() {
        return properties.getMaxFileSize();
    }

    /** Drops any {@code ;charset=} parameter and lowercases, so {@code TEXT/PLAIN; charset=utf-8} matches. */
    static String normalise(String contentType) {
        int parameter = contentType.indexOf(';');
        String base = parameter < 0 ? contentType : contentType.substring(0, parameter);
        return base.trim().toLowerCase(Locale.ROOT);
    }
}
