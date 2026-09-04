package com.juriscore.notifications.service;

import com.juriscore.notifications.domain.NotificationType;

import java.util.UUID;

/**
 * What a producer has to say to raise a notification.
 *
 * <p>A record rather than eight parameters, because the two UUIDs and three strings in the
 * middle are exactly the kind of argument list that gets transposed silently.
 *
 * <p>{@code dedupeKey} is how a producer says "this business fact should reach this person
 * once". Null means it is genuinely repeatable. {@code actionPath} must be a relative
 * in-app path; the database rejects anything that does not start with {@code /}, so an
 * absolute or signed URL cannot be stored here even by mistake.
 */
public record NotificationRequest(
        UUID organizationId,
        UUID recipientUserId,
        NotificationType type,
        String title,
        String message,
        String entityType,
        UUID entityId,
        String actionPath,
        String dedupeKey) {

    public static Builder to(UUID organizationId, UUID recipientUserId, NotificationType type) {
        return new Builder(organizationId, recipientUserId, type);
    }

    /** Small builder so the optional half does not become a call with five nulls in it. */
    public static final class Builder {
        private final UUID organizationId;
        private final UUID recipientUserId;
        private final NotificationType type;
        private String title;
        private String message;
        private String entityType;
        private UUID entityId;
        private String actionPath;
        private String dedupeKey;

        private Builder(UUID organizationId, UUID recipientUserId, NotificationType type) {
            this.organizationId = organizationId;
            this.recipientUserId = recipientUserId;
            this.type = type;
        }

        public Builder saying(String title, String message) {
            this.title = title;
            this.message = message;
            return this;
        }

        public Builder about(String entityType, UUID entityId) {
            this.entityType = entityType;
            this.entityId = entityId;
            return this;
        }

        public Builder linkingTo(String actionPath) {
            this.actionPath = actionPath;
            return this;
        }

        public Builder onceFor(String dedupeKey) {
            this.dedupeKey = dedupeKey;
            return this;
        }

        public NotificationRequest build() {
            return new NotificationRequest(organizationId, recipientUserId, type, title, message,
                    entityType, entityId, actionPath, dedupeKey);
        }
    }
}
