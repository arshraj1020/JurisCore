package com.juriscore.casemanagement.service;

import com.juriscore.common.domain.BaseEntity;
import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

/**
 * Compares the version a caller last read against the row's current one.
 *
 * <p>This is not a second locking mechanism — it is the existing
 * {@code BaseEntity.@Version} column, made reachable by an HTTP client. Optimistic
 * locking that the client cannot participate in is last-write-wins with a spare column:
 * two people editing one hearing in two browser tabs are in separate transactions, so
 * JPA never sees a conflict and the second save silently wins.
 *
 * <p>Phase 2 established the pattern on {@code PUT /cases/{id}}; every mutable Phase 3
 * update follows it, so the rule lives here once rather than in five services.
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
