package com.juriscore.casemanagement.event;

import com.juriscore.casemanagement.domain.HearingStatus;
import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A listing was heard, put off or called off. */
@Getter
public class HearingStatusChangedEvent extends AbstractDomainEvent {

    private final UUID hearingId;
    private final UUID caseId;
    private final HearingStatus previousStatus;
    private final HearingStatus newStatus;

    public HearingStatusChangedEvent(UUID organizationId, UUID hearingId, UUID caseId,
                                     HearingStatus previousStatus, HearingStatus newStatus) {
        super(organizationId);
        this.hearingId = hearingId;
        this.caseId = caseId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }

    @Override
    public String eventType() {
        return "hearing.status_changed";
    }
}
