package com.juriscore.notifications.domain;

/** How loudly a client should present the notification. Presentation only; no behaviour hangs off it. */
public enum NotificationSeverity {
    INFO,
    SUCCESS,
    WARNING,
    CRITICAL
}
