package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A date was met. */
@Getter
public class DeadlineCompletedEvent extends AbstractDomainEvent {

    private final UUID deadlineId;
    private final UUID caseId;
    private final String title;

    public DeadlineCompletedEvent(UUID organizationId, UUID deadlineId, UUID caseId, String title) {
        super(organizationId);
        this.deadlineId = deadlineId;
        this.caseId = caseId;
        this.title = title;
    }

    @Override
    public String eventType() {
        return "deadline.completed";
    }
}
