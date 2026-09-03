package com.juriscore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import org.springframework.boot.autoconfigure.domain.EntityScan;

/**
 * JurisCore runs as a modular monolith: one deployable, several modules that keep
 * strict boundaries — separate packages, separate database schemas, no reaching into
 * another module's repositories. Splitting a module into its own service later is then
 * a packaging change rather than a rewrite, which is why the boundaries are enforced
 * now, while they are cheap.
 */
@SpringBootApplication(scanBasePackages = "com.juriscore")
@EntityScan(basePackages = "com.juriscore")
@EnableJpaRepositories(basePackages = "com.juriscore")
@ConfigurationPropertiesScan(basePackages = "com.juriscore")
@EnableTransactionManagement
// @EnableAsync backs the AFTER_COMMIT event listener, so a slow consumer cannot add
// latency to the request that produced the event.
//
// @EnableScheduling arrives with Phase 3, which is the work Phase 1 said it was waiting
// for: ReminderScheduler sweeps for due reminders. It stays off in the test profile via
// juriscore.reminders.enabled, because an integration test asserting on reminder rows
// while a background thread mutates them is a test that passes locally and fails on a
// loaded runner.
//
// Still not scheduled, and still deliberately: the cleanup job for expired refresh and
// password-reset tokens. RefreshTokenRepository.deleteExpiredBefore exists and its index
// is in place, but nothing calls it, so both tables still grow unbounded. That is a
// Phase 1 gap this phase did not close, not something enabling the scheduler fixed.
@EnableAsync
@EnableScheduling
public class JurisCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(JurisCoreApplication.class, args);
    }
}
