package com.juriscore.casemanagement.api.dto;

import com.juriscore.casemanagement.domain.Reminder;
import com.juriscore.casemanagement.domain.ReminderChannel;
import com.juriscore.casemanagement.domain.ReminderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "A scheduled reminder. status=SENT means the reminder was published "
        + "as a reminder.triggered domain event, not that a message reached anybody.")
public record ReminderResponse(
        UUID id,
        UUID taskId,
        UUID deadlineId,
        Instant remindAt,
        ReminderStatus status,
        ReminderChannel channel,
        String note,
        Instant triggeredAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static ReminderResponse from(Reminder reminder) {
        return new ReminderResponse(reminder.getId(), reminder.getTaskId(), reminder.getDeadlineId(),
                reminder.getRemindAt(), reminder.getStatus(), reminder.getChannel(),
                reminder.getNote(), reminder.getTriggeredAt(), reminder.getCreatedAt(),
                reminder.getUpdatedAt(), reminder.getVersion());
    }
}
