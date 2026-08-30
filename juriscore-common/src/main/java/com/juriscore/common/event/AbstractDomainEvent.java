package com.juriscore.common.event;

import java.time.Instant;
import java.util.UUID;

/** Convenience base that stamps the identity/time fields every event needs. */
public abstract class AbstractDomainEvent implements DomainEvent {

    private final UUID eventId = UUID.randomUUID();
    private final Instant occurredAt = Instant.now();
    private final UUID organizationId;

    protected AbstractDomainEvent(UUID organizationId) {
        this.organizationId = organizationId;
    }

    @Override
    public UUID eventId() {
        return eventId;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }

    @Override
    public UUID organizationId() {
        return organizationId;
    }
}
