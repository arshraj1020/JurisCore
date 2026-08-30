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
}
