package com.juriscore.identity.repository;

import com.juriscore.identity.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The same lookup, but holding a row lock for the rest of the transaction.
     *
     * <p>Rotation is read-then-write: decide the token has not been revoked, then revoke it
     * and issue its successor. Under READ COMMITTED — PostgreSQL's default, and this
     * application's — two concurrent refreshes presenting the same token can both read the
     * row while it is still live, and both go on to rotate it. The result is two valid
     * successor chains from one credential and, worse, a reuse that
     * <em>reuse detection never sees</em>: neither transaction observed the other's
     * revocation, so the theft signal the whole design rests on is silently lost.
     *
     * <p>{@code PESSIMISTIC_WRITE} issues {@code SELECT ... FOR UPDATE}, so the second
     * transaction blocks at this line until the first commits and then re-reads the row —
     * by which time it is revoked, and the ordinary reuse path handles it. That yields the
     * behaviour the design already claims: exactly one rotation succeeds, the loser is
     * treated as a replay, and the chain stays single-threaded.
     *
     * <p>Blocking rather than failing fast is the right trade here. Rotation is a
     * sub-millisecond write on a row keyed by a unique hash, so contention exists only
     * between requests presenting the <em>same</em> token — which is either a client
     * retrying or an attacker replaying, and both should be serialised rather than raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

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
