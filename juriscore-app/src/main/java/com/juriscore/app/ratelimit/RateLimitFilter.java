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
 * Anonymous traffic, including every sign-in attempt, falls back to the client address.
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";
    private static final String API_PATH_PREFIX = "/api/";

    private final RedisRateLimiter rateLimiter;
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

        if (!rateLimiter.tryAcquire(bucket, limit, properties.getWindow())) {
            reject(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String callerKey(HttpServletRequest request) {
        return CurrentUser.find()
                .map(AuthenticatedUser::userId)
                .map(userId -> "user:" + userId)
                .orElseGet(() -> "ip:" + clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwarded.split(",")[0].trim();
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
