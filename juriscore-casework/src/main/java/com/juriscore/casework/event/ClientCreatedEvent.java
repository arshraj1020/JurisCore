package com.juriscore.casework.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** A firm added a client. */
@Getter
public class ClientCreatedEvent extends AbstractDomainEvent {

    private final UUID clientId;
    private final String displayName;

    public ClientCreatedEvent(UUID organizationId, UUID clientId, String displayName) {
        super(organizationId);
        this.clientId = clientId;
        this.displayName = displayName;
    }

    @Override
    public String eventType() {
        return "client.created";
    }
}
