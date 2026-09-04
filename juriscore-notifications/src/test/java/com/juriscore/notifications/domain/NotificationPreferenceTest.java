package com.juriscore.notifications.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPreferenceTest {

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    @DisplayName("everything is on until somebody turns it off")
    void defaultsToEnabled(NotificationCategory category) {
        assertThat(new NotificationPreference().allows(category)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(NotificationCategory.class)
    void eachSwitchIsIndependent(NotificationCategory category) {
        NotificationPreference preference = new NotificationPreference();
        preference.set(category, false);

        assertThat(preference.allows(category)).isFalse();
        for (NotificationCategory other : NotificationCategory.values()) {
            if (other != category) {
                assertThat(preference.allows(other))
                        .as("turning %s off must not touch %s", category, other)
                        .isTrue();
            }
        }
    }

    @Test
    void switchesBackOn() {
        NotificationPreference preference = new NotificationPreference();
        preference.set(NotificationCategory.INVOICE, false);
        preference.set(NotificationCategory.INVOICE, true);

        assertThat(preference.allows(NotificationCategory.INVOICE)).isTrue();
    }

    @Test
    @DisplayName("read_at is the only thing about a notification that ever changes")
    void markingReadIsIdempotent() {
        Notification notification = new Notification();
        assertThat(notification.isRead()).isFalse();

        Instant first = Instant.parse("2026-03-15T10:00:00Z");
        notification.markRead(first);
        notification.markRead(Instant.parse("2026-03-16T10:00:00Z"));

        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt())
                .as("a second click must not restamp when it was read")
                .isEqualTo(first);
    }
}
