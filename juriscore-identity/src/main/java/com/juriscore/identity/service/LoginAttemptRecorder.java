package com.juriscore.identity.service;

import com.juriscore.identity.repository.UserRepository;
import com.juriscore.identity.security.AuthProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a failed sign-in in a transaction of its own.
 *
 * <p>Sibling of {@link SessionRevoker}, and it exists for the same reason. A failed
 * sign-in has to end in a failure response, and a failure rolls the caller's transaction
 * back — which would undo the very record that makes the lockout work. {@code AuthService}
 * used to increment the counter on the loaded {@code User} and rely on dirty checking;
 * that write was discarded on every single attempt, so {@code failed_login_attempts}
 * never left zero and {@code max-failed-attempts} was a setting with no effect.
 * {@code REQUIRES_NEW} commits this write before the caller throws.
 *
 * <p>It is a separate bean because Spring's proxying ignores self-invocation: a private
 * or inner method on {@code AuthService} would silently join the outer transaction and
 * change nothing.
 *
 * <p>{@code noRollbackFor = ApiException.class} on the sign-in method was the smaller
 * alternative and was rejected: it would commit <em>any</em> partial state left behind by
 * <em>any</em> expected failure in that method, now or later, which is a much wider
 * promise than "this counter is allowed to survive".
 *
 * <h2>Why two statements and not a loaded entity</h2>
 *
 * <p>Both writes are targeted updates against the two columns that matter, so nothing
 * else on the row can be overwritten by a stale in-memory copy. They also do the counting
 * in the database rather than in Java: brute force is concurrent by nature, and
 * {@code read, add one, write back} lets N simultaneous attempts all read zero and all
 * write one, which would leave the threshold permanently out of reach. {@code SET x = x + 1}
 * cannot lose an increment.
 */
@Service
@RequiredArgsConstructor
public class LoginAttemptRecorder {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptRecorder.class);

    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    /**
     * Counts one failed sign-in against the account and locks it if that brings the run of
     * consecutive failures up to the configured threshold.
     *
     * <p>Both statements share this transaction, so the lock decision reads the counter
     * this call just incremented.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId) {
        if (userRepository.countFailedLoginAttempt(userId) == 0) {
            // The account disappeared between the sign-in read and here. Nothing to record,
            // and nothing worth failing the response over.
            return;
        }

        Instant lockedUntil = Instant.now().plus(authProperties.getLockDuration());
        int locked = userRepository.lockIfAttemptsReached(
                userId, authProperties.getMaxFailedAttempts(), lockedUntil);

        if (locked > 0) {
            log.warn("Locked user {} until {} after {} consecutive failed sign-ins",
                    userId, lockedUntil, authProperties.getMaxFailedAttempts());
        }
    }
}
