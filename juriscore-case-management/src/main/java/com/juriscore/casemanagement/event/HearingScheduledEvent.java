package com.juriscore.casemanagement.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/** A matter was listed. */
@Getter
public class HearingScheduledEvent extends AbstractDomainEvent {

    private final UUID hearingId;
    private final UUID caseId;
    private final UUID courtId;
    private final Instant scheduledAt;

    public HearingScheduledEvent(UUID organizationId, UUID hearingId, UUID caseId, UUID courtId,
                                 Instant scheduledAt) {
        super(organizationId);
        this.hearingId = hearingId;
        this.caseId = caseId;
        this.courtId = courtId;
        this.scheduledAt = scheduledAt;
    }

    @Override
    public String eventType() {
        return "hearing.scheduled";
    }
}
