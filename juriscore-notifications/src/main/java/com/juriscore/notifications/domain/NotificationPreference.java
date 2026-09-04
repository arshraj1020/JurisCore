package com.juriscore.notifications.domain;

import com.juriscore.common.domain.TenantAwareEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One user's four switches.
 *
 * <p>Belongs to the user, not the firm. An administrator cannot mute a colleague's
 * notifications and cannot read their settings: the row is keyed by {@code user_id} and
 * every path to it comes from {@code CurrentUser.requireUserId()}, so there is no endpoint
 * that takes somebody else's id. The {@code organizationId} is carried for tenant scoping
 * and for cleanup, not to make the preference the firm's.
 *
 * <p>A user with no row has everything enabled. That is the product default, and
 * representing it by absence means a firm's users cost nothing until one of them actually
 * changes something.
 */
@Entity
@Table(name = "notification_preferences", schema = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference extends TenantAwareEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "invoice_enabled", nullable = false)
    private boolean invoiceEnabled = true;

    @Column(name = "payment_enabled", nullable = false)
    private boolean paymentEnabled = true;

    @Column(name = "case_enabled", nullable = false)
    private boolean caseEnabled = true;

    @Column(name = "system_enabled", nullable = false)
    private boolean systemEnabled = true;

    public boolean allows(NotificationCategory category) {
        return switch (category) {
            case INVOICE -> invoiceEnabled;
            case PAYMENT -> paymentEnabled;
            case CASE -> caseEnabled;
            case SYSTEM -> systemEnabled;
        };
    }

    public void set(NotificationCategory category, boolean enabled) {
        switch (category) {
            case INVOICE -> invoiceEnabled = enabled;
            case PAYMENT -> paymentEnabled = enabled;
            case CASE -> caseEnabled = enabled;
            case SYSTEM -> systemEnabled = enabled;
        }
    }
}
