package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** A date the matter has to meet was recorded. */
@Getter
public class DeadlineCreatedEvent extends AbstractDomainEvent {

    private final UUID deadlineId;
    private final UUID caseId;
    private final String title;
    private final Instant dueAt;

    public DeadlineCreatedEvent(UUID organizationId, UUID deadlineId, UUID caseId, String title,
                                Instant dueAt) {
        super(organizationId);
        this.deadlineId = deadlineId;
        this.caseId = caseId;
        this.title = title;
        this.dueAt = dueAt;
    }

    @Override
    public String eventType() {
        return "deadline.created";
    }
}
