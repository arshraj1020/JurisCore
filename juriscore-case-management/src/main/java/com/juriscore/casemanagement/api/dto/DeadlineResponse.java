package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.Deadline;
import com.juriscore.casemanagement.domain.DeadlineStatus;
import com.juriscore.casemanagement.domain.DeadlineType;

import java.time.Instant;
import java.util.UUID;

public record DeadlineResponse(
        UUID id,
        UUID caseId,
        String title,
        String description,
        DeadlineType deadlineType,
        Instant dueAt,
        DeadlineStatus status,
        Instant completedAt,
        String source,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static DeadlineResponse from(Deadline deadline) {
        return new DeadlineResponse(deadline.getId(), deadline.getCaseId(), deadline.getTitle(),
                deadline.getDescription(), deadline.getDeadlineType(), deadline.getDueAt(),
                deadline.getStatus(), deadline.getCompletedAt(), deadline.getSource(),
                deadline.getDeletedAt(), deadline.getCreatedAt(), deadline.getUpdatedAt(),
                deadline.getVersion());
    }
}
