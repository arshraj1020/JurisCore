package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The hearing lifecycle, in one place, as data.
 *
 * <p>Same shape as {@code CaseStatusPolicy} in casework, for the same reason: the whole
 * rule can be read at once and asserted cell by cell. The one structural difference is
 * that {@link HearingStatus#ADJOURNED} is not terminal — a hearing put off is relisted,
 * and that edge back to {@code SCHEDULED} is the whole point of having the state.
 */
public final class HearingStatusPolicy {

    private static final Map<HearingStatus, Set<HearingStatus>> ALLOWED =
            new EnumMap<>(HearingStatus.class);

    static {
        ALLOWED.put(HearingStatus.SCHEDULED,
                Set.of(HearingStatus.COMPLETED, HearingStatus.ADJOURNED, HearingStatus.CANCELLED));
        ALLOWED.put(HearingStatus.ADJOURNED,
                Set.of(HearingStatus.SCHEDULED, HearingStatus.CANCELLED));
        ALLOWED.put(HearingStatus.COMPLETED, Set.of());
        ALLOWED.put(HearingStatus.CANCELLED, Set.of());
    }

    private HearingStatusPolicy() {
    }

    public static Set<HearingStatus> allowedFrom(HearingStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(HearingStatus current, HearingStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    /**
     * @throws ApiException {@code ILLEGAL_STATE_TRANSITION} (409) for any move the
     *                      lifecycle does not allow, including a no-op move to the state
     *                      the hearing already holds.
     */
    public static void requireTransition(HearingStatus current, HearingStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A hearing cannot move from " + current + " to " + target);
        }
    }
}
