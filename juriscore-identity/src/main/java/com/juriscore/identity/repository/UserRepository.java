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
            update User u
               set u.lockedUntil = :lockedUntil
             where u.id = :userId
               and u.failedLoginAttempts >= :threshold
            """)
    int lockIfAttemptsReached(@Param("userId") UUID userId,
                              @Param("threshold") int threshold,
                              @Param("lockedUntil") Instant lockedUntil);

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
