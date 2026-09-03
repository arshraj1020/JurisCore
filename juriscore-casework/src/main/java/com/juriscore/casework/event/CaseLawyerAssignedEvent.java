package com.juriscore.casework.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A lawyer was put on a matter. {@code lead} says whether they took the lead with it. */
@Getter
public class CaseLawyerAssignedEvent extends AbstractDomainEvent {

    private final UUID caseId;
    private final String caseNumber;
    private final UUID lawyerUserId;
    private final boolean lead;

    public CaseLawyerAssignedEvent(UUID organizationId, UUID caseId, String caseNumber,
                                   UUID lawyerUserId, boolean lead) {
        super(organizationId);
        this.caseId = caseId;
        this.caseNumber = caseNumber;
        this.lawyerUserId = lawyerUserId;
        this.lead = lead;
    }

    @Override
    public String eventType() {
        return "case.lawyer_assigned";
    }
}
