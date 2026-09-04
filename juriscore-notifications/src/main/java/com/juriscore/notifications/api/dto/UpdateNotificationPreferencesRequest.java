package com.juriscore.notifications.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A partial edit: send only the switches you want to change.
 *
 * <p>PATCH rather than PUT on purpose. A client that only wants to mute billing should not
 * have to send the other three and risk resetting a setting added after it was written.
 *
 * <p><strong>No {@code version}, unlike every other update in the platform.</strong>
 * Optimistic locking exists to stop two people overwriting each other, and this row has
 * exactly one possible editor: there is no endpoint that takes another user's id, so a
 * lost update would need one person editing their own four switches from two tabs at
 * once. Making them resolve a 409 for that is a cost with no benefit. The response still
 * reports the row's version, because it is the row's version.
 */
@Schema(description = "Turn notification categories on or off. Omitted switches are left alone.")
public record UpdateNotificationPreferencesRequest(
        Boolean invoice,
        Boolean payment,
        Boolean caseUpdates,
        Boolean system) {
}
