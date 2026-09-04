package com.juriscore.billing.service;

import com.juriscore.common.domain.BaseEntity;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

/**
 * Compares the version a caller last read against the row's current one.
 *
 * <p>Not a second locking mechanism — it is the existing {@code BaseEntity.@Version}
 * column, made reachable by an HTTP client. Optimistic locking a client cannot
 * participate in is last-write-wins with a spare column: two people editing one draft in
 * two browser tabs are in separate transactions, so JPA never sees a conflict and the
 * second save silently wins.
 *
 * <p>Phase 2 established the pattern on {@code PUT /cases/{id}} and Phase 3 copied it into
 * case-management; this is the same rule again for billing, kept module-local for the same
 * reason theirs is — it is three lines, and a shared utility class in common would be a
 * dependency edge for nothing.
 */
final class OptimisticVersion {

    private OptimisticVersion() {
    }

    static void require(BaseEntity entity, Long submitted) {
        if (submitted == null || submitted.longValue() != entity.getVersion()) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }
}
