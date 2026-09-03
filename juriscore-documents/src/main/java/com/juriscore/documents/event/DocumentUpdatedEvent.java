package com.juriscore.documents.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A document's description or display name was edited. The file itself never changes. */
@Getter
public class DocumentUpdatedEvent extends AbstractDomainEvent {

    private final UUID documentId;
    private final UUID caseId;
    private final String filename;

    public DocumentUpdatedEvent(UUID organizationId, UUID documentId, UUID caseId, String filename) {
        super(organizationId);
        this.documentId = documentId;
        this.caseId = caseId;
        this.filename = filename;
    }

    @Override
    public String eventType() {
        return "document.updated";
    }
}
