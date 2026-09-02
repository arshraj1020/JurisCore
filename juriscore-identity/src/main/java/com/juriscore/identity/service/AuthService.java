package com.juriscore.identity.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
import com.juriscore.identity.api.dto.AuthTokens;
import com.juriscore.identity.api.dto.ForgotPasswordRequest;
import com.juriscore.identity.api.dto.LoginRequest;
import com.juriscore.identity.api.dto.RegisterRequest;
import com.juriscore.identity.api.dto.ResetPasswordRequest;
import com.juriscore.identity.api.dto.UserResponse;
import com.juriscore.identity.domain.PasswordResetToken;
import com.juriscore.identity.domain.RefreshToken;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.event.PasswordChangedEvent;
import com.juriscore.identity.event.PasswordResetRequestedEvent;
import com.juriscore.identity.event.UserRegisteredEvent;
import com.juriscore.identity.repository.PasswordResetTokenRepository;
import com.juriscore.identity.repository.RefreshTokenRepository;
import com.juriscore.identity.repository.UserRepository;
import com.juriscore.identity.security.JwtProperties;
import com.juriscore.identity.security.JwtService;
import com.juriscore.identity.security.TokenHasher;
import com.juriscore.organization.domain.Organization;
import com.juriscore.organization.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The whole authentication lifecycle: signup, sign-in, refresh-token rotation,
 * sign-out and password reset.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * A valid BCrypt hash of a value nobody knows. Verified against when the email does
     * not exist so that "unknown user" and "wrong password" take the same time — without
     * it, response latency alone tells an attacker which addresses are registered.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.3H1nHhkl2b1uGqYy1F8vNGnZ6Cqh5xa";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final OrganizationService organizationService;
    private final SessionRevoker sessionRevoker;
    private final LoginAttemptRecorder loginAttemptRecorder;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final EventPublisher eventPublisher;

    // ---------------------------------------------------------------- registration

    /**
     * Self-serve firm signup. The organization and its first FIRM_ADMIN are created in
     * one transaction: a firm with no administrator would be unreachable, and an admin
     * with no firm has no tenant to be scoped to.
     */
    @Transactional
    public AuthTokens register(RegisterRequest request, RequestContext context) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Organization organization = organizationService.create(
                request.firmName(), email, request.timezone());

        User admin = User.builder()
                .organizationId(organization.getId())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .phone(request.phone())
                .role(Role.FIRM_ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        User saved = userRepository.save(admin);

        log.info("Registered firm {} with administrator {}", organization.getSlug(), saved.getId());
        eventPublisher.publish(new UserRegisteredEvent(
                organization.getId(), saved.getId(), saved.getEmail(), saved.fullName(),
                saved.getRole(), true));

        return issueTokens(saved, context);
    }

    // ---------------------------------------------------------------------- sign-in

    @Transactional
    public AuthTokens login(LoginRequest request, RequestContext context) {
        String email = normalizeEmail(request.email());
        Optional<User> found = userRepository.findByEmailIgnoreCase(email);

        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = found.get();
        if (user.isLocked()) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED,
                    "Too many failed sign-in attempts. Try again later.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            registerFailedAttempt(user);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (user.getStatus() == UserStatus.INVITED) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE,
                    "Set your password using the invitation link before signing in");
        }
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE);
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());

        log.info("Sign-in for user {} of organization {}", user.getId(), user.getOrganizationId());
        return issueTokens(user, context);
    }

    /**
     * Hands the failure to {@link LoginAttemptRecorder}, whose transaction commits
     * independently of this one.
     *
     * <p>This used to increment the counter on the loaded {@code User} and let dirty
     * checking persist it. It never did: {@link #login} is {@code @Transactional} and
     * throws {@code ApiException} — a {@code RuntimeException} — on the next line, so the
     * transaction was marked rollback-only and the update discarded. Every failed sign-in
     * on the platform left {@code failed_login_attempts} at zero, and
     * {@code max-failed-attempts} was configuration with no effect.
     *
     * <p>The unit test that covered this passed throughout, because a mocked repository
     * has no transaction to roll back and the mutation survived on the in-memory object.
     * {@code AccountLockoutIT} is the regression test; it reads the row back.
     */
    private void registerFailedAttempt(User user) {
        loginAttemptRecorder.recordFailure(user.getId());
    }

    // ----------------------------------------------------------------------- refresh

    /**
     * Rotates the refresh token: the presented token is revoked and a new one issued.
     *
     * <p>Presenting a token that was already rotated means either a replay or a stolen
     * token being used alongside the legitimate one. Both are handled the same way —
     * every session for that user is revoked. Losing a session is a minor annoyance;
     * leaving a thief with a valid chain is not.
     */
    @Transactional
    public AuthTokens refresh(String presentedToken, RequestContext context) {
        String hash = TokenHasher.hash(presentedToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (stored.isRevoked()) {
            log.warn("Refresh token reuse detected for user {} — revoking all sessions", stored.getUserId());
            // Committed independently: the exception below rolls this transaction back.
            sessionRevoker.revokeAllForUser(stored.getUserId());
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (stored.isExpired()) {
            throw new ApiException(ErrorCode.REFRESH_TOKEN_INVALID, "Session has expired. Sign in again.");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE);
        }

        IssuedRefreshToken replacement = persistRefreshToken(user, context);
        stored.revoke();
        stored.setReplacedBy(replacement.entity().getId());

        return AuthTokens.of(
                jwtService.issueAccessToken(user),
                replacement.rawToken(),
                jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }

    // ------------------------------------------------------------------------ logout

    /** Ends one session, or every session when no token is supplied. */
    @Transactional
    public void logout(UUID userId, String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
            log.info("Signed user {} out of {} session(s)", userId, revoked);
            return;
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.hash(refreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(RefreshToken::revoke);
    }

    // ---------------------------------------------------------------- password reset

    /**
     * Always completes without telling the caller whether the address exists — the
     * endpoint is unauthenticated, so any difference in behaviour is an account
     * enumeration oracle.
     */
    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        Optional<User> found = userRepository.findByEmailIgnoreCase(email);
        if (found.isEmpty()) {
            log.debug("Password reset requested for unknown address");
            return;
        }
        User user = found.get();
        if (user.getStatus() == UserStatus.DEACTIVATED) {
            return;
        }

        passwordResetTokenRepository.invalidateAllForUser(user.getId(), Instant.now());

        String rawToken = TokenHasher.newToken();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(TokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(jwtProperties.getPasswordResetTtl()))
                .build());

        eventPublisher.publish(new PasswordResetRequestedEvent(
                user.getOrganizationId(), user.getId(), user.getEmail(), user.fullName(), rawToken));
        log.info("Issued password reset token for user {}", user.getId());
    }

    /**
     * Consumes a reset token. Also used to activate an invited account, which is why
     * the status is promoted to ACTIVE here.
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(TokenHasher.hash(request.token()))
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID,
                        "This reset link is invalid or has already been used"));

        if (!token.isUsable()) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED,
                    "This reset link has expired. Request a new one.");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID));

        // Burn the token BEFORE anything else can touch the persistence context. A reset
        // link that stays usable is a second chance for whoever else has seen it — a
        // forwarded email, a browser history entry, a proxy log — for its full lifetime.
        token.setUsedAt(Instant.now());
        passwordResetTokenRepository.saveAndFlush(token);

        applyNewPassword(user, request.newPassword());

        eventPublisher.publish(new PasswordChangedEvent(
                user.getOrganizationId(), user.getId(), user.getEmail(), true));
        log.info("Password reset completed for user {}", user.getId());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        applyNewPassword(user, newPassword);

        eventPublisher.publish(new PasswordChangedEvent(
                user.getOrganizationId(), user.getId(), user.getEmail(), false));
    }

    /**
     * Sets the hash and invalidates everything minted under the old one: refresh tokens
     * are revoked, and the token generation bump makes outstanding access tokens fail
     * validation on their next use rather than lingering for their remaining TTL.
     */
    private void applyNewPassword(User user, String rawPassword) {
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setStatus(UserStatus.ACTIVE);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now());
    }

    // ------------------------------------------------------------------------ shared

    private AuthTokens issueTokens(User user, RequestContext context) {
        IssuedRefreshToken refresh = persistRefreshToken(user, context);
        return AuthTokens.of(
                jwtService.issueAccessToken(user),
                refresh.rawToken(),
                jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }

    private IssuedRefreshToken persistRefreshToken(User user, RequestContext context) {
        String rawToken = TokenHasher.newToken();
        RefreshToken entity = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(TokenHasher.hash(rawToken))
                .expiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()))
                .userAgent(truncate(context.userAgent(), 255))
                .ipAddress(truncate(context.ipAddress(), 64))
                .build();
        return new IssuedRefreshToken(refreshTokenRepository.save(entity), rawToken);
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Where the call came from; recorded on the session for the audit trail. */
    public record RequestContext(String ipAddress, String userAgent) {

        public static RequestContext unknown() {
            return new RequestContext(null, null);
        }
    }

    private record IssuedRefreshToken(RefreshToken entity, String rawToken) {
    }
}
