package com.juriscore.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Base class for anything that belongs to exactly one law firm.
 *
 * <p>Tenant isolation in JurisCore is enforced in three places, deliberately
 * redundantly: this column, a mandatory {@code organizationId} predicate in every
 * repository query, and a check in {@code TenantGuard} before a resource is returned.
 * A single missed {@code WHERE} clause would otherwise expose one firm's case files
 * to another.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class TenantAwareEntity extends BaseEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;
}
