package com.juriscore.identity.event;

import com.juriscore.common.event.AbstractDomainEvent;
import com.juriscore.common.security.Role;
import lombok.Getter;

import java.util.UUID;

/** A firm signed up, or a member accepted an invitation. */
@Getter
public class UserRegisteredEvent extends AbstractDomainEvent {

    private final UUID userId;
    private final String email;
    private final String fullName;
    private final Role role;
    private final boolean newOrganization;

    public UserRegisteredEvent(UUID organizationId, UUID userId, String email, String fullName,
                               Role role, boolean newOrganization) {
        super(organizationId);
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.newOrganization = newOrganization;
    }

    @Override
    public String eventType() {
        return "identity.user.registered";
    }
}
