package com.juriscore.casemanagement.event;

import com.juriscore.casemanagement.domain.ReminderChannel;
import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** Somebody asked to be reminded. Exactly one of the two target ids is set. */
@Getter
public class ReminderScheduledEvent extends AbstractDomainEvent {

    private final UUID reminderId;
    private final UUID taskId;
    private final UUID deadlineId;
    private final Instant remindAt;
    private final ReminderChannel channel;

    public ReminderScheduledEvent(UUID organizationId, UUID reminderId, UUID taskId, UUID deadlineId,
                                  Instant remindAt, ReminderChannel channel) {
        super(organizationId);
        this.reminderId = reminderId;
        this.taskId = taskId;
        this.deadlineId = deadlineId;
        this.remindAt = remindAt;
        this.channel = channel;
    }

    @Override
    public String eventType() {
        return "reminder.scheduled";
    }
}
