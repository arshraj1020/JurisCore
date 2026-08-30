package com.juriscore.identity.repository;

import com.juriscore.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * {@code flushAutomatically} pushes pending entity changes ahead of the bulk update so
     * they are not overwritten by it.
     *
     * <p>{@code clearAutomatically} is deliberately <em>off</em>. It would detach every
     * managed entity in the caller's persistence context, and callers of this method hold
     * live entities they mutate afterwards — a detached write is silently dropped rather
     * than failing loudly. This method touches only {@code refresh_tokens}, which no caller
     * re-reads in the same transaction, so there is nothing stale to clear.
     */
    @Modifying(flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
