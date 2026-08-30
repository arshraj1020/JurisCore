package com.juriscore.identity.security;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT settings, bound from {@code juriscore.security.jwt.*}.
 *
 * <p>The secret has no default on purpose. The application must fail to start rather
 * than sign production tokens with a value someone copied out of a tutorial; in
 * deployed environments it comes from AWS Secrets Manager (PRD §33).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "juriscore.security.jwt")
public class JwtProperties {

    /** Base64-encoded HMAC key, at least 256 bits once decoded. */
    @NotBlank
    private String secret;

    @NotBlank
    private String issuer = "juriscore";

    /** Short by design; revocation happens through the token generation claim. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    private Duration refreshTokenTtl = Duration.ofDays(14);

    private Duration passwordResetTtl = Duration.ofMinutes(30);

    /** Tolerance for clock drift between the app and whatever issued the token. */
    @Min(0)
    private long allowedClockSkewSeconds = 30;
}
