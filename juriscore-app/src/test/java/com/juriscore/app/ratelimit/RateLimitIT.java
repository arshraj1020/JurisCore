package com.juriscore.app.ratelimit;

import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter against a real Redis.
 *
 * <p>Kept separate from {@code SecurityGuaranteesIT} because it drives the limiter bean
 * directly. The servlet filter in front of it stays disabled under the test profile —
 * switching it on would make every other integration test flaky the moment one of them
 * made more than a handful of requests. PostgreSQL and Redis both come from the shared
 * containers in {@link AbstractIntegrationTest}.
 */
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    private RedisRateLimiter rateLimiter;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("allows exactly the configured number of requests per window")
    void enforcesTheLimit() {
        List<Boolean> results = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofMinutes(1)))
                .toList();

        assertThat(results).containsExactly(true, true, true, true, true, false, false, false);
    }

    @Test
    @DisplayName("buckets are independent, so one caller cannot exhaust another's budget")
    void bucketsAreIsolated() {
        for (int i = 0; i < 6; i++) {
            rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofMinutes(1));
        }

        assertThat(rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofMinutes(1))).isFalse();
        assertThat(rateLimiter.tryAcquire("auth:ip:203.0.113.9", 5, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.tryAcquire("api:ip:198.51.100.7", 5, Duration.ofMinutes(1))).isTrue();
    }

    /**
     * Counting and expiry have to happen in one atomic step. Two round-trips leave a
     * window where the process can die after the counter exists but before it has a TTL,
     * stranding an immortal key that locks that caller out permanently.
     */
    @Test
    @DisplayName("every counter is created with an expiry attached")
    void counterAlwaysCarriesAnExpiry() {
        rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofSeconds(30));

        Long ttl = redisTemplate.getExpire("ratelimit:auth:ip:198.51.100.7");
        assertThat(ttl).isNotNull().isGreaterThan(0L);
    }

    @Test
    @DisplayName("a counter left without an expiry heals instead of locking someone out forever")
    void repairsAnImmortalCounter() {
        String key = "ratelimit:auth:ip:198.51.100.7";
        // Exactly what a crash between INCR and EXPIRE would leave behind.
        redisTemplate.opsForValue().set(key, "500");
        assertThat(redisTemplate.getExpire(key)).isEqualTo(-1L);

        rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofSeconds(30));

        assertThat(redisTemplate.getExpire(key))
                .as("the stranded counter must be given an expiry so the caller recovers")
                .isGreaterThan(0L);
    }

    @Test
    @DisplayName("the window expires and the caller recovers")
    @Timeout(30)
    void windowResets() throws Exception {
        for (int i = 0; i < 6; i++) {
            rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofSeconds(1));
        }
        assertThat(rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofSeconds(1))).isFalse();

        Thread.sleep(1400);

        assertThat(rateLimiter.tryAcquire("auth:ip:198.51.100.7", 5, Duration.ofSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("concurrent callers cannot exceed the limit between them")
    void isAtomicUnderConcurrency() throws Exception {
        int limit = 50;
        int attempts = 200;
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Boolean>> tasks = java.util.stream.IntStream.range(0, attempts)
                    .<Callable<Boolean>>mapToObj(i ->
                            () -> rateLimiter.tryAcquire("api:user:shared", limit, Duration.ofMinutes(1)))
                    .toList();

            long allowed = 0;
            for (Future<Boolean> future : pool.invokeAll(tasks)) {
                if (future.get()) {
                    allowed++;
                }
            }
            assertThat(allowed)
                    .as("a non-atomic counter would let more than the limit through")
                    .isEqualTo(limit);
        }
    }

    @Test
    @DisplayName("fails open when Redis is unreachable, rather than taking sign-in down with it")
    void failsOpenWhenRedisIsDown() {
        // A bucket name is irrelevant here; what matters is that a broken limiter never
        // becomes a broken platform. Verified by pointing the template at a dead port.
        var deadFactory = new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(
                "127.0.0.1", 6390);
        deadFactory.afterPropertiesSet();
        var deadTemplate = new StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();

        RedisRateLimiter offline = new RedisRateLimiter(deadTemplate);

        assertThat(offline.tryAcquire("auth:ip:198.51.100.7", 1, Duration.ofMinutes(1))).isTrue();
        deadFactory.destroy();
    }
}
