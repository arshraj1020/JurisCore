package com.juriscore.casework.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A matter was opened. */
@Getter
public class CaseCreatedEvent extends AbstractDomainEvent {

    private final UUID caseId;
    private final String caseNumber;
    private final UUID clientId;

    public CaseCreatedEvent(UUID organizationId, UUID caseId, String caseNumber, UUID clientId) {
        super(organizationId);
        this.caseId = caseId;
        this.caseNumber = caseNumber;
        this.clientId = clientId;
    }

    @Override
    public String eventType() {
        return "case.created";
    }
}
