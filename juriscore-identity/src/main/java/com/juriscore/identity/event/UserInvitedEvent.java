package com.juriscore.identity.event;

import com.juriscore.common.event.AbstractDomainEvent;
import com.juriscore.common.security.Role;
import lombok.Getter;

import java.util.UUID;

/**
 * A firm admin added someone. Carries the one-time activation token so the
 * notification module can build the "set your password" link without querying identity.
 */
@Getter
public class UserInvitedEvent extends AbstractDomainEvent {

    private final UUID userId;
    private final String email;
    private final String fullName;
    private final Role role;
    private final String activationToken;

    public UserInvitedEvent(UUID organizationId, UUID userId, String email, String fullName,
                            Role role, String activationToken) {
        super(organizationId);
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.activationToken = activationToken;
    }

    @Override
    public String eventType() {
        return "identity.user.invited";
    }
}
