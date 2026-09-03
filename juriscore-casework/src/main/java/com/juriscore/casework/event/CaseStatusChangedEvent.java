package com.juriscore.casework.event;

import com.juriscore.casework.domain.CaseStatus;
import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A matter moved through its lifecycle. */
@Getter
public class CaseStatusChangedEvent extends AbstractDomainEvent {

    private final UUID caseId;
    private final String caseNumber;
    private final CaseStatus previousStatus;
    private final CaseStatus newStatus;

    public CaseStatusChangedEvent(UUID organizationId, UUID caseId, String caseNumber,
                                  CaseStatus previousStatus, CaseStatus newStatus) {
        super(organizationId);
        this.caseId = caseId;
        this.caseNumber = caseNumber;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
    }

    @Override
    public String eventType() {
        return "case.status_changed";
    }
}
