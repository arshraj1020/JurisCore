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
 * A matter.
 *
 * <p>The class is called {@code LegalCase} rather than {@code Case} on purpose:
 * {@code case} is a reserved word in HQL, so an entity of that name breaks every
 * generated query the moment Spring Data renders {@code select c from Case c}. The
 * table is still {@code casework.cases}, which is what the API and the schema speak.
 *
 * <p>{@code clientId} is a plain UUID with a real foreign key behind it — both tables
 * live in {@code casework}. It is not a {@code @ManyToOne}, because nothing in this
 * codebase is: associations invite lazy loads that quietly step around the tenant
 * predicate every repository method carries.
 *
 * <p>The optimistic-lock column comes from {@code BaseEntity}. It is what turns two
 * lawyers editing one matter into a 409 {@code CONCURRENT_MODIFICATION} instead of a
 * lost update.
 */
@Entity
@Table(name = "cases", schema = "casework")
@Getter
@Setter
@NoArgsConstructor
public class LegalCase extends TenantAwareEntity {

    /** {@code CASE-2026-000001}. Unique per firm, issued by {@code CaseNumberGenerator}. */
    @Column(name = "case_number", nullable = false, length = 32, updatable = false)
    private String caseNumber;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "client_id", nullable = false)
    private UUID clientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private CaseStatus status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * The only way a case changes status.
     *
     * <p>Lives on the entity rather than in the service so that the invariant travels
     * with the object: a second caller cannot reach a state the lifecycle forbids by
     * going round a service method.
     */
    public void transitionTo(CaseStatus target, Instant when) {
        CaseStatusPolicy.requireTransition(this.status, target);
        this.status = target;
        this.closedAt = target == CaseStatus.CLOSED ? when : null;
    }

    public boolean isClosed() {
        return status == CaseStatus.CLOSED;
    }
}
