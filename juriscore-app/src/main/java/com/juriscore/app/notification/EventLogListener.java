package com.juriscore.app.notification;

import com.juriscore.common.event.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Placeholder consumer standing in for the Notification and Audit services.
 *
 * <p>It is here to prove the delivery contract that the real consumers will rely on:
 * {@code AFTER_COMMIT} means a rolled-back registration never produces a "welcome"
 * email, and {@code @Async} means a slow consumer cannot add latency to the request
 * that produced the event. Phase 5 replaces this with SQS consumers; the events and
 * the publishing code stay as they are.
 */
@Component
public class EventLogListener {

    private static final Logger log = LoggerFactory.getLogger(EventLogListener.class);

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEvent event) {
        log.info("event={} id={} organization={} at={}",
                event.eventType(), event.eventId(), event.organizationId(), event.occurredAt());
    }
}
