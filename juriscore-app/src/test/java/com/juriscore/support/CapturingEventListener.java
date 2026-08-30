package com.juriscore.support;

import com.juriscore.common.event.DomainEvent;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures published domain events so tests can read values that never cross the API
 * boundary — invitation and password-reset tokens exist only on the event, because only
 * their hash is stored.
 *
 * <p>Synchronous on purpose, unlike the production listener: a test that races an
 * {@code @Async} handler is a test that fails on a busy CI runner and passes locally.
 * It still listens {@code AFTER_COMMIT}, so it also proves events are not delivered for
 * work that rolled back.
 */
@TestComponent
public class CapturingEventListener {

    private final List<DomainEvent> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEvent event) {
        events.add(event);
    }

    public void clear() {
        events.clear();
    }

    public List<DomainEvent> all() {
        return List.copyOf(events);
    }

    public <T extends DomainEvent> Optional<T> latest(Class<T> type) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (type.isInstance(events.get(i))) {
                return Optional.of(type.cast(events.get(i)));
            }
        }
        return Optional.empty();
    }

    public <T extends DomainEvent> T require(Class<T> type) {
        return latest(type).orElseThrow(() ->
                new AssertionError("No " + type.getSimpleName() + " was published. Captured: "
                        + events.stream().map(DomainEvent::eventType).toList()));
    }
}
