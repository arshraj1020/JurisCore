package com.juriscore.casework.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The case lifecycle, in one place.
 *
 * <p>Kept as data rather than a chain of {@code if}s so the whole rule can be read at
 * once and asserted cell by cell in {@code CaseStatusPolicyTest}. {@link CaseStatus#CLOSED}
 * has no outgoing edges: reopening a closed matter is not a Phase 2 capability, and a
 * lifecycle that quietly allows it is worse than one that refuses.
 */
public final class CaseStatusPolicy {

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = new EnumMap<>(CaseStatus.class);

    static {
        ALLOWED.put(CaseStatus.OPEN, Set.of(CaseStatus.IN_PROGRESS, CaseStatus.ON_HOLD, CaseStatus.CLOSED));
        ALLOWED.put(CaseStatus.IN_PROGRESS, Set.of(CaseStatus.ON_HOLD, CaseStatus.CLOSED));
        ALLOWED.put(CaseStatus.ON_HOLD, Set.of(CaseStatus.IN_PROGRESS, CaseStatus.CLOSED));
        ALLOWED.put(CaseStatus.CLOSED, Set.of());
    }

    private CaseStatusPolicy() {
    }

    public static Set<CaseStatus> allowedFrom(CaseStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(CaseStatus current, CaseStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    /**
     * @throws ApiException {@code ILLEGAL_STATE_TRANSITION} (409) for any move the
     *                      lifecycle does not allow — including a no-op move to the
     *                      status the case already has, which is a caller mistake
     *                      rather than a silent success.
     */
    public static void requireTransition(CaseStatus current, CaseStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A case cannot move from " + current + " to " + target);
        }
    }
}
