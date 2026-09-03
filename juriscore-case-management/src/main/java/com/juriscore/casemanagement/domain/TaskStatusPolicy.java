package com.juriscore.casemanagement.domain;

import com.juriscore.common.error.ApiException;
import com.juriscore.common.error.ErrorCode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The task lifecycle.
 *
 * <p>Work can be picked up, put back down and picked up again, so {@code TODO} and
 * {@code IN_PROGRESS} move freely between themselves; the two ways a task stops being
 * work — done, or dropped — are terminal.
 */
public final class TaskStatusPolicy {

    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED.put(TaskStatus.TODO,
                Set.of(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.IN_PROGRESS,
                Set.of(TaskStatus.TODO, TaskStatus.COMPLETED, TaskStatus.CANCELLED));
        ALLOWED.put(TaskStatus.COMPLETED, Set.of());
        ALLOWED.put(TaskStatus.CANCELLED, Set.of());
    }

    private TaskStatusPolicy() {
    }

    public static Set<TaskStatus> allowedFrom(TaskStatus current) {
        return ALLOWED.getOrDefault(current, Set.of());
    }

    public static boolean permits(TaskStatus current, TaskStatus target) {
        return current != null && target != null && allowedFrom(current).contains(target);
    }

    public static void requireTransition(TaskStatus current, TaskStatus target) {
        if (!permits(current, target)) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A task cannot move from " + current + " to " + target);
        }
    }
}
