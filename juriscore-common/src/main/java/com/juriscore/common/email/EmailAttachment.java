package com.juriscore.common.email;

import java.util.Objects;

/**
 * Bytes to attach, with the name the recipient should see.
 *
 * <p>Held in memory, which is the right call at this size and only at this size: an
 * invoice PDF is a few tens of kilobytes and is generated on demand, so there is nothing
 * to stream from and nowhere to stream it to. A provider that accepts a raw MIME message
 * has to be handed the whole thing anyway. If something large ever needs sending, it
 * belongs behind a link rather than in an inbox — which is the rule
 * {@code ObjectStorageService} already states for case documents.
 */
public record EmailAttachment(String fileName, String contentType, byte[] content) {

    public EmailAttachment {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(content, "content");
    }

    public int sizeBytes() {
        return content.length;
    }
}
