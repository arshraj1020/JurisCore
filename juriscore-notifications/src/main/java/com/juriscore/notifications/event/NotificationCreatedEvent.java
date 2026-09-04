package com.juriscore.notifications.event;

import com.juriscore.common.event.AbstractDomainEvent;
import com.juriscore.notifications.domain.NotificationType;
import lombok.Getter;

import java.util.UUID;

/**
 * A notification now exists for somebody.
 *
 * <p>Carries the type and the recipient, and deliberately not the title or the message. A
 * notification's text can name a client and an amount; the event exists so that a future
 * delivery channel — an email sender, a websocket push — knows there is something to
 * fetch, not so that the content travels the bus. It is also what keeps this event from
 * recursing: nothing maps {@code notification.created} back to a notification.
 */
@Getter
public class NotificationCreatedEvent extends AbstractDomainEvent {

    private final UUID notificationId;
    private final UUID recipientUserId;
    private final NotificationType notificationType;

    public NotificationCreatedEvent(UUID organizationId, UUID notificationId,
                                    UUID recipientUserId, NotificationType notificationType) {
        super(organizationId);
        this.notificationId = notificationId;
        this.recipientUserId = recipientUserId;
        this.notificationType = notificationType;
    }

    @Override
    public String eventType() {
        return "notification.created";
    }
}
