package com.juriscore.notifications.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Something a user should know about, waiting in the application for them to read.
 *
 * <p><strong>In-app only.</strong> Nothing in Phase 5 sends an email, an SMS, a WhatsApp
 * message or a push notification, and this entity has no column that would carry one — no
 * delivery status, no provider message id, no retry count. The API is the only channel
 * that exists, and a status field called {@code SENT} on a row nothing has ever sent is
 * exactly the sort of thing a later reader believes.
 *
 * <p>Scoped twice: to the firm, like everything else, and to one user inside it. A
 * notification is addressed to a person, so {@code recipientUserId} is part of every query
 * rather than a field checked afterwards — a notification belonging to a colleague answers
 * not-found, the same as one belonging to another firm.
 *
 * <p>{@link #actionPath} is a relative in-app path and the database enforces that it starts
 * with {@code /}. Never an absolute URL and never a signed one: a presigned link is a
 * bearer credential for a document, and a row that sits in somebody's inbox indefinitely is
 * the last place one should be kept.
 */
@Entity
@Table(name = "notifications", schema = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification extends TenantAwareEntity {

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 64, updatable = false)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32, updatable = false)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 32, updatable = false)
    private NotificationSeverity severity;

    @Column(name = "title", nullable = false, length = 200, updatable = false)
    private String title;

    @Column(name = "message", nullable = false, length = 1000, updatable = false)
    private String message;

    /** What the notification is about, e.g. {@code INVOICE}. Free text, not a foreign key. */
    @Column(name = "entity_type", length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "action_path", length = 500, updatable = false)
    private String actionPath;

    /** The only mutable field, and the only thing a user can change about a notification. */
    @Column(name = "read_at")
    private Instant readAt;

    /**
     * What makes delivery at-most-once for a business fact.
     *
     * <p>Derived by the listener from the event and the entity it concerns, so a repeated
     * overdue sweep, a retried publish or a second listener invocation all collide on
     * {@code uk_notifications_dedupe} instead of filling somebody's inbox. Null means "no
     * deduplication wanted", which is right for anything genuinely repeatable.
     */
    @Column(name = "dedupe_key", length = 200, updatable = false)
    private String dedupeKey;

    public boolean isRead() {
        return readAt != null;
    }

    /** Marking as read twice is not an error; it is a person clicking twice. */
    public void markRead(Instant when) {
        if (readAt == null) {
            readAt = when;
        }
    }
}
