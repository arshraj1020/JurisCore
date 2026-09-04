package com.juriscore.notifications.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every type belongs to exactly one category, which is what lets a user's four switches
 * cover everything without a table of exceptions.
 */
class NotificationTypeTest {

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    void everyTypeHasACategoryAndASeverity(NotificationType type) {
        assertThat(type.category()).isNotNull();
        assertThat(type.defaultSeverity()).isNotNull();
    }

    @Test
    @DisplayName("billing types land in the two categories a user would expect to find them in")
    void billingTypesAreCategorisedSensibly() {
        assertThat(NotificationType.INVOICE_ISSUED.category()).isEqualTo(NotificationCategory.INVOICE);
        assertThat(NotificationType.INVOICE_PAID.category()).isEqualTo(NotificationCategory.INVOICE);
        assertThat(NotificationType.INVOICE_OVERDUE.category()).isEqualTo(NotificationCategory.INVOICE);
        assertThat(NotificationType.INVOICE_CANCELLED.category()).isEqualTo(NotificationCategory.INVOICE);
        assertThat(NotificationType.PAYMENT_RECEIVED.category()).isEqualTo(NotificationCategory.PAYMENT);
    }

    @Test
    @DisplayName("an overdue invoice is a warning; a settled one is good news")
    void severitiesMatchTheirMeaning() {
        assertThat(NotificationType.INVOICE_OVERDUE.defaultSeverity())
                .isEqualTo(NotificationSeverity.WARNING);
        assertThat(NotificationType.INVOICE_PAID.defaultSeverity())
                .isEqualTo(NotificationSeverity.SUCCESS);
        assertThat(NotificationType.PAYMENT_RECEIVED.defaultSeverity())
                .isEqualTo(NotificationSeverity.SUCCESS);
        assertThat(NotificationType.INVOICE_ISSUED.defaultSeverity())
                .isEqualTo(NotificationSeverity.INFO);
    }

    @Test
    @DisplayName("the list stays short on purpose — a feed of everything is a feed nobody reads")
    void theListIsDeliberatelySmall() {
        assertThat(NotificationType.values()).hasSize(7);
    }
}
