package com.juriscore.casework.domain;

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
 * One entry on a case's timeline.
 *
 * <p>Append-only by construction rather than by constraint: the service has no mutator,
 * and no controller maps PUT, PATCH or DELETE onto these rows. Every setter below exists
 * so the entity can be built; the columns are marked {@code updatable = false} so that
 * even a stray {@code set} on a managed instance cannot rewrite history.
 *
 * <p>Written inside the transaction that caused it, so a rolled-back case creation
 * leaves no orphan entry. That is the opposite of the domain events this module also
 * publishes, which are deliberately delivered only after commit.
 */
@Entity
@Table(name = "case_events", schema = "casework")
@Getter
@Setter
@NoArgsConstructor
public class CaseEvent extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private CaseEventType eventType;

    /** Null only for entries the system writes with no signed-in actor. */
    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "summary", nullable = false, length = 1000, updatable = false)
    private String summary;
}
