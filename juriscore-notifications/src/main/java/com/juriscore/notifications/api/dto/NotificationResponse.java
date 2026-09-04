package com.juriscore.notifications.api.dto;

import com.juriscore.notifications.domain.Notification;
import com.juriscore.notifications.domain.NotificationCategory;
import com.juriscore.notifications.domain.NotificationSeverity;
import com.juriscore.notifications.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A notification as its recipient sees it.
 *
 * <p>No {@code recipientUserId} and no {@code organizationId}: the caller is the recipient
 * — there is no endpoint that returns anybody else's — so echoing either back only hands
 * out internal identifiers. No {@code dedupeKey} either; it is a delivery mechanism, not
 * something a reader has any use for.
 */
@Schema(description = "An in-app notification. JurisCore sends no email, SMS or push.")
public record NotificationResponse(
        UUID id,
        NotificationType type,
        NotificationCategory category,
        NotificationSeverity severity,
        String title,
        String message,
        String entityType,
        UUID entityId,
        @Schema(description = "A relative in-app path, never an absolute or signed URL.",
                example = "/invoices/6f1c…")
        String actionPath,
        Instant readAt,
        boolean read,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getCategory(),
                notification.getSeverity(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getEntityType(),
                notification.getEntityId(),
                notification.getActionPath(),
                notification.getReadAt(),
                notification.isRead(),
                notification.getCreatedAt());
    }
}
