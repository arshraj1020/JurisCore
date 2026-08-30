package com.juriscore.common.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the caller out of the Spring Security context.
 *
 * <p>This is the one place that knows how the principal is stored, so the
 * representation can change without touching services.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthenticatedUser> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof AuthenticatedUser user ? Optional.of(user) : Optional.empty();
    }

    /** The caller, or 401 if the request is anonymous. */
    public static AuthenticatedUser require() {
        return find().orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
    }

    public static UUID requireUserId() {
        return require().userId();
    }

    /**
     * The tenant every query in the current request must be scoped to.
     * A SUPER_ADMIN has no tenant of their own and must pass one explicitly.
     */
    public static UUID requireOrganizationId() {
        AuthenticatedUser user = require();
        if (user.organizationId() == null) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "This endpoint requires a caller scoped to an organization");
        }
        return user.organizationId();
    }
}
