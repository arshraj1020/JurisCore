package com.juriscore.documents.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Document policy, bound from {@code juriscore.documents.*}.
 *
 * <p>Separate from {@code AwsProperties} on purpose: which bucket and how long a link
 * lives are facts about the storage backend, while what a firm is willing to accept is a
 * business rule that survives replacing S3 with anything else.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.documents")
public class DocumentProperties {

    /** 50 MB. Applied when the link is issued and again against the real size at completion. */
    private long maxFileSize = 52_428_800L;

    /** Matches the column width, so a name that fits the rule always fits the row. */
    private int maxFilenameLength = 255;

    /**
     * An allowlist, and it has to be: an extension is a claim the uploader makes, and
     * nothing in this platform opens the file to check. Refusing everything not named
     * here is the only control available without content inspection, which Phase 4
     * deliberately does not attempt.
     */
    private List<String> allowedContentTypes = List.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/rtf",
            "text/plain",
            "text/csv",
            "image/jpeg",
            "image/png",
            "image/tiff");
}
