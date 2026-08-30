package com.juriscore.common.security;

import java.util.Set;

/**
 * Platform roles (PRD §8). Authorities are stored with the {@code ROLE_} prefix so
 * {@code hasRole()} and {@code @PreAuthorize} work without extra mapping.
 */
public enum Role {

    /** Operates the platform itself; not scoped to any single firm. */
    SUPER_ADMIN,
    /** Owns one law firm's tenant: members, billing config, firm-wide visibility. */
    FIRM_ADMIN,
    /** Primary user: owns cases, hearings, documents, invoices. */
    LAWYER,
    /** Supports lawyers; may maintain case data but not perform administrative actions. */
    CLERK,
    /** External party; sees only what has been explicitly shared with them. */
    CLIENT;

    public static final String PREFIX = "ROLE_";

    /** Roles that belong to the firm's staff, as opposed to its clients. */
    public static final Set<Role> FIRM_STAFF = Set.of(FIRM_ADMIN, LAWYER, CLERK);

    public String authority() {
        return PREFIX + name();
    }

    public boolean isStaff() {
        return FIRM_STAFF.contains(this);
    }

    public boolean isPlatformAdmin() {
        return this == SUPER_ADMIN;
    }
}
