package com.juriscore.notifications.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.notifications.api.dto.NotificationPreferencesResponse;
import com.juriscore.notifications.api.dto.UpdateNotificationPreferencesRequest;
import com.juriscore.notifications.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A user's own notification switches.
 *
 * <p>Same shape as {@code NotificationController} and for the same reason: there is no path
 * parameter for a user, so "change somebody else's preferences" is not an endpoint that
 * exists. An administrator cannot mute a colleague's overdue-invoice alerts, and cannot
 * read what they have chosen.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Notification preferences", description = "Which categories you want to hear about")
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    @GetMapping("/api/v1/notification-preferences")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Your notification switches",
            description = "Everything is on until you turn something off; the version is null "
                    + "until you have saved these at least once.")
    public ApiResponse<NotificationPreferencesResponse> current() {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(NotificationPreferencesResponse.from(
                preferenceService.effectiveFor(userId, organizationId)));
    }

    @PatchMapping("/api/v1/notification-preferences")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change your notification switches",
            description = "Send only what you want to change; omitted switches are left alone.")
    public ApiResponse<NotificationPreferencesResponse> update(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                NotificationPreferencesResponse.from(
                        preferenceService.update(userId, organizationId, request)),
                "Notification preferences updated successfully");
    }
}
