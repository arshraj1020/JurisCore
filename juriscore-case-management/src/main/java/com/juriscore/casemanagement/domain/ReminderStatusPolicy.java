package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * A reminder is scheduled, and then it either fires or it is called off.
 *
 * <p>The transition that matters for correctness is the one this policy refuses:
 * {@code SENT -> SENT}. The scheduler claims rows under a lock, but a policy that would
 * happily re-fire an already-published reminder is a second chance to get duplicate
 * delivery wrong, so it does not exist.
 */
public final class ReminderStatusPolicy {

    private static final Map<ReminderStatus, Set<ReminderStatus>> ALLOWED =
            new EnumMap<>(ReminderStatus.class);

    static {
        ALLOWED.put(ReminderStatus.SCHEDULED,
                Set.of(ReminderStatus.SENT, ReminderStatus.CANCELLED));
        ALLOWED.put(ReminderStatus.SENT, Set.of());
        ALLOWED.put(ReminderStatus.CANCELLED, Set.of());
    }

    private ReminderStatusPolicy() {
    }

    public static Set<ReminderStatus> allowedFrom(ReminderStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(ReminderStatus current, ReminderStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    public static void requireTransition(ReminderStatus current, ReminderStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A reminder cannot move from " + current + " to " + target);
        }
    }
}
