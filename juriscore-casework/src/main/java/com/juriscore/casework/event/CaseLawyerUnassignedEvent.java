package com.juriscore.casework.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * A lawyer came off a matter. {@code newLeadUserId} is set only when the departing
 * lawyer was lead and another was promoted in the same transaction.
 */
@Getter
public class CaseLawyerUnassignedEvent extends AbstractDomainEvent {

    private final UUID caseId;
    private final String caseNumber;
    private final UUID lawyerUserId;
    private final UUID newLeadUserId;

    public CaseLawyerUnassignedEvent(UUID organizationId, UUID caseId, String caseNumber,
                                     UUID lawyerUserId, UUID newLeadUserId) {
        super(organizationId);
        this.caseId = caseId;
        this.caseNumber = caseNumber;
        this.lawyerUserId = lawyerUserId;
        this.newLeadUserId = newLeadUserId;
    }

    @Override
    public String eventType() {
        return "case.lawyer_unassigned";
    }
}
