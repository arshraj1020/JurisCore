package com.juriscore.identity.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
import com.juriscore.identity.api.dto.ForgotPasswordRequest;
import com.juriscore.identity.api.dto.LoginRequest;
import com.juriscore.identity.domain.RefreshToken;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.repository.PasswordResetTokenRepository;
import com.juriscore.identity.repository.RefreshTokenRepository;
import com.juriscore.identity.repository.UserRepository;
import com.juriscore.identity.security.AuthProperties;
import com.juriscore.identity.security.JwtProperties;
import com.juriscore.identity.security.JwtService;
import com.juriscore.identity.security.TokenHasher;
import com.juriscore.organization.service.OrganizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final String PASSWORD = "Adv0cate!Chamber";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private OrganizationService organizationService;
    @Mock
    private SessionRevoker sessionRevoker;
    @Mock
    private EventPublisher eventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private JwtProperties jwtProperties;
    private AuthProperties authProperties;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setSecret(Base64.getEncoder()
                .encodeToString("a-test-secret-that-is-long-enough-for-hs256".getBytes()));
        jwtProperties.setIssuer("juriscore");

        authProperties = new AuthProperties();
        authProperties.setMaxFailedAttempts(3);
        authProperties.setLockDuration(Duration.ofMinutes(15));

        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordResetTokenRepository,
                organizationService,
                sessionRevoker,
                passwordEncoder,
                new JwtService(jwtProperties),
                jwtProperties,
                authProperties,
                eventPublisher);

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> {
                    RefreshToken token = invocation.getArgument(0);
                    token.setId(UUID.randomUUID());
                    return token;
                });
    }

    @Test
    @DisplayName("signs in with correct credentials and clears the failure counter")
    void signsInSuccessfully() {
        User user = activeUser();
        user.setFailedLoginAttempts(2);
        when(userRepository.findByEmailIgnoreCase("asha@example-firm.test")).thenReturn(Optional.of(user));

        var tokens = authService.login(
                new LoginRequest("Asha@Example-Firm.test", PASSWORD), AuthService.RequestContext.unknown());

        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(tokens.tokenType()).isEqualTo("Bearer");
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("an unknown address fails exactly like a wrong password")
    void unknownAddressLooksLikeWrongPassword() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("nobody@example.test", PASSWORD), AuthService.RequestContext.unknown()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("locks the account once failed attempts reach the configured limit")
    void locksAfterRepeatedFailures() {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));
        var wrongPassword = new LoginRequest("asha@example-firm.test", "Wr0ng!Password123");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> authService.login(wrongPassword, AuthService.RequestContext.unknown()))
                    .isInstanceOf(ApiException.class);
        }

        assertThat(user.isLocked()).isTrue();
        // Even the right password is refused while the lock holds.
        assertThatThrownBy(() -> authService.login(
                new LoginRequest("asha@example-firm.test", PASSWORD), AuthService.RequestContext.unknown()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_LOCKED));
    }

    @Test
    @DisplayName("an invited user is told to use their invitation, not that the password was wrong")
    void invitedUserCannotSignIn() {
        User user = activeUser();
        user.setStatus(UserStatus.INVITED);
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("asha@example-firm.test", PASSWORD), AuthService.RequestContext.unknown()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_INACTIVE));
    }

    @Test
    @DisplayName("rotates the refresh token and revokes the one presented")
    void rotatesRefreshToken() {
        User user = activeUser();
        RefreshToken stored = storedToken(user.getId(), Instant.now().plus(Duration.ofDays(7)));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        var tokens = authService.refresh("whatever-raw-token", AuthService.RequestContext.unknown());

        assertThat(tokens.refreshToken()).isNotBlank();
        assertThat(stored.isRevoked()).isTrue();
        assertThat(stored.getReplacedBy()).isNotNull();
    }

    @Test
    @DisplayName("reusing an already-rotated token kills every session for that user")
    void refreshTokenReuseRevokesEverything() {
        User user = activeUser();
        RefreshToken alreadyUsed = storedToken(user.getId(), Instant.now().plus(Duration.ofDays(7)));
        alreadyUsed.revoke();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(alreadyUsed));

        assertThatThrownBy(() -> authService.refresh("stolen-token", AuthService.RequestContext.unknown()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_TOKEN_INVALID));

        // Committed in its own transaction, otherwise the exception above would undo it.
        verify(sessionRevoker, times(1)).revokeAllForUser(user.getId());
    }

    @Test
    @DisplayName("a password reset for an unknown address does nothing and says nothing")
    void passwordResetDoesNotLeakAccountExistence() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        authService.requestPasswordReset(new ForgotPasswordRequest("nobody@example.test"));

        verify(passwordResetTokenRepository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("changing a password invalidates outstanding tokens")
    void changingPasswordBumpsTokenGeneration() {
        User user = activeUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        int generationBefore = user.getTokenGeneration();

        authService.changePassword(user.getId(), PASSWORD, "N3w!PasswordHere");

        assertThat(user.getTokenGeneration()).isEqualTo(generationBefore + 1);
        assertThat(passwordEncoder.matches("N3w!PasswordHere", user.getPasswordHash())).isTrue();
        verify(refreshTokenRepository).revokeAllForUser(eq(user.getId()), any(Instant.class));
    }

    private User activeUser() {
        User user = User.builder()
                .organizationId(UUID.randomUUID())
                .email("asha@example-firm.test")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .firstName("Asha")
                .lastName("Menon")
                .role(Role.LAWYER)
                .status(UserStatus.ACTIVE)
                .build();
        user.setId(UUID.randomUUID());
        return user;
    }

    private RefreshToken storedToken(UUID userId, Instant expiresAt) {
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .tokenHash(TokenHasher.hash("whatever-raw-token"))
                .expiresAt(expiresAt)
                .build();
        token.setId(UUID.randomUUID());
        return token;
    }
}
