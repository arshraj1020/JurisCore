package com.juriscore.identity.repository;

import com.juriscore.common.security.Role;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.domain.UserStatus;
import com.juriscore.identity.security.UserTokenState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Note the shape of every lookup below: an id is never enough on its own,
 * {@code organizationId} is always part of the predicate. The only exception is
 * {@link #findByEmailIgnoreCase}, used during sign-in before a tenant is known.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findByOrganizationId(UUID organizationId, Pageable pageable);

    Page<User> findByOrganizationIdAndRole(UUID organizationId, Role role, Pageable pageable);

    long countByOrganizationIdAndRoleAndStatus(UUID organizationId, Role role, UserStatus status);

    /**
     * Counts one failed sign-in, in the database rather than on a loaded entity.
     *
     * <p>Two targeted columns, so a stale in-memory copy of the row cannot overwrite
     * anything else, and {@code x = x + 1} rather than a read-modify-write, so parallel
     * attempts against one account cannot lose increments between them. Called only from
     * {@link com.juriscore.identity.service.LoginAttemptRecorder}, which runs it in a
     * transaction that commits independently of the sign-in it belongs to.
     *
     * <p>Deliberately <em>not</em> {@code update versioned}, unlike the two updates that write
     * lock and revocation state. Those move the version so that a request holding a stale copy
     * of the row fails rather than overwriting them. This one fires on every wrong password,
     * so versioning it would let anyone guessing passwords make a colleague's profile or role
     * edit fail with a conflict at will — a nuisance the attacker controls. It does not need
     * the guard either: {@code @DynamicUpdate} on {@link com.juriscore.identity.domain.User}
     * means no other write mentions this column unless it means to reset it, which only a
     * successful sign-in and a password change do.
     *
     * @return 1 when the account exists, 0 when it does not
     */
    @Modifying
    @Query("""
            update User u
               set u.failedLoginAttempts = u.failedLoginAttempts + 1
             where u.id = :userId
            """)
    int countFailedLoginAttempt(@Param("userId") UUID userId);

    /**
     * Locks the account, but only once the counter above has reached the threshold.
     *
     * <p>The threshold lives in the predicate rather than in Java so the decision is made
     * against the committed counter, not against whatever the caller last read.
     *
     * @return 1 when the account was locked, 0 when it is not yet at the threshold
     */
    @Modifying
    @Query("""
            update versioned User u
               set u.lockedUntil = :lockedUntil
             where u.id = :userId
               and u.failedLoginAttempts >= :threshold
            """)
    int lockIfAttemptsReached(@Param("userId") UUID userId,
                              @Param("threshold") int threshold,
                              @Param("lockedUntil") Instant lockedUntil);

    /**
     * Invalidates every access token already issued to this user.
     *
     * <p>Access tokens are self-contained: nothing about revoking a refresh-token row stops
     * the API accepting one. {@code token_generation} is what does — every token carries the
     * generation it was minted under and {@link #findTokenState} is checked against this
     * column on every authenticated request, so a bump here retires the whole outstanding
     * set at once.
     *
     * <p>A targeted increment rather than a loaded entity, for the same reasons as
     * {@link #countFailedLoginAttempt}: it touches one column, so no stale in-memory copy of
     * the row can overwrite anything else, and it is safe to run from a transaction of its
     * own while the caller holds the same row.
     *
     * @return 1 when the account exists, 0 when it does not
     */
    @Modifying
    @Query("""
            update versioned User u
               set u.tokenGeneration = u.tokenGeneration + 1
             where u.id = :userId
            """)
    int invalidateIssuedAccessTokens(@Param("userId") UUID userId);

    /**
     * Two-column projection read on every authenticated request. It is a primary-key
     * lookup returning an int and an enum, which Postgres answers from the index; the
     * alternative — trusting the JWT blindly for its full 15-minute life — would mean a
     * suspended lawyer keeps access to case files after being locked out. When the read
     * volume justifies it, this is the query that goes behind a Redis cache keyed by
     * user id and evicted on password change.
     */
    @Query("""
            select new com.juriscore.identity.security.UserTokenState(u.tokenGeneration, u.status)
            from User u
            where u.id = :userId
            """)
    Optional<UserTokenState> findTokenState(@Param("userId") UUID userId);

    @Query("""
            select u from User u
            where u.organizationId = :organizationId
              and (
                lower(u.firstName) like lower(concat('%', :term, '%'))
                or lower(u.lastName) like lower(concat('%', :term, '%'))
                or lower(u.email) like lower(concat('%', :term, '%'))
              )
            """)
    Page<User> search(@Param("organizationId") UUID organizationId,
                      @Param("term") String term,
                      Pageable pageable);
}
