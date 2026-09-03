package com.juriscore.casework.service;

import java.util.UUID;

/**
 * Casework's one question for the identity module: may this user be put on a case?
 *
 * <p>A port rather than a direct call, for two reasons. It keeps identity's types at a
 * single boundary class, so the rest of casework never imports {@code User}; and it
 * lets the assignment rules be unit-tested without standing up identity.
 */
public interface LawyerDirectory {

    /**
     * Passes silently when {@code userId} is an ACTIVE user with role LAWYER in
     * {@code organizationId}.
     *
     * @throws com.juriscore.common.error.ApiException {@code USER_NOT_FOUND} (404) when
     *         no such user exists in that organization — including when the user exists
     *         in a different one, which must not be distinguishable; or
     *         {@code INVALID_ARGUMENT} (400) when the user is in this firm but is not an
     *         active lawyer.
     */
    void requireAssignableLawyer(UUID userId, UUID organizationId);
}
