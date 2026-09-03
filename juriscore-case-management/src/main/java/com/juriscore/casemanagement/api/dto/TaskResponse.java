package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.Task;
import com.juriscore.casemanagement.domain.TaskPriority;
import com.juriscore.casemanagement.domain.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID caseId,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        UUID assignedToUserId,
        Instant dueAt,
        Instant completedAt,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getCaseId(), task.getTitle(),
                task.getDescription(), task.getStatus(), task.getPriority(),
                task.getAssignedToUserId(), task.getDueAt(), task.getCompletedAt(),
                task.getDeletedAt(), task.getCreatedAt(), task.getUpdatedAt(), task.getVersion());
    }
}
