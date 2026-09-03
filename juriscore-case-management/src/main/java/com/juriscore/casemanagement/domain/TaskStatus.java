package com.juriscore.casemanagement.domain;

/** Where a piece of work stands. Mirrored by {@code ck_tasks_status}. */
public enum TaskStatus {

    TODO,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
