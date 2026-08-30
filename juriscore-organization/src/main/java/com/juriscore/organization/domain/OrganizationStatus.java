package com.juriscore.organization.domain;

public enum OrganizationStatus {
    /** Normal operation. */
    ACTIVE,
    /** Billing or compliance hold: reads allowed, writes blocked. */
    SUSPENDED,
    /** Closed by the firm or the platform; retained for audit only. */
    CLOSED
}
