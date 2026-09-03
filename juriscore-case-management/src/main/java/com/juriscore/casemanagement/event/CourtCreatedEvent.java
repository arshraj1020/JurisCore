package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A firm added a court to its list. */
@Getter
public class CourtCreatedEvent extends AbstractDomainEvent {

    private final UUID courtId;
    private final String name;

    public CourtCreatedEvent(UUID organizationId, UUID courtId, String name) {
        super(organizationId);
        this.courtId = courtId;
        this.name = name;
    }

    @Override
    public String eventType() {
        return "court.created";
    }
}
