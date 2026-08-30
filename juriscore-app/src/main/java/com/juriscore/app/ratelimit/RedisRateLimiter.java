package com.juriscore.app.ratelimit;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed-window request counter in Redis.
 *
 * <p>Redis rather than an in-memory counter because the limit has to hold across every
 * instance behind the load balancer — a per-instance counter multiplies the real limit
 * by the number of tasks running, which is exactly the wrong behaviour under autoscale.
 *
 * <p>A fixed window admits up to 2× the limit across a window boundary. That is
 * acceptable for abuse protection; a sliding-log or token-bucket implementation is the
 * upgrade if the limit ever needs to be exact.
 *
 * <p>If Redis is unreachable the limiter fails <em>open</em>. Losing the cache should
 * degrade protection, not take the platform down.
 */
@Component
@RequiredArgsConstructor
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String KEY_PREFIX = "ratelimit:";

    /**
     * Counting and expiry must happen in one atomic step.
     *
     * <p>Issuing {@code INCR} and {@code EXPIRE} as two round-trips leaves a window in
     * which the process can die — a rolling deploy, a failed health check, an OOM kill —
     * after the counter exists but before it has a TTL. The key is then immortal: it
     * keeps incrementing, never expires, and that user or office IP is locked out of
     * sign-in permanently with no way to recover. A Lua script runs atomically inside
     * Redis, so the counter and its expiry are set together or not at all.
     *
     * <p>The {@code PTTL < 0} arm is the repair path: it re-arms the expiry on any key
     * that somehow already lacks one, so a counter stranded by an earlier crash heals on
     * its next request instead of needing a manual {@code DEL}.
     */
    private static final String INCREMENT_AND_EXPIRE = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 or redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private static final RedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(INCREMENT_AND_EXPIRE, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * @return true when the caller is within the limit and the request may proceed
     */
    public boolean tryAcquire(String bucket, int limit, Duration window) {
        String key = KEY_PREFIX + bucket;
        try {
            Long count = redisTemplate.execute(
                    SCRIPT, List.of(key), String.valueOf(window.toMillis()));
            if (count == null) {
                return true;
            }
            return count <= limit;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
