package com.juriscore.identity.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.identity.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a {@code Bearer} token into an authenticated {@code SecurityContext}.
 *
 * <p>A malformed or expired token is <em>not</em> an error here: the filter leaves the
 * context empty and lets the authorization rules decide. That way a bad token on a
 * public endpoint behaves like no token at all, and the 401 for protected endpoints
 * comes from one place — the entry point.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    private static final String MDC_USER = "userId";
    private static final String MDC_TENANT = "organizationId";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            authenticate(request);
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER);
            MDC.remove(MDC_TENANT);
        }
    }

    private void authenticate(HttpServletRequest request) {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            return;
        }
        Optional<String> bearer = extractToken(request);
        if (bearer.isEmpty()) {
            return;
        }
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(bearer.get());
        } catch (ApiException e) {
            logger.debug("Ignoring unusable bearer token: " + e.errorCode());
            return;
        }

        AuthenticatedUser user = parsed.user();
        // Cheap authoritative check: the account may have been suspended, or the password
        // changed, since this still-unexpired token was minted.
        UserTokenState state = userRepository.findTokenState(user.userId()).orElse(null);
        if (state == null || !state.isActive() || state.tokenGeneration() != parsed.tokenGeneration()) {
            logger.debug("Bearer token rejected for user " + user.userId() + " (stale generation or inactive)");
            return;
        }

        var authorities = List.of(new SimpleGrantedAuthority(user.role().authority()));
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        MDC.put(MDC_USER, user.userId().toString());
        if (user.organizationId() != null) {
            MDC.put(MDC_TENANT, user.organizationId().toString());
        }
    }

    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
