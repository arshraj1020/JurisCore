package com.juriscore.app.ratelimit;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bound from {@code juriscore.rate-limit.*}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    /** Requests allowed per client per window on authenticated API traffic. */
    private int apiRequestsPerWindow = 100;

    /**
     * Much tighter on the unauthenticated auth endpoints: these are the ones worth
     * brute-forcing, and a legitimate user signs in a handful of times an hour.
     */
    private int authRequestsPerWindow = 10;

    private Duration window = Duration.ofMinutes(1);

    /**
     * Whether authentication endpoints keep a per-instance limit when Redis is unreachable.
     *
     * <p>On by default. Turning it off restores fail-open behaviour for sign-in, which means
     * an attacker who can cause a Redis outage also removes the brute-force limit — so this
     * exists as an escape hatch for an operator who knows why they want it, not as a tuning
     * knob. See {@link LocalAuthRateLimiter}.
     */
    private boolean authFallbackEnabled = true;

    /**
     * Hard ceiling on how many buckets the in-memory fallback will track at once.
     *
     * <p>The bound is the point: the key is derived from the caller's address, so an
     * unbounded map would trade a rate-limit bypass for memory exhaustion. Twenty thousand
     * active buckets is far more than a real tenant produces in one window and still only a
     * few megabytes; past it, unseen callers are refused rather than admitted untracked.
     */
    private int fallbackMaxEntries = 20_000;
}
