package com.juriscore.common.event;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Phase-1 event bus: in-process dispatch.
 *
 * <p>Listeners annotated {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * only run once the producing transaction has committed, so a rolled-back case
 * creation never sends a notification. Replacing this bean with an SQS publisher in
 * Phase 5 is the only change required to go out-of-process.
 */
@Component
@RequiredArgsConstructor
public class SpringEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringEventPublisher.class);

    private final ApplicationEventPublisher delegate;

    @Override
    public void publish(DomainEvent event) {
        log.debug("Publishing {} [{}] for organization {}", event.eventType(), event.eventId(),
                event.organizationId());
        delegate.publishEvent(event);
    }
}
