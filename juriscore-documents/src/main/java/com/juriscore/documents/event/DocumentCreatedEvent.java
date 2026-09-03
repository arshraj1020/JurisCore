package com.juriscore.documents.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * A document was registered and an upload link issued. The object may never arrive, so a
 * consumer must not read this as "a file exists" — {@code document.upload_completed} is
 * that signal.
 *
 * <p>Carries no presigned URL and no storage key. The URL is a bearer credential and the
 * key is internal layout; neither belongs on a bus that other modules and, in Phase 5, a
 * queue will read.
 */
@Getter
public class DocumentCreatedEvent extends AbstractDomainEvent {

    private final UUID documentId;
    private final UUID caseId;
    private final String filename;
    private final String contentType;
    private final long declaredSize;

    public DocumentCreatedEvent(UUID organizationId, UUID documentId, UUID caseId, String filename,
                                String contentType, long declaredSize) {
        super(organizationId);
        this.documentId = documentId;
        this.caseId = caseId;
        this.filename = filename;
        this.contentType = contentType;
        this.declaredSize = declaredSize;
    }

    @Override
    public String eventType() {
        return "document.created";
    }
}
