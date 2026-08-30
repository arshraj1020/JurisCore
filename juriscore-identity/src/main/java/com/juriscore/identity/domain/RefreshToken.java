package com.juriscore.identity.domain;

import com.juriscore.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One issued refresh token.
 *
 * <p>Only a SHA-256 hash is stored: a stolen database dump must not yield usable
 * sessions. Tokens are rotated on every refresh — the old row is revoked and points at
 * its replacement, so presenting an already-used token is detectable and treated as
 * theft (the whole chain is revoked).
 */
@Entity
@Table(
        name = "refresh_tokens",
        schema = "identity",
        indexes = {
                @Index(name = "idx_refresh_tokens_hash", columnList = "token_hash", unique = true),
                @Index(name = "idx_refresh_tokens_user", columnList = "user_id")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Set when this token was rotated; points at the token that replaced it. */
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isUsable() {
        return !isRevoked() && !isExpired();
    }

    public void revoke() {
        if (revokedAt == null) {
            revokedAt = Instant.now();
        }
    }
}
