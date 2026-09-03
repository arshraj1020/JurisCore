package com.juriscore.casemanagement.domain;

/**
 * How the firm intends the reminder to reach somebody, recorded for the Phase 5 consumer
 * that will one day act on it.
 *
 * <p>A statement of intent, not of capability: Phase 3 stores this value and publishes
 * it on the domain event, and there is no code anywhere in the platform that sends an
 * email. Mirrored by {@code ck_reminders_channel}.
 */
public enum ReminderChannel {
    IN_APP,
    EMAIL
}
