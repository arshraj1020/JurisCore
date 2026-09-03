package com.juriscore.casemanagement.domain;

/**
 * The tier a bench sits at.
 *
 * <p>Deliberately coarse. No PRD taxonomy of courts exists in this repository, and a
 * finer list would encode one jurisdiction's structure as if it were universal; these
 * five say how senior a court is, which is the distinction every jurisdiction has.
 * Widening the list is a one-line migration against {@code ck_courts_type}.
 */
public enum CourtType {
    SUPREME,
    HIGH,
    DISTRICT,
    TRIBUNAL,
    OTHER
}
