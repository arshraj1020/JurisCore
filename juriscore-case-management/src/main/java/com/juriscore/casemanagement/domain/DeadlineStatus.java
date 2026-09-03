package com.juriscore.casemanagement.domain;

/** Mirrored by {@code ck_deadlines_status}. */
public enum DeadlineStatus {

    OPEN,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this != OPEN;
    }
}
