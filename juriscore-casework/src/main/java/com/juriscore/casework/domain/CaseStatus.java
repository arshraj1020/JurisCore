package com.juriscore.casework.domain;

/** Where a matter stands. Mirrored by {@code ck_cases_status} in V2. */
public enum CaseStatus {

    /** Opened, not yet being worked. Every case starts here. */
    OPEN,
    IN_PROGRESS,
    ON_HOLD,
    /** Terminal in Phase 2: a closed case cannot be reopened. */
    CLOSED;

    public boolean isTerminal() {
        return this == CLOSED;
    }
}
