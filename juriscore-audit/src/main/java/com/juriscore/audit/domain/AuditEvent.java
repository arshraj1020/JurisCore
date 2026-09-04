package com.juriscore.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened, recorded so it can be answered for later.
 *
 * <h2>Append-only, at four levels</h2>
 *
 * <ol>
 *   <li><strong>Shape.</strong> No {@code version}, no {@code updated_at}, no
 *       {@code updated_by} — so this does not extend {@code BaseEntity}, which supplies
 *       all three. A table with an optimistic-lock column is a table somebody expects to
 *       rewrite, and the absence is the statement.</li>
 *   <li><strong>Mapping.</strong> Every column is {@code updatable = false}, so even a
 *       stray {@code set} on a managed instance produces no UPDATE.</li>
 *   <li><strong>Repository.</strong> {@code AuditEventRepository} does not extend
 *       {@code JpaRepository}; it exposes exactly one write, {@code append}, and no
 *       {@code save}, {@code delete} or {@code deleteAll}.</li>
 *   <li><strong>API.</strong> {@code AuditController} maps GET and nothing else. There is
 *       no PUT, PATCH or DELETE to authorize.</li>
 * </ol>
 *
 * <p>It is also not {@code TenantAwareEntity}: {@code organizationId} has to be nullable
 * here, for the two cases that genuinely have no tenant — a failed sign-in against an
 * address matching no account, and the scheduled sweeps that act with no signed-in user.
 * A tenant column that must be nullable is not the tenant column the rest of the platform
 * means.
 *
 * <h2>What must never be in here</h2>
 *
 * <p>{@link #summary} is a sentence a person wrote, assembled from ids and names.
 * Passwords, access and refresh tokens, AWS credentials, presigned URLs, document bytes,
 * payment references and request bodies are all out — {@code AuditRedaction} is the one
 * place that decides what a summary may contain, and it is asserted directly.
 */
@Entity
@Table(name = "audit_events", schema = "audit")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    @Id
    @GeneratedValue
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** Null for platform-level events that belong to no firm. */
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    /** Null for anything the system did with no signed-in user. */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    /** The domain event type it came from, e.g. {@code invoice.issued}. */
    @Column(name = "action", nullable = false, length = 100, updatable = false)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 64, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** The MDC request id, so an audit row lines up with the logs for the same request. */
    @Column(name = "request_id", length = 64, updatable = false)
    private String requestId;

    @Column(name = "summary", nullable = false, length = 500, updatable = false)
    private String summary;

    /** The domain event's id. UNIQUE, so the same business action is recorded once. */
    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;
}
