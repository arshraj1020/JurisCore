package com.juriscore.organization.domain;

import com.juriscore.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A law firm. This row <em>is</em> the tenant: every other table in the platform
 * carries its id, and no query may cross it.
 */
@Entity
@Table(name = "organizations", schema = "organization")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Organization extends BaseEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** URL-safe unique handle, e.g. {@code sharma-associates}. Immutable once issued. */
    @Column(name = "slug", nullable = false, unique = true, length = 120, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

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

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** IANA zone used to render hearing dates and to schedule deadline reminders. */
    @Column(name = "timezone", nullable = false, length = 64)
    @Builder.Default
    private String timezone = "Asia/Kolkata";

    /** Bar council / firm registration number, where applicable. */
    @Column(name = "registration_number", length = 120)
    private String registrationNumber;

    public boolean isActive() {
        return status == OrganizationStatus.ACTIVE;
    }
}
