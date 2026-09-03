package com.juriscore.casemanagement.domain;

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
 * One listing of a matter before a court.
 *
 * <p>{@code caseId} points into {@code casework.cases} and therefore carries no foreign
 * key — the schemas are separate on purpose, and the reference is validated through
 * {@code CaseAccess} before anything is written. {@code courtId} is a real foreign key,
 * because courts live in this schema.
 *
 * <p>Neither is a JPA association. Nothing in this codebase is, and the reason is worth
 * repeating: a lazy association is a query that runs without the tenant predicate every
 * other query in the module carries.
 */
@Entity
@Table(name = "hearings", schema = "case_management")
@Getter
@Setter
@NoArgsConstructor
public class Hearing extends TenantAwareEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "court_id", nullable = false)
    private UUID courtId;

    @Enumerated(EnumType.STRING)
    @Column(name = "hearing_type", nullable = false, length = 32)
    private HearingType hearingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private HearingStatus status;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "judge_name", length = 200)
    private String judgeName;

    @Column(name = "courtroom", length = 120)
    private String courtroom;

    @Column(name = "purpose", length = 1000)
    private String purpose;

    @Column(name = "outcome", length = 4000)
    private String outcome;

    /**
     * The only way a hearing changes status.
     *
     * <p>On the entity rather than in the service so the invariant travels with the
     * object: a second caller cannot reach a forbidden state by finding another door.
     */
    public void transitionTo(HearingStatus target) {
        HearingStatusPolicy.requireTransition(this.status, target);
        this.status = target;
    }
}
