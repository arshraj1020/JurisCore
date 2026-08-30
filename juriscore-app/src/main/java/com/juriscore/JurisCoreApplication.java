package com.juriscore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
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
// @EnableScheduling is deliberately absent: nothing is scheduled in Phase 1. It returns
// with the work that needs it — deadline reminders in Phase 3, and the cleanup job for
// expired refresh and password-reset tokens (RefreshTokenRepository.deleteExpiredBefore
// exists and its index is in place, but nothing calls it yet, so both tables grow
// unbounded until then). Enabling a scheduler with nothing to schedule just makes the
// configuration lie about what the application does.
@EnableAsync
public class JurisCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(JurisCoreApplication.class, args);
    }
}
