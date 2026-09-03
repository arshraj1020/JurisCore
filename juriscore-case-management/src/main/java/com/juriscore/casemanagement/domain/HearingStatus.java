package com.juriscore.casemanagement.domain;

/** Where a listing stands. Mirrored by {@code ck_hearings_status}. */
public enum HearingStatus {

    SCHEDULED,
    /** Terminal: the hearing happened. */
    COMPLETED,
    /** Put off. Can be relisted, which is what separates it from CANCELLED. */
    ADJOURNED,
    /** Terminal: the hearing will not happen. */
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
