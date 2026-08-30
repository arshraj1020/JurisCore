package com.juriscore.identity.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/** Every password change, whether self-service or via a reset link. Always notify. */
@Getter
public class PasswordChangedEvent extends AbstractDomainEvent {

    private final UUID userId;
    private final String email;
    private final boolean viaResetLink;

    public PasswordChangedEvent(UUID organizationId, UUID userId, String email, boolean viaResetLink) {
        super(organizationId);
        this.userId = userId;
        this.email = email;
        this.viaResetLink = viaResetLink;
    }

    @Override
    public String eventType() {
        return "identity.password.changed";
    }
}
