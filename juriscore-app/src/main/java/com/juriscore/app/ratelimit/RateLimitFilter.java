package com.juriscore.app.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.common.api.ApiErrorResponse;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the per-caller request budget (PRD §41.5).
 *
 * <p>Runs after authentication so an authenticated caller is limited by user id rather
 * than by IP — otherwise a whole firm behind one office NAT shares a single budget.
 * Anonymous traffic, including every sign-in attempt, falls back to the client address,
 * which is taken from the connection and never from a header the caller controls. See
 * {@link #clientAddress}.
 *
 * <h2>Where this sits, and what it therefore protects</h2>
 *
 * <p>"After authentication" means after Spring Security's chain, which Boot registers at
 * order {@code -100}, well ahead of this filter's {@code @Order(50)}. For a sign-in that
 * ordering is what matters: {@code /api/v1/auth/login} is {@code permitAll}, so the
 * security chain does no work on it beyond declaring it public, and the expensive part —
 * a BCrypt verification at strength 12, deliberately ~100ms — happens in the handler,
 * which is downstream of this filter. A rejected request therefore costs a Redis
 * {@code INCR}, not a password hash, and the limiter protects the operation rather than
 * merely counting requests after the cost has been paid.
 *
 * <h2>Failure policy</h2>
 *
 * <p>When Redis cannot answer, the two kinds of traffic are treated differently, and
 * deliberately so — see {@link LocalAuthRateLimiter} for the full argument. Authenticated
 * API traffic is allowed through, because the caller already holds a valid token and an
 * infrastructure incident should not also be an outage. Authentication endpoints fall back
 * to a bounded per-instance limiter, because fail-open there means the sign-in limit can be
 * removed by anyone able to disturb Redis.
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";
    private static final String API_PATH_PREFIX = "/api/";

    private final RedisRateLimiter rateLimiter;
    private final LocalAuthRateLimiter authFallback;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.isEnabled() || !request.getRequestURI().startsWith(API_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        boolean authEndpoint = request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
        int limit = authEndpoint ? properties.getAuthRequestsPerWindow()
                : properties.getApiRequestsPerWindow();
        String bucket = (authEndpoint ? "auth:" : "api:") + callerKey(request);

        if (!permitted(bucket, limit, authEndpoint)) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Applies the distributed limit, and the failure policy when there isn't one.
     *
     * <p>The fallback is consulted only for authentication endpoints and only when Redis
     * gave no answer — never as a second opinion on a {@code LIMITED} verdict, which would
     * let a caller who is already over the distributed budget spend a fresh local one.
     */
    private boolean permitted(String bucket, int limit, boolean authEndpoint) {
        RateLimitOutcome outcome = rateLimiter.check(bucket, limit, properties.getWindow());
        return switch (outcome) {
            case ALLOWED -> true;
            case LIMITED -> false;
            case UNAVAILABLE -> !authEndpoint
                    || !properties.isAuthFallbackEnabled()
                    || authFallback.tryAcquire(bucket, limit, properties.getWindow());
        };
    }

    private String callerKey(HttpServletRequest request) {
        return CurrentUser.find()
                .map(AuthenticatedUser::userId)
                .map(userId -> "user:" + userId)
                .orElseGet(() -> "ip:" + clientAddress(request));
    }

    /**
     * The address this request is budgeted against — and the single place that decides it.
     *
     * <p>{@code getRemoteAddr()} only, deliberately. This used to read
     * {@code X-Forwarded-For} and take the leftmost entry, which is the entry a caller
     * writes: any anonymous client could name its own bucket, and rotating the header
     * through a dozen values bought a dozen fresh budgets, so the sign-in limit could be
     * walked straight past. {@code RateLimitBucketIT} is the regression test.
     *
     * <p>Reading a forwarding header here would be wrong even if it were parsed correctly,
     * because a filter is the wrong place to decide who may be believed. That decision is
     * configuration — {@code server.tomcat.remoteip.internal-proxies} — and it is enforced
     * before this class runs: Tomcat's RemoteIpValve replaces {@code getRemoteAddr()} with
     * the real client address when, and only when, the request arrived from a trusted
     * proxy. When it did not, this is the address of whoever actually opened the
     * connection, which is the only thing a remote caller cannot forge.
     *
     * <p>So there is exactly one source of truth for the client address, it has one
     * configuration knob, and nothing in application code parses a forwarding header.
     */
    private String clientAddress(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.RATE_LIMIT_EXCEEDED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(properties.getWindow().toSeconds()));
        objectMapper.writeValue(response.getOutputStream(),
                ApiErrorResponse.of(ErrorCode.RATE_LIMIT_EXCEEDED.name(),
                        ErrorCode.RATE_LIMIT_EXCEEDED.defaultMessage()));
    }
}
