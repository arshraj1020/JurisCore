package com.juriscore.documents.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * Storage confirmed the object and the document is downloadable.
 *
 * <p>{@code size} is the size storage reported, not the size the client declared — this is
 * the first point at which the platform knows the real number.
 */
@Getter
public class DocumentUploadCompletedEvent extends AbstractDomainEvent {

    private final UUID documentId;
    private final UUID caseId;
    private final String filename;
    private final String contentType;
    private final long size;

    public DocumentUploadCompletedEvent(UUID organizationId, UUID documentId, UUID caseId,
                                        String filename, String contentType, long size) {
        super(organizationId);
        this.documentId = documentId;
        this.caseId = caseId;
        this.filename = filename;
        this.contentType = contentType;
        this.size = size;
    }

    @Override
    public String eventType() {
        return "document.upload_completed";
    }
}
