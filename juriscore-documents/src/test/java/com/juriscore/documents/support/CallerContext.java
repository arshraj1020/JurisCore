package com.juriscore.documents.support;

import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.Role;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Puts a caller in the security context for unit tests, because {@code TenantGuard} and
 * {@code CurrentUser} read the caller rather than taking one as an argument.
 */
public final class CallerContext {

    private CallerContext() {
    }

    public static AuthenticatedUser signIn(UUID userId, UUID organizationId, Role role) {
        AuthenticatedUser caller = new AuthenticatedUser(userId, organizationId, "caller@firm.test", role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null,
                        List.of(new SimpleGrantedAuthority(role.authority()))));
        return caller;
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
