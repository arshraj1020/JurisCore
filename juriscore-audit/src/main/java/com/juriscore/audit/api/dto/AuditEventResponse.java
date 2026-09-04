package com.juriscore.audit.api.dto;

import com.juriscore.audit.domain.AuditEvent;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row.
 *
 * <p>No {@code organizationId}: the caller can only ever see their own firm's, so echoing
 * it back adds nothing. {@code sourceEventId} is omitted too — it is the deduplication key
 * for an internal bus, not something a reader has any use for.
 */
@Schema(description = "One recorded action. Append-only: there is no endpoint that changes or removes these.")
public record AuditEventResponse(
        UUID id,
        @Schema(description = "The domain event this came from.", example = "invoice.issued")
        String action,
        String entityType,
        UUID entityId,
        @Schema(description = "Null for actions the system took with no signed-in user.")
        UUID actorUserId,
        Instant occurredAt,
        @Schema(description = "The request id, matching the application logs for the same request.")
        String requestId,
        @Schema(description = "Never contains a credential, a signed URL or a request body.")
        String summary,
        Instant recordedAt) {

    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getAction(),
                event.getEntityType(),
                event.getEntityId(),
                event.getActorUserId(),
                event.getOccurredAt(),
                event.getRequestId(),
                event.getSummary(),
                event.getCreatedAt());
    }
}
