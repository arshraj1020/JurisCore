package com.juriscore.notifications.domain;

/**
 * The switches a user actually gets.
 *
 * <p>Four, not one per notification type. A person who does not want to hear about billing
 * wants to say that once, not tick six boxes and then miss the seventh when it is added.
 * Every {@link NotificationType} maps to exactly one of these.
 */
public enum NotificationCategory {
    INVOICE,
    PAYMENT,
    CASE,
    SYSTEM
}
