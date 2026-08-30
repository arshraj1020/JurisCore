package com.juriscore.identity.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and verifies access tokens.
 *
 * <p>The token carries everything needed to authorize a request — user id, tenant,
 * role — so no downstream module has to hit the identity tables on every call. That
 * is what makes the eventual split into separate services cheap.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String CLAIM_ORGANIZATION = "org";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_EMAIL = "email";
    /** Bumped on password change / global sign-out; see {@link User#getTokenGeneration()}. */
    public static final String CLAIM_GENERATION = "gen";

    private final JwtProperties properties;

    private volatile SecretKey cachedKey;

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getAccessTokenTtl());

        var builder = Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_GENERATION, user.getTokenGeneration());

        if (user.getOrganizationId() != null) {
            builder.claim(CLAIM_ORGANIZATION, user.getOrganizationId().toString());
        }
        return builder.signWith(key()).compact();
    }

    /**
     * Verifies signature and expiry and maps the payload to a principal.
     *
     * @throws ApiException with {@code TOKEN_EXPIRED} or {@code TOKEN_INVALID}
     */
    public ParsedToken parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key())
                    .requireIssuer(properties.getIssuer())
                    .clockSkewSeconds(properties.getAllowedClockSkewSeconds())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID userId = UUID.fromString(claims.getSubject());
            String organizationClaim = claims.get(CLAIM_ORGANIZATION, String.class);
            UUID organizationId = organizationClaim == null ? null : UUID.fromString(organizationClaim);
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            String email = claims.get(CLAIM_EMAIL, String.class);
            Integer generation = claims.get(CLAIM_GENERATION, Integer.class);

            return new ParsedToken(
                    new AuthenticatedUser(userId, organizationId, email, role),
                    generation == null ? 0 : generation);
        } catch (ExpiredJwtException e) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            // The failure type is enough to diagnose. The parser's message can quote the
            // offending token's contents, and a rejected token is still a bearer
            // credential — one typo away from being a valid one — so it must not reach
            // the log, where it would outlive the request and be shipped to CloudWatch.
            log.debug("Rejected access token: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.TOKEN_INVALID);
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    /**
     * Decodes the configured secret. Accepts Base64 and, for local convenience, a raw
     * passphrase — but rejects anything under 256 bits either way, because HS256 with a
     * short key is the single most common way a JWT setup is actually broken.
     */
    private SecretKey key() {
        SecretKey local = cachedKey;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cachedKey == null) {
                byte[] material = decodeSecret(properties.getSecret());
                if (material.length < 32) {
                    throw new IllegalStateException(
                            "juriscore.security.jwt.secret must decode to at least 32 bytes (256 bits); got "
                                    + material.length);
                }
                cachedKey = Keys.hmacShaKeyFor(material);
            }
            return cachedKey;
        }
    }

    private byte[] decodeSecret(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Parsed principal plus the token generation, which the filter checks against the user row. */
    public record ParsedToken(AuthenticatedUser user, int tokenGeneration) {
    }
}
