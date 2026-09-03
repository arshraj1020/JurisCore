package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The deadline lifecycle: open until it is met or withdrawn, and then finished.
 *
 * <p>There is deliberately no OVERDUE state. Whether a deadline has passed is a function
 * of {@code due_at} and the clock, and storing it as well would mean a row that is wrong
 * between the moment the date passes and the moment some job notices.
 */
public final class DeadlineStatusPolicy {

    private static final Map<DeadlineStatus, Set<DeadlineStatus>> ALLOWED =
            new EnumMap<>(DeadlineStatus.class);

    static {
        ALLOWED.put(DeadlineStatus.OPEN,
                Set.of(DeadlineStatus.COMPLETED, DeadlineStatus.CANCELLED));
        ALLOWED.put(DeadlineStatus.COMPLETED, Set.of());
        ALLOWED.put(DeadlineStatus.CANCELLED, Set.of());
    }

    private DeadlineStatusPolicy() {
    }

    public static Set<DeadlineStatus> allowedFrom(DeadlineStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(DeadlineStatus current, DeadlineStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    public static void requireTransition(DeadlineStatus current, DeadlineStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A deadline cannot move from " + current + " to " + target);
        }
    }
}
