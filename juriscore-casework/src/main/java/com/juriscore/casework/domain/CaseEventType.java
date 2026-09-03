package com.juriscore.casework.domain;

/** Timeline entry kinds. Mirrored by {@code ck_case_events_type} in V2. */
public enum CaseEventType {

    CASE_CREATED,
    LAWYER_ASSIGNED,
    LAWYER_UNASSIGNED,
    CASE_STATUS_CHANGED,
    /** The only kind a person writes directly. Still append-only. */
    MANUAL_NOTE
}
