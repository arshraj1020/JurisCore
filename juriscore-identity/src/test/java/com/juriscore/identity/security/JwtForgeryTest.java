package com.juriscore.identity.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tokens nobody signed, and tokens signed by the wrong party.
 *
 * <h2>Why this file exists separately from {@link JwtServiceTest}</h2>
 *
 * <p>That class covers the honest failures — an expired token, a wrong issuer, a token
 * signed with a different key. What it does not cover is the classic JWT attack, and the
 * gap matters more than the others put together: <em>every</em> authorization and
 * tenant-isolation test in this repository presents a properly signed token. If the parser
 * ever accepted an unsigned one, all of them would still pass while the entire security
 * model was gone, because the tenant, the user id and the role are read out of the token
 * and trusted from there.
 *
 * <p>Concretely: {@code alg: none} is a JWT the standard permits — an "unsecured" JWS with
 * an empty signature. A parser that reads the header to decide how to verify will happily
 * conclude that no verification is required. jjwt's {@code parseSignedClaims} refuses it by
 * construction (it demands a JWS and the key is pinned by {@code verifyWith}, so the
 * header cannot select the algorithm), which is why these tests pass today. They exist so
 * that a library upgrade, a switch to a hand-rolled parser, or a well-meaning change to
 * "support multiple algorithms" cannot quietly reintroduce it.
 *
 * <p>Each forgery below carries <strong>another firm's</strong> organization id, so a test
 * that fails here is not a parsing curiosity — it is a cross-tenant read.
 */
class JwtForgeryTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("a-test-secret-that-is-long-enough-for-hs256".getBytes());

    /** The tenant an attacker would like the token to name. */
    private static final UUID VICTIM_ORGANIZATION = UUID.randomUUID();

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("juriscore");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtService = new JwtService(properties);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A plausible payload naming the victim firm, valid for the next hour. */
    private static String forgedPayload() {
        return """
                {"sub":"%s","iss":"juriscore","exp":%d,"iat":%d,
                 "email":"attacker@example.test","role":"FIRM_ADMIN",
                 "organizationId":"%s","generation":0}
                """.formatted(UUID.randomUUID(), Instant.now().plusSeconds(3600).getEpochSecond(),
                Instant.now().getEpochSecond(), VICTIM_ORGANIZATION);
    }

    @Test
    @DisplayName("an alg:none token is refused, however well-formed its payload")
    void unsignedTokenIsRefused() {
        // header.payload. — the trailing dot with an empty signature is what the spec calls
        // an unsecured JWS, and it is a valid JWT.
        String forged = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + base64Url(forgedPayload()) + ".";

        assertThatThrownBy(() -> jwtService.parse(forged))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).errorCode())
                        .isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    @Test
    @DisplayName("an alg:none token with a signature attached anyway is refused")
    void unsignedTokenWithJunkSignatureIsRefused() {
        // Some parsers only check "is the signature empty"; this one is not empty, it is
        // simply meaningless.
        String forged = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + base64Url(forgedPayload()) + "." + base64Url("not-a-signature");

        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token whose header names a different MAC algorithm is refused")
    void algorithmSwapIsRefused() {
        // Algorithm confusion: claim HS512 while the server's key is configured for HS256.
        // The signature is not valid under either, so the only way this passes is if the
        // header were allowed to choose the verification path.
        String forged = base64Url("{\"alg\":\"HS512\",\"typ\":\"JWT\"}") + "."
                + base64Url(forgedPayload()) + "." + base64Url("forged");

        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a token signed with someone else's key is refused")
    void foreignKeyIsRefused() {
        JwtProperties attackerProperties = new JwtProperties();
        attackerProperties.setSecret(Base64.getEncoder()
                .encodeToString("a-completely-different-secret-of-sufficient-length".getBytes()));
        attackerProperties.setIssuer("juriscore");
        attackerProperties.setAccessTokenTtl(Duration.ofMinutes(15));

        User attacker = new User();
        attacker.setId(UUID.randomUUID());
        attacker.setOrganizationId(VICTIM_ORGANIZATION);
        attacker.setEmail("attacker@example.test");
        attacker.setRole(Role.FIRM_ADMIN);
        attacker.setTokenGeneration(0);

        // A structurally perfect token, issued by a service that simply is not ours.
        String forged = new JwtService(attackerProperties).issueAccessToken(attacker);

        assertThatThrownBy(() -> jwtService.parse(forged)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("editing the payload of a genuine token invalidates it")
    void tamperedPayloadIsRefused() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOrganizationId(UUID.randomUUID());
        user.setEmail("clerk@example.test");
        user.setRole(Role.CLERK);
        user.setTokenGeneration(0);

        String genuine = jwtService.issueAccessToken(user);
        String[] parts = genuine.split("\\.");
        // Same signature, a payload that promotes the caller and moves them to another firm.
        String tampered = parts[0] + "." + base64Url(forgedPayload()) + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(tampered)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a genuine token still parses, so the tests above are not passing vacuously")
    void genuineTokenIsStillAccepted() {
        // Without this, every assertion above would pass just as happily against a parser
        // that rejected everything.
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setOrganizationId(UUID.randomUUID());
        user.setEmail("asha@sharma-legal.test");
        user.setRole(Role.FIRM_ADMIN);
        user.setTokenGeneration(0);

        JwtService.ParsedToken parsed = jwtService.parse(jwtService.issueAccessToken(user));

        assertThat(parsed.user().organizationId()).isEqualTo(user.getOrganizationId());
        assertThat(parsed.user().role()).isEqualTo(Role.FIRM_ADMIN);
    }
}
