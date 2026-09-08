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
 * <p>This class does not decide what happens when Redis is unreachable. It reports
 * {@link RateLimitOutcome#UNAVAILABLE} and lets {@link RateLimitFilter} choose, because
 * the right answer differs by endpoint: ordinary API traffic is allowed through, while
 * authentication falls back to {@link LocalAuthRateLimiter}. Returning a bare
 * {@code true} here — as this used to — buried that policy decision in an exception
 * handler, where "allow the request" was indistinguishable from "the caller is within
 * their budget".
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
     * @return whether the caller is within the limit, or that Redis could not answer
     */
    public RateLimitOutcome check(String bucket, int limit, Duration window) {
        String key = KEY_PREFIX + bucket;
        try {
            Long count = redisTemplate.execute(
                    SCRIPT, List.of(key), String.valueOf(window.toMillis()));
            if (count == null) {
                // The script ran but returned nothing, which should not happen. Treated as
                // unavailable rather than as a pass: an unreadable answer is not evidence
                // that the caller is within budget.
                return RateLimitOutcome.UNAVAILABLE;
            }
            return count <= limit ? RateLimitOutcome.ALLOWED : RateLimitOutcome.LIMITED;
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable: {}", e.getMessage());
            return RateLimitOutcome.UNAVAILABLE;
        }
    }
}
