package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** Work on a matter was finished. Cancellation is not this event — it is not completion. */
@Getter
public class TaskCompletedEvent extends AbstractDomainEvent {

    private final UUID taskId;
    private final UUID caseId;
    private final String title;
    private final UUID assignedToUserId;

    public TaskCompletedEvent(UUID organizationId, UUID taskId, UUID caseId, String title,
                              UUID assignedToUserId) {
        super(organizationId);
        this.taskId = taskId;
        this.caseId = caseId;
        this.title = title;
        this.assignedToUserId = assignedToUserId;
    }

    @Override
    public String eventType() {
        return "task.completed";
    }
}
