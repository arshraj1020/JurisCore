package com.juriscore.notifications.api;

import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import com.juriscore.notifications.api.dto.NotificationResponse;
import com.juriscore.notifications.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * A user's own notifications.
 *
 * <p><strong>There is no path parameter for a user.</strong> Every method takes the
 * recipient from {@code CurrentUser.requireUserId()}, so "read somebody else's
 * notifications" is not an endpoint that exists to be authorized — it is a request that
 * cannot be expressed. That is a stronger guarantee than an ownership check, and it is why
 * these methods need no role beyond being signed in and scoped to a firm.
 *
 * <p>{@code isAuthenticated()} rather than a role list, deliberately. Notifications are
 * addressed to a person, not granted to a job: a CLIENT user who is ever sent one should be
 * able to read it, and a role list here would be a second place to keep in step with
 * whatever the notification producers decide. What a person can be told is decided where
 * the notification is raised, not here.
 *
 * <p>{@code SUPER_ADMIN} is refused by {@code requireOrganizationId()} before any handler
 * body runs, like everywhere else in the tenant API.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app messages for the signed-in user")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/api/v1/notifications")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Your notifications, newest first",
            description = "Pass unread=true for only the ones you have not read. Ordering is "
                    + "newest first with the id as a tiebreak, so paging is stable.")
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @RequestParam(defaultValue = "false") boolean unread,
            @PageableDefault(size = 20) Pageable pageable) {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                notificationService.list(userId, organizationId, unread, pageable),
                NotificationResponse::from));
    }

    @GetMapping("/api/v1/notifications/unread-count")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "How many you have not read",
            description = "So a client can render a badge without paging the whole list.")
    public ApiResponse<Map<String, Long>> unreadCount() {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(Map.of("unread",
                notificationService.unreadCount(userId, organizationId)));
    }

    @GetMapping("/api/v1/notifications/{notificationId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One of your notifications",
            description = "A colleague's answers not found, exactly as another firm's does.")
    public ApiResponse<NotificationResponse> byId(@PathVariable UUID notificationId) {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(NotificationResponse.from(
                notificationService.require(notificationId, userId, organizationId)));
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark one as read",
            description = "Idempotent: marking an already-read notification does not restamp it.")
    public ApiResponse<NotificationResponse> markRead(@PathVariable UUID notificationId) {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(NotificationResponse.from(
                notificationService.markRead(notificationId, userId, organizationId)));
    }

    @PostMapping("/api/v1/notifications/read-all")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Mark everything as read")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(Map.of("marked",
                notificationService.markAllRead(userId, organizationId)));
    }

    @DeleteMapping("/api/v1/notifications/{notificationId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Dismiss one of your notifications",
            description = "A real delete. A notification is a message to you and references "
                    + "nothing; the audit trail, which does keep history, has no delete at all.")
    public ApiResponse<Void> delete(@PathVariable UUID notificationId) {
        UUID userId = CurrentUser.requireUserId();
        UUID organizationId = CurrentUser.requireOrganizationId();
        notificationService.delete(notificationId, userId, organizationId);
        return ApiResponse.message("Notification dismissed");
    }
}
