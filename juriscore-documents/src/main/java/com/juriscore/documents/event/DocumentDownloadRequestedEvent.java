package com.juriscore.documents.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * Somebody was issued a link to a document.
 *
 * <p>The one event here that records a read rather than a state change, and it earns the
 * exception: who looked at which filing, and when, is exactly what a firm is asked for
 * when a matter is disputed. The platform has no audit subsystem yet — that is Phase 5 —
 * so this is the record that will feed it, and {@code actorUserId} is the point of the
 * whole event.
 *
 * <p>The URL that was issued is deliberately absent. Publishing it would put a live bearer
 * credential for the object onto the bus and into every log line that renders an event.
 */
@Getter
public class DocumentDownloadRequestedEvent extends AbstractDomainEvent {

    private final UUID documentId;
    private final UUID caseId;
    private final UUID actorUserId;

    public DocumentDownloadRequestedEvent(UUID organizationId, UUID documentId, UUID caseId,
                                          UUID actorUserId) {
        super(organizationId);
        this.documentId = documentId;
        this.caseId = caseId;
        this.actorUserId = actorUserId;
    }

    @Override
    public String eventType() {
        return "document.download_requested";
    }
}
