package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** Work was put on a matter. {@code assignedToUserId} is null when nobody has it yet. */
@Getter
public class TaskCreatedEvent extends AbstractDomainEvent {

    private final UUID taskId;
    private final UUID caseId;
    private final String title;
    private final UUID assignedToUserId;

    public TaskCreatedEvent(UUID organizationId, UUID taskId, UUID caseId, String title,
                            UUID assignedToUserId) {
        super(organizationId);
        this.taskId = taskId;
        this.caseId = caseId;
        this.title = title;
        this.assignedToUserId = assignedToUserId;
    }

    @Override
    public String eventType() {
        return "task.created";
    }
}
