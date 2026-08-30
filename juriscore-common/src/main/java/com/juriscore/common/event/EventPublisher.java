package com.juriscore.common.event;

/**
 * Outbound port for domain events.
 *
 * <p>Phase 1 ships an in-process implementation backed by Spring's
 * {@code ApplicationEventPublisher} with {@code AFTER_COMMIT} listeners. Phase 5
 * swaps in an SQS-backed implementation; publishing code does not change, which is
 * the whole point of the port.
 */
public interface EventPublisher {

    void publish(DomainEvent event);
}
