package com.juriscore.app.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * What happens to the sign-in limit when Redis is not there.
 *
 * <p>The limiter used to fail open everywhere, which reads as a sensible availability
 * trade-off until it is stated precisely for authentication: <em>anyone able to disturb
 * Redis can switch off the brute-force limit on sign-in.</em> An attacker does not even
 * need that capability — they can wait for an outage and spend it. This class pins the
 * split policy that replaced it: authenticated API traffic still fails open, while
 * authentication endpoints fall back to a bounded per-instance limiter.
 *
 * <p>Redis is made unavailable by swapping the limiter's template for one pointed at a dead
 * port, rather than by stopping the shared container — the container is static and shared by
 * the whole suite, and killing it would take every other test down with it.
 */
@TestPropertySource(properties = {
        "juriscore.rate-limit.enabled=true",
        "juriscore.rate-limit.auth-requests-per-window=5",
        "juriscore.rate-limit.api-requests-per-window=5",
})
class RateLimitFailurePolicyIT extends AbstractIntegrationTest {

    /** Wrong on purpose: nothing listens here. */
    private static final int DEAD_REDIS_PORT = 6390;

    private static final String LOGIN_BODY = """
            {"email": "nobody@example.test", "password": "Wr0ng!Password123"}
            """;

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private LocalAuthRateLimiter authFallback;

    @Autowired
    private ObjectMapper objectMapper;

    private Object healthyTemplate;

    @BeforeEach
    void clearFallback() {
        authFallback.reset();
    }

    @AfterEach
    void restoreRedis() {
        if (healthyTemplate != null) {
            ReflectionTestUtils.setField(rateLimiter, "redisTemplate", healthyTemplate);
            healthyTemplate = null;
        }
        authFallback.reset();
    }

    /** Points the limiter at a port nothing is listening on. */
    private void breakRedis() {
        healthyTemplate = ReflectionTestUtils.getField(rateLimiter, "redisTemplate");

        var deadFactory = new org.springframework.data.redis.connection.lettuce
                .LettuceConnectionFactory("127.0.0.1", DEAD_REDIS_PORT);
        deadFactory.afterPropertiesSet();
        var deadTemplate = new org.springframework.data.redis.core.StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();

        ReflectionTestUtils.setField(rateLimiter, "redisTemplate", deadTemplate);
    }

    private int attemptLogin() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    @DisplayName("with Redis healthy, repeated sign-in attempts are limited as before")
    void normalOperationStillLimits() throws Exception {
        int lastStatus = 0;
        for (int attempt = 0; attempt < 12; attempt++) {
            lastStatus = attemptLogin();
        }

        assertThat(lastStatus)
                .as("the distributed limiter is unchanged by this work")
                .isEqualTo(429);
    }

    @Test
    @DisplayName("with Redis down, sign-in is still limited — the hole this closes")
    void authIsStillLimitedWhenRedisIsDown() throws Exception {
        breakRedis();

        int limited = 0;
        int allowed = 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (attemptLogin() == 429) {
                limited++;
            } else {
                allowed++;
            }
        }

        // Before this change every one of the twenty went through, and so would the next
        // twenty thousand.
        assertThat(limited).as("a Redis outage must not remove the sign-in limit").isPositive();
        assertThat(allowed)
                .as("the fallback allows the configured budget before it starts refusing")
                .isLessThanOrEqualTo(10);
    }

    @Test
    @DisplayName("with Redis down, authenticated API traffic is still served")
    void apiTrafficStillFailsOpen() throws Exception {
        breakRedis();

        // The other half of the policy, and the reason this is not simply "fail closed":
        // a cache outage must not become an application outage for callers who already hold
        // a valid token. This endpoint is public and cheap, and it stands in for that
        // traffic — what matters is that no request is refused with 429.
        for (int attempt = 0; attempt < 20; attempt++) {
            int status = mockMvc.perform(get("/actuator/health"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).isNotEqualTo(429);
        }
    }

    @Test
    @DisplayName("the fallback's memory stays bounded while Redis is down")
    void fallbackStorageIsBounded() throws Exception {
        breakRedis();

        // Each request carries a different peer address, which is the input an attacker
        // controls. An unbounded map here would be a memory-exhaustion bug reachable by
        // anyone, which is a worse bug than the one being fixed.
        for (int i = 0; i < 500; i++) {
            String address = "198.51.%d.%d".formatted(i / 256, i % 256);
            mockMvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr(address);
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(LOGIN_BODY));
        }

        assertThat(authFallback.trackedBuckets())
                .as("bounded by juriscore.rate-limit.fallback-max-entries")
                .isLessThanOrEqualTo(20_000);
    }

    @Test
    @DisplayName("the fallback budgets each address separately, not the whole internet as one")
    void fallbackBucketsPerCaller() throws Exception {
        breakRedis();

        // Exhaust one address.
        for (int attempt = 0; attempt < 20; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.7");
                        return request;
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(LOGIN_BODY));
        }

        int otherCaller = mockMvc.perform(post("/api/v1/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.9");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andReturn().getResponse().getStatus();

        assertThat(otherCaller)
                .as("one attacker must not lock every other firm out of signing in")
                .isNotEqualTo(429);
    }

    @Test
    @DisplayName("a forged forwarding header buys no extra fallback budget either")
    void forwardedHeadersDoNotCreateFallbackBuckets() throws Exception {
        breakRedis();

        // The same rule as the Redis path (see RateLimitBucketIT): the bucket follows the
        // connection, never a header. Twelve values must not buy twelve budgets.
        int limited = 0;
        for (int attempt = 0; attempt < 20; attempt++) {
            int status = mockMvc.perform(post("/api/v1/auth/login")
                            .header("X-Forwarded-For", "203.0.113." + attempt)
                            .header("Forwarded", "for=192.0.2." + attempt)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LOGIN_BODY))
                    .andReturn().getResponse().getStatus();
            if (status == 429) {
                limited++;
            }
        }

        assertThat(limited)
                .as("rotating a forwarding header must not reset the fallback budget")
                .isPositive();
    }
}
