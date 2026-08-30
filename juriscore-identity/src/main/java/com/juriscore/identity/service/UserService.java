package com.juriscore.identity.service;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;
import com.juriscore.common.event.EventPublisher;
import com.juriscore.common.security.Role;
import com.juriscore.identity.api.dto.InviteUserRequest;
import com.juriscore.identity.api.dto.UpdateProfileRequest;
import com.juriscore.identity.domain.PasswordResetToken;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.event.UserInvitedEvent;
import com.juriscore.identity.repository.PasswordResetTokenRepository;
import com.juriscore.identity.repository.UserRepository;
import com.juriscore.identity.security.JwtProperties;
import com.juriscore.identity.security.TokenHasher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Firm membership: invitations, directory queries, profile and status changes. */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final SessionRevoker sessionRevoker;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final EventPublisher eventPublisher;

    /**
     * Adds a member to the caller's firm.
     *
     * <p>No password is chosen here. The account is stored INVITED with an unusable
     * random hash and an activation token is issued, so an administrator never knows,
     * types, or transmits another person's password.
     */
    @Transactional
    public User invite(UUID organizationId, InviteUserRequest request) {
        if (request.role() == Role.SUPER_ADMIN) {
            throw new ApiException(ErrorCode.ACCESS_DENIED,
                    "Platform administrators cannot be created from a firm");
        }
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        User user = User.builder()
                .organizationId(organizationId)
                .email(email)
                .passwordHash(passwordEncoder.encode(TokenHasher.newToken()))
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .phone(request.phone())
                .role(request.role())
                .status(UserStatus.INVITED)
                .build();
        User saved = userRepository.save(user);

        String activationToken = TokenHasher.newToken();
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userId(saved.getId())
                .tokenHash(TokenHasher.hash(activationToken))
                .expiresAt(Instant.now().plus(jwtProperties.getPasswordResetTtl()))
                .build());

        log.info("Invited {} as {} to organization {}", saved.getId(), saved.getRole(), organizationId);
        eventPublisher.publish(new UserInvitedEvent(organizationId, saved.getId(), saved.getEmail(),
                saved.fullName(), saved.getRole(), activationToken));
        return saved;
    }

    @Transactional(readOnly = true)
    public User getScoped(UUID userId, UUID organizationId) {
        return userRepository.findByIdAndOrganizationId(userId, organizationId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.USER_NOT_FOUND, userId));
    }

    @Transactional(readOnly = true)
    public User getById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.USER_NOT_FOUND, userId));
    }

    @Transactional(readOnly = true)
    public Page<User> list(UUID organizationId, Role role, String search, Pageable pageable) {
        if (search != null && !search.isBlank()) {
            return userRepository.search(organizationId, search.trim(), pageable);
        }
        if (role != null) {
            return userRepository.findByOrganizationIdAndRole(organizationId, role, pageable);
        }
        return userRepository.findByOrganizationId(organizationId, pageable);
    }

    @Transactional
    public User updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = getById(userId);
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPhone(request.phone());
        return user;
    }

    /**
     * Suspending or deactivating a member also drops their sessions. Leaving them signed
     * in until their access token expires would mean a lawyer removed from a firm keeps
     * reading case files for the rest of the token's life.
     */
    @Transactional
    public User changeStatus(UUID organizationId, UUID userId, UserStatus status) {
        User user = getScoped(userId, organizationId);
        if (user.getRole() == Role.FIRM_ADMIN && status != UserStatus.ACTIVE
                && userRepository.countByOrganizationIdAndRoleAndStatus(
                        organizationId, Role.FIRM_ADMIN, UserStatus.ACTIVE) <= 1) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A firm must keep at least one active administrator");
        }
        user.setStatus(status);
        if (status != UserStatus.ACTIVE) {
            user.setTokenGeneration(user.getTokenGeneration() + 1);
            sessionRevoker.revokeAllForUser(userId);
        }
        log.info("User {} status changed to {}", userId, status);
        return user;
    }

    @Transactional
    public User changeRole(UUID organizationId, UUID userId, Role role) {
        if (role == Role.SUPER_ADMIN) {
            throw new ApiException(ErrorCode.ACCESS_DENIED, "Cannot grant platform administrator from a firm");
        }
        User user = getScoped(userId, organizationId);
        user.setRole(role);
        // The role is baked into issued access tokens, so old ones must stop validating.
        user.setTokenGeneration(user.getTokenGeneration() + 1);
        sessionRevoker.revokeAllForUser(userId);
        log.info("User {} role changed to {}", userId, role);
        return user;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
