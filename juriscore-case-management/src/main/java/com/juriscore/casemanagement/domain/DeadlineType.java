package com.juriscore.casemanagement.domain;

/**
 * Who imposed the date.
 *
 * <p>Three values on purpose. Phase 3 does no statutory date arithmetic and knows no
 * jurisdiction's limitation rules, so a richer taxonomy here would be a claim the code
 * cannot back. "Who set this" is the distinction that changes how a firm treats a date,
 * and it survives translation. Mirrored by {@code ck_deadlines_type}.
 */
public enum DeadlineType {
    /** Imposed by a court: a direction, an order, a listing requirement. */
    COURT,
    /** Set by the firm for itself. */
    INTERNAL,
    OTHER
}
