package com.juriscore.identity.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Sign-in hardening knobs, bound from {@code juriscore.security.auth.*}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.security.auth")
public class AuthProperties {

    /** Consecutive failures before the account is temporarily locked. */
    private int maxFailedAttempts = 8;

    /** How long a lock lasts. Short enough to be a speed bump, not a denial-of-service vector. */
    private Duration lockDuration = Duration.ofMinutes(15);
}
