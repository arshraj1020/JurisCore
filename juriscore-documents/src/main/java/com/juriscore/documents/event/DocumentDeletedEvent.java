package com.juriscore.documents.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * A document was removed from a matter.
 *
 * <p>Published after the metadata commit, and it is what triggers the object removal:
 * {@code DeletedDocumentObjectCleaner} listens for it. It carries no storage key — the
 * cleaner re-reads the row for that, so the bucket layout stays inside this module even
 * though the event travels outside it.
 */
@Getter
public class DocumentDeletedEvent extends AbstractDomainEvent {

    private final UUID documentId;
    private final UUID caseId;
    private final String filename;

    public DocumentDeletedEvent(UUID organizationId, UUID documentId, UUID caseId, String filename) {
        super(organizationId);
        this.documentId = documentId;
        this.caseId = caseId;
        this.filename = filename;
    }

    @Override
    public String eventType() {
        return "document.deleted";
    }
}
