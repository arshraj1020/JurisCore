package com.juriscore.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Marker for everything published onto the event bus.
 *
 * <p>{@link #eventId()} is the idempotency key: consumers record it before acting, so
 * an at-least-once delivery (SQS guarantees no better) cannot bill a client twice
 * (PRD §41.3).
 */
public interface DomainEvent {

    UUID eventId();

    /** Tenant the event belongs to; consumers must not act across tenants. */
    UUID organizationId();

    Instant occurredAt();

    /** Stable name used for routing and for the audit log, e.g. {@code case.created}. */
    String eventType();
}
