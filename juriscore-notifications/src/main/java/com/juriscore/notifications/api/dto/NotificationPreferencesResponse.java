package com.juriscore.notifications.api.dto;

import com.juriscore.notifications.domain.NotificationPreference;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Your own notification switches. Everything is on until you turn it off.")
public record NotificationPreferencesResponse(
        @Schema(description = "Invoices issued, paid, cancelled or overdue.")
        boolean invoice,
        @Schema(description = "Payments recorded against your firm's invoices.")
        boolean payment,
        @Schema(description = "Matter activity.")
        boolean caseUpdates,
        @Schema(description = "Platform messages.")
        boolean system,
        @Schema(description = "Null until you have saved these settings at least once.")
        Long version) {

    public static NotificationPreferencesResponse from(NotificationPreference preference) {
        return new NotificationPreferencesResponse(
                preference.isInvoiceEnabled(),
                preference.isPaymentEnabled(),
                preference.isCaseEnabled(),
                preference.isSystemEnabled(),
                preference.getId() == null ? null : preference.getVersion());
    }
}
