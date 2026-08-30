package com.juriscore.identity.domain;

public enum UserStatus {
    /** Invited but has not set a password yet. */
    INVITED,
    ACTIVE,
    /** Temporarily blocked by a firm admin. */
    SUSPENDED,
    /** Left the firm. Retained so audit rows keep resolving to a name. */
    DEACTIVATED
}
