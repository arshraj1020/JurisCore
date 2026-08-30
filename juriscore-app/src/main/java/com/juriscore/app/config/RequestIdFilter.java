package com.juriscore.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Stamps every request with a correlation id, echoed back in {@code X-Request-Id} and
 * attached to every log line through the MDC. When a lawyer reports "it failed at
 * 3:42", this is what turns that into a single retrievable trace in CloudWatch.
 *
 * <p>Runs before the security chain so that even rejected requests are traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Client-supplied ids are accepted for cross-service tracing but never trusted verbatim. */
    private String sanitize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = candidate.length() > MAX_LENGTH ? candidate.substring(0, MAX_LENGTH) : candidate;
        String cleaned = trimmed.replaceAll("[^A-Za-z0-9\\-_.]", "");
        return cleaned.isEmpty() ? UUID.randomUUID().toString() : cleaned;
    }
}
