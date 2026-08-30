package com.juriscore.identity.domain;

import com.juriscore.common.domain.BaseEntity;
import com.juriscore.common.security.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A person who can sign in.
 *
 * <p>Not a {@code TenantAwareEntity} on purpose: {@link Role#SUPER_ADMIN} operates the
 * platform and belongs to no firm, so {@code organizationId} is nullable here and
 * nowhere else.
 *
 * <p>Email is globally unique, not unique per firm. Sign-in presents an email and a
 * password with no tenant hint, so the address has to resolve to exactly one account;
 * scoping it per firm would mean asking every user which firm they meant. The cost is
 * that a person who is a client of two firms needs two addresses — the right trade for
 * a platform where picking the wrong tenant means seeing the wrong case files.
 */
@Entity
@Table(
        name = "users",
        schema = "identity",
        // Email uniqueness is enforced by a functional unique index on lower(email),
        // declared in V1. JPA cannot express a functional index, so declaring a plain
        // @UniqueConstraint here would describe a weaker guarantee than the one the
        // database actually holds — and would create the wrong thing under ddl-auto.
        indexes = {
                @Index(name = "idx_users_organization", columnList = "organization_id"),
                @Index(name = "idx_users_organization_role", columnList = "organization_id, role")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User extends BaseEntity {

    /** Null only for SUPER_ADMIN. */
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    /** Stored lower-cased; see {@code UserService#normalizeEmail}. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "phone", length = 40)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Consecutive failed sign-ins; reset on success. Drives {@link #lockedUntil}. */
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Bumped on password change and on "sign out everywhere". Access tokens carry this
     * value; a token minted before the bump stops validating immediately, which is how
     * a short-lived stateless JWT can still be revoked.
     */
    @Column(name = "token_generation", nullable = false)
    @Builder.Default
    private int tokenGeneration = 0;

    public String fullName() {
        return firstName + " " + lastName;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }
}
