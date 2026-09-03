package com.juriscore.casemanagement.event;

import com.juriscore.casemanagement.domain.ReminderChannel;
import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A reminder came due and was announced.
 *
 * <p>The handover point, and the end of Phase 3's responsibility. Nothing consumes this
 * yet; the consumer that turns it into an email or a push is Phase 5's, and
 * {@link #channel} is what it will read to decide how. Publishing this event is the whole
 * meaning of a reminder being "sent".
 */
@Getter
public class ReminderTriggeredEvent extends AbstractDomainEvent {

    private final UUID reminderId;
    private final UUID taskId;
    private final UUID deadlineId;
    private final ReminderChannel channel;
    private final Instant scheduledFor;

    public ReminderTriggeredEvent(UUID organizationId, UUID reminderId, UUID taskId, UUID deadlineId,
                                  ReminderChannel channel, Instant scheduledFor) {
        super(organizationId);
        this.reminderId = reminderId;
        this.taskId = taskId;
        this.deadlineId = deadlineId;
        this.channel = channel;
        this.scheduledFor = scheduledFor;
    }

    @Override
    public String eventType() {
        return "reminder.triggered";
    }
}
