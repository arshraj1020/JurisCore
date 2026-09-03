package com.juriscore.casework.domain;

/**
 * Timeline entry kinds. Mirrored by {@code ck_case_events_type}, widened in V3.
 *
 * <p>The list grew in Phase 3 rather than Phase 3 growing a timeline of its own. A
 * matter with two histories — one for the case, one for its hearings and tasks — is
 * worse than an enum that gains members, because the question a timeline answers is
 * "what happened on this matter", and that has one answer.
 *
 * <p>Adding a value here without the matching migration is a failed insert, not a silent
 * divergence, which is the intended way round.
 */
public enum CaseEventType {

    // ------------------------------------------------------------- Phase 2: the matter
    CASE_CREATED,
    LAWYER_ASSIGNED,
    LAWYER_UNASSIGNED,
    CASE_STATUS_CHANGED,
    /** The only kind a person writes directly. Still append-only. */
    MANUAL_NOTE,

    // ---------------------------------------------------------- Phase 3: what happened
    HEARING_SCHEDULED,
    HEARING_COMPLETED,
    HEARING_ADJOURNED,
    HEARING_CANCELLED,
    TASK_CREATED,
    TASK_COMPLETED,
    TASK_CANCELLED,
    DEADLINE_CREATED,
    DEADLINE_COMPLETED,
    DEADLINE_CANCELLED
}
