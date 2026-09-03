package com.juriscore.casemanagement.api;

import com.juriscore.casemanagement.api.dto.CreateReminderRequest;
import com.juriscore.casemanagement.api.dto.ReminderResponse;
import com.juriscore.casemanagement.api.dto.UpdateReminderRequest;
import com.juriscore.casemanagement.domain.ReminderStatus;
import com.juriscore.casemanagement.service.ReminderService;
import com.juriscore.common.api.ApiResponse;
import com.juriscore.common.api.PageResponse;
import com.juriscore.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Reminders on tasks and deadlines.
 *
 * <p><strong>Nothing here delivers anything.</strong> A reminder is a row and a time;
 * when the time comes the sweep publishes {@code reminder.triggered} and marks the row
 * SENT. There is no email, SMS or push in this platform, and a status called SENT is
 * exactly the sort of thing a later reader takes at face value, so it is said plainly
 * here, on the response schema, and on the database column.
 *
 * <p>Open to all staff: setting a reminder for yourself is not an administrative act.
 * {@code CLIENT} and {@code SUPER_ADMIN} are refused, as everywhere else in this module.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Reminders", description = "Scheduled reminders on tasks and deadlines. Phase 3 "
        + "publishes an event when one comes due; it does not deliver messages.")
public class ReminderController {

    private final ReminderService reminderService;

    @PostMapping("/api/v1/tasks/{taskId}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Set a reminder on a task",
            description = "remindAt must be in the future. Refused if the task is already "
                    + "completed or cancelled.")
    public ApiResponse<ReminderResponse> forTask(@PathVariable UUID taskId,
                                                 @Valid @RequestBody CreateReminderRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(ReminderResponse.from(
                        reminderService.scheduleForTask(taskId, organizationId, request)),
                "Reminder scheduled successfully");
    }

    @PostMapping("/api/v1/deadlines/{deadlineId}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Set a reminder on a deadline",
            description = "remindAt must be in the future. Refused if the deadline is no longer open.")
    public ApiResponse<ReminderResponse> forDeadline(
            @PathVariable UUID deadlineId,
            @Valid @RequestBody CreateReminderRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(ReminderResponse.from(
                        reminderService.scheduleForDeadline(deadlineId, organizationId, request)),
                "Reminder scheduled successfully");
    }

    @GetMapping("/api/v1/reminders")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List your firm's reminders")
    public ApiResponse<PageResponse<ReminderResponse>> list(
            @RequestParam(required = false) ReminderStatus status,
            @PageableDefault(size = 20, sort = "remindAt") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                reminderService.list(organizationId, status, pageable), ReminderResponse::from));
    }

    @GetMapping("/api/v1/reminders/{reminderId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one reminder")
    public ApiResponse<ReminderResponse> byId(@PathVariable UUID reminderId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                ReminderResponse.from(reminderService.getScoped(reminderId, organizationId)));
    }

    @PutMapping("/api/v1/reminders/{reminderId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Reschedule a reminder",
            description = "Only while it is still SCHEDULED — one that has already fired cannot "
                    + "be edited. The target cannot move. Send the version you last read.")
    public ApiResponse<ReminderResponse> update(@PathVariable UUID reminderId,
                                                @Valid @RequestBody UpdateReminderRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                ReminderResponse.from(reminderService.update(reminderId, organizationId, request)),
                "Reminder updated successfully");
    }

    @DeleteMapping("/api/v1/reminders/{reminderId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Call a reminder off",
            description = "Cancellation, not deletion, so that 'this fired' and 'somebody stopped "
                    + "it' stay distinguishable afterwards. Cancelling twice is a no-op.")
    public ApiResponse<ReminderResponse> cancel(@PathVariable UUID reminderId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(ReminderResponse.from(reminderService.cancel(reminderId, organizationId)),
                "Reminder cancelled successfully");
    }
}
