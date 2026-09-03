package com.juriscore.casemanagement.api;

import com.juriscore.casemanagement.api.dto.ChangeTaskStatusRequest;
import com.juriscore.casemanagement.api.dto.CreateTaskRequest;
import com.juriscore.casemanagement.api.dto.TaskResponse;
import com.juriscore.casemanagement.api.dto.UpdateTaskRequest;
import com.juriscore.casemanagement.domain.TaskStatus;
import com.juriscore.casemanagement.service.TaskService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Work on matters.
 *
 * <p>Creating, reading, editing and moving a task are open to all staff — a clerk chasing
 * a filing is the ordinary case. Removal is an administrator's, following the one
 * destructive verb casework already has: only {@code FIRM_ADMIN} deletes a client, so
 * only {@code FIRM_ADMIN} removes a task. A lawyer or clerk who wants a task gone marks
 * it CANCELLED, which is what the state is for and leaves the matter's history intact.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Tasks", description = "Work items on a matter")
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/api/v1/cases/{caseId}/tasks")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "List the tasks on a matter", description = "Removed tasks are excluded.")
    public ApiResponse<PageResponse<TaskResponse>> listForCase(
            @PathVariable UUID caseId,
            @RequestParam(required = false) TaskStatus status,
            @PageableDefault(size = 20, sort = "dueAt") Pageable pageable) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(PageResponse.from(
                taskService.listForCase(caseId, organizationId, status, pageable),
                TaskResponse::from));
    }

    @PostMapping("/api/v1/cases/{caseId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Put a task on a matter",
            description = "Any assignee must be an active member of your firm's staff. "
                    + "Writes a TASK_CREATED entry to the case timeline.")
    public ApiResponse<TaskResponse> create(@PathVariable UUID caseId,
                                            @Valid @RequestBody CreateTaskRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(TaskResponse.from(taskService.create(caseId, organizationId, request)),
                "Task created successfully");
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Fetch one task",
            description = "Returns removed tasks too, so a timeline entry naming one still resolves.")
    public ApiResponse<TaskResponse> byId(@PathVariable UUID taskId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(TaskResponse.from(taskService.getScoped(taskId, organizationId)));
    }

    @PutMapping("/api/v1/tasks/{taskId}")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Edit a task",
            description = "Status cannot be changed here. Send the version you last read.")
    public ApiResponse<TaskResponse> update(@PathVariable UUID taskId,
                                            @Valid @RequestBody UpdateTaskRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                TaskResponse.from(taskService.update(taskId, organizationId, request)),
                "Task updated successfully");
    }

    @PatchMapping("/api/v1/tasks/{taskId}/status")
    @PreAuthorize("hasAnyRole('FIRM_ADMIN', 'LAWYER', 'CLERK')")
    @Operation(summary = "Move a task",
            description = "TODO and IN_PROGRESS move freely between themselves; COMPLETED and "
                    + "CANCELLED are terminal. Only those two endings reach the case timeline.")
    public ApiResponse<TaskResponse> changeStatus(@PathVariable UUID taskId,
                                                  @Valid @RequestBody ChangeTaskStatusRequest request) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(
                TaskResponse.from(taskService.changeStatus(taskId, organizationId, request.status())),
                "Task status updated successfully");
    }

    @DeleteMapping("/api/v1/tasks/{taskId}")
    @PreAuthorize("hasRole('FIRM_ADMIN')")
    @Operation(summary = "Remove a task",
            description = "Soft removal: the row stays so the matter's timeline keeps resolving. "
                    + "To close out work instead, move it to COMPLETED or CANCELLED.")
    public ApiResponse<TaskResponse> remove(@PathVariable UUID taskId) {
        UUID organizationId = CurrentUser.requireOrganizationId();
        return ApiResponse.ok(TaskResponse.from(taskService.remove(taskId, organizationId)),
                "Task removed successfully");
    }
}
