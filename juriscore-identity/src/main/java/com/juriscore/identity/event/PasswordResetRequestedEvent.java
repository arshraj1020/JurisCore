package com.juriscore.identity.event;

import com.juriscore.common.event.AbstractDomainEvent;
import lombok.Getter;

import java.util.UUID;

/**
 * Carries the raw reset token, which exists nowhere else — only its hash is stored.
 * Consumers must not log it, and it must never cross into the audit trail.
 */
@Getter
public class PasswordResetRequestedEvent extends AbstractDomainEvent {

    private final UUID userId;
    private final String email;
    private final String fullName;
    private final String resetToken;

    public PasswordResetRequestedEvent(UUID organizationId, UUID userId, String email,
                                       String fullName, String resetToken) {
        super(organizationId);
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.resetToken = resetToken;
    }

    @Override
    public String eventType() {
        return "identity.password.reset_requested";
    }
}
