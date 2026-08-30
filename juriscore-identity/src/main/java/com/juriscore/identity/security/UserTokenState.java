package com.juriscore.identity.security;

import com.juriscore.identity.domain.UserStatus;

/**
 * The two facts the authentication filter needs beyond the token itself:
 * is this account still usable, and is the token from the current generation.
 */
public record UserTokenState(int tokenGeneration, UserStatus status) {

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
