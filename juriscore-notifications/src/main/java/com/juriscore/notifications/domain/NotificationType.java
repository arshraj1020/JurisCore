package com.juriscore.notifications.domain;

/**
 * What happened, in the notification's own vocabulary.
 *
 * <p>A deliberately short list. The platform publishes more than twenty domain events and
 * only a handful of them are worth interrupting somebody over — a notification for every
 * event is a notification feed nobody reads, which is worse than none. The mapping from
 * domain event to notification is explicit and lives in one place
 * ({@code BillingNotificationListener}); adding a type here does nothing on its own.
 *
 * <p>Each type belongs to exactly one {@link NotificationCategory}, so a user's four
 * switches cover everything without a table of exceptions.
 */
public enum NotificationType {

    INVOICE_ISSUED(NotificationCategory.INVOICE, NotificationSeverity.INFO),
    INVOICE_PAID(NotificationCategory.INVOICE, NotificationSeverity.SUCCESS),
    INVOICE_OVERDUE(NotificationCategory.INVOICE, NotificationSeverity.WARNING),
    INVOICE_CANCELLED(NotificationCategory.INVOICE, NotificationSeverity.INFO),
    PAYMENT_RECEIVED(NotificationCategory.PAYMENT, NotificationSeverity.SUCCESS),
    CASE_ASSIGNED(NotificationCategory.CASE, NotificationSeverity.INFO),
    SYSTEM_MESSAGE(NotificationCategory.SYSTEM, NotificationSeverity.INFO);

    private final NotificationCategory category;
    private final NotificationSeverity defaultSeverity;

    NotificationType(NotificationCategory category, NotificationSeverity defaultSeverity) {
        this.category = category;
        this.defaultSeverity = defaultSeverity;
    }

    public NotificationCategory category() {
        return category;
    }

    public NotificationSeverity defaultSeverity() {
        return defaultSeverity;
    }
}
