package com.juriscore.casemanagement.scheduler;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Knobs for the reminder sweep. Bound from {@code juriscore.reminders.*}. */
@Getter
@Setter
@ConfigurationProperties(prefix = "juriscore.reminders")
public class ReminderProperties {

    /**
     * Whether the sweep runs at all.
     *
     * <p>Off in the test profile, deliberately: an integration test that asserts on
     * reminder state while a background thread is mutating the same rows is a test that
     * passes locally and fails on a loaded CI runner. The tests drive
     * {@code ReminderDispatchService} directly instead, which is the same code path
     * without the timer.
     */
    private boolean enabled = true;

    /** How often to look. A minute is well inside any useful reminder's tolerance. */
    private Duration pollInterval = Duration.ofSeconds(60);

    /**
     * How many reminders one sweep claims.
     *
     * <p>Bounded so a backlog is worked through over several sweeps instead of one
     * transaction holding locks on thousands of rows.
     */
    private int batchSize = 100;
}
