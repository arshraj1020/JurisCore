package com.juriscore.common.config;

import com.juriscore.common.security.AuthenticatedUser;
import com.juriscore.common.security.CurrentUser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.UUID;

/** Populates {@code created_by} / {@code updated_by} from the authenticated caller. */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<UUID> auditorAware() {
        // Empty for unauthenticated writes (registration, scheduled jobs) — the column is nullable.
        return () -> CurrentUser.find().map(AuthenticatedUser::userId);
    }
}
