package com.juriscore.casemanagement.domain;

/**
 * Mirrored by {@code ck_reminders_status}.
 *
 * <p>{@link #SENT} is the one to read carefully: it means the reminder was published as
 * a {@code reminder.triggered} domain event, not that anything reached a person. Phase 3
 * has no email, SMS or push, and naming the state after a delivery that does not happen
 * is how a later reader ends up believing one did.
 */
public enum ReminderStatus {

    SCHEDULED,
    /** Handed to the event bus. Not delivered — nothing in this system delivers. */
    SENT,
    CANCELLED;

    public boolean isTerminal() {
        return this != SCHEDULED;
    }
}
