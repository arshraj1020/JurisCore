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

/**
 * A bench the firm appears before.
 *
 * <p>Firm-scoped reference data rather than a shared registry: two firms describe the
 * same building differently, and a shared table would need an owner nobody in this
 * product is.
 *
 * <p>Retirement is a flag, not a delete — the same shape as a soft-deleted client in
 * casework, and for the same reason. A hearing held in 2019 has to keep resolving to the
 * court that heard it.
 */
@Entity
@Table(name = "courts", schema = "case_management")
@Getter
@Setter
@NoArgsConstructor
public class Court extends TenantAwareEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "court_type", nullable = false, length = 32)
    private CourtType courtType;

    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(name = "city", length = 120)
    private String city;

    @Column(name = "state", length = 120)
    private String state;

    @Column(name = "country", length = 120)
    private String country;

    /** The zone hearing times are rendered in at the edge. Falls back to the firm's. */
    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
