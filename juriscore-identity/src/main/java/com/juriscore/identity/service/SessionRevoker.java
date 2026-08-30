package com.juriscore.identity.service;

import com.juriscore.identity.repository.RefreshTokenRepository;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(UUID userId) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, Instant.now());
        log.info("Revoked {} refresh token(s) for user {}", revoked, userId);
        return revoked;
    }
}
