package com.juriscore.identity.service;

import com.juriscore.identity.repository.RefreshTokenRepository;
import com.juriscore.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revokes sessions in a transaction of its own.
 *
 * <p>This exists for one case: refresh-token reuse. The response there has to be a
 * failure, and a failure rolls the caller's transaction back — which would undo the
 * very revocation that makes the detection useful. Running in {@code REQUIRES_NEW}
 * commits the revocation before the caller throws. It is a separate bean because
 * Spring's proxying ignores self-invocation, so an inner method on {@code AuthService}
 * would silently join the outer transaction instead.
 */
@Service
@RequiredArgsConstructor
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(UUID userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} refresh token(s) for user {}", revoked, userId);
        return revoked;
    }

    /**
     * Ends every session <em>and</em> retires the access tokens already issued from them.
     *
     * <p>The difference from {@link #revokeAllForUser} matters. A refresh-token row is state
     * this application controls; an access token is a signed bearer credential already in
     * someone's hands, and the API keeps accepting it for its full lifetime no matter what
     * happens to the row it came from. Bumping {@code token_generation} is what retires it,
     * because {@code JwtAuthenticationFilter} compares that column on every request.
     *
     * <p>Use this wherever the reason for revoking is that a credential is believed to be in
     * the wrong hands — detected refresh-token reuse, or a user signing out of everything.
     * {@code UserService} already pairs the two for suspension and role changes; this is the
     * same pairing, in a transaction that survives a caller that has to throw afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeEverythingForUser(UUID userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        userRepository.invalidateIssuedAccessTokens(userId);
        log.warn("Revoked {} refresh token(s) and retired every issued access token for user {}",
                revoked, userId);
        return revoked;
    }
}
