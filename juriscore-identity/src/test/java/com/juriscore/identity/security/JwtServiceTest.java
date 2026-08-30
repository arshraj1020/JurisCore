package com.juriscore.identity.security;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET =
            Base64.getEncoder().encodeToString("a-test-secret-that-is-long-enough-for-hs256".getBytes());

    private JwtProperties properties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer("juriscore");
        properties.setAccessTokenTtl(Duration.ofMinutes(15));
        jwtService = new JwtService(properties);
    }

    @Test
    @DisplayName("round-trips the caller's identity, tenant and role")
    void issuesAndParsesToken() {
        User user = user(Role.LAWYER, UUID.randomUUID());

        JwtService.ParsedToken parsed = jwtService.parse(jwtService.issueAccessToken(user));

        assertThat(parsed.user().userId()).isEqualTo(user.getId());
        assertThat(parsed.user().organizationId()).isEqualTo(user.getOrganizationId());
        assertThat(parsed.user().email()).isEqualTo(user.getEmail());
        assertThat(parsed.user().role()).isEqualTo(Role.LAWYER);
        assertThat(parsed.tokenGeneration()).isEqualTo(user.getTokenGeneration());
    }

    @Test
    @DisplayName("a platform administrator carries no tenant")
    void superAdminHasNoOrganisation() {
        JwtService.ParsedToken parsed =
                jwtService.parse(jwtService.issueAccessToken(user(Role.SUPER_ADMIN, null)));

        assertThat(parsed.user().organizationId()).isNull();
        assertThat(parsed.user().isSuperAdmin()).isTrue();
    }

    @Test
    @DisplayName("rejects a token signed with a different key")
    void rejectsForeignSignature() {
        JwtProperties otherProperties = new JwtProperties();
        otherProperties.setSecret(Base64.getEncoder()
                .encodeToString("a-completely-different-secret-key-value-32".getBytes()));
        otherProperties.setIssuer("juriscore");
        String foreignToken = new JwtService(otherProperties).issueAccessToken(user(Role.LAWYER, UUID.randomUUID()));

        assertThatThrownBy(() -> jwtService.parse(foreignToken))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    @Test
    @DisplayName("reports an expired token distinctly, so clients know to refresh")
    void reportsExpiry() {
        properties.setAccessTokenTtl(Duration.ofSeconds(-120));
        properties.setAllowedClockSkewSeconds(0);
        String expired = jwtService.issueAccessToken(user(Role.CLERK, UUID.randomUUID()));

        assertThatThrownBy(() -> jwtService.parse(expired))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
    }

    @Test
    @DisplayName("refuses to start with a secret shorter than the algorithm requires")
    void rejectsShortSecret() {
        JwtProperties weak = new JwtProperties();
        weak.setSecret("dGlueQ==");
        JwtService weakService = new JwtService(weak);

        assertThatThrownBy(() -> weakService.issueAccessToken(user(Role.LAWYER, UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("rejects a token whose issuer is not us")
    void rejectsForeignIssuer() {
        properties.setIssuer("someone-else");
        String foreign = jwtService.issueAccessToken(user(Role.LAWYER, UUID.randomUUID()));
        properties.setIssuer("juriscore");

        assertThatThrownBy(() -> jwtService.parse(foreign))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }

    private User user(Role role, UUID organizationId) {
        User user = User.builder()
                .organizationId(organizationId)
                .email("asha@example-firm.test")
                .passwordHash("irrelevant")
                .firstName("Asha")
                .lastName("Menon")
                .role(role)
                .tokenGeneration(3)
                .build();
        user.setId(UUID.randomUUID());
        return user;
    }
}
