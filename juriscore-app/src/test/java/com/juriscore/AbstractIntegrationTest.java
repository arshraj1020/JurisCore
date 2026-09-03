package com.juriscore;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests.
 *
 * <p>A real PostgreSQL container, not H2. Flyway migrations, schemas, check
 * constraints, {@code timestamptz} semantics and unique indexes are exactly the parts
 * an in-memory database gets subtly wrong, and they are exactly what these tests are
 * for. The container is static, so it starts once for the whole suite; tables are
 * truncated between tests instead.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static {
        // Backstop only. Both of these are normally done by DockerEnvironmentInitializer,
        // a LauncherSessionListener that runs before JUnit discovers any test class —
        // which is the only way to be certain nothing has already initialised
        // Testcontainers' DockerClientFactory, since that caches its strategy on first use
        // for the life of the JVM. These calls cover runners that bypass the JUnit Platform
        // launcher; both are idempotent, so when the listener has already run they do
        // nothing. Their position above the container fields still matters, because static
        // initialisers execute in source order.
        DockerEnvironment.ensureConfigured();
        DockerApiVersion.ensureNegotiated();
    }

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("juriscore")
                    .withUsername("juriscore")
                    .withPassword("juriscore");

    /**
     * Redis belongs to every integration test, not just the rate limiter's.
     *
     * <p>The application declares Redis a health dependency, so without it
     * {@code /actuator/health} reports DOWN and any test that asserts the application is
     * healthy is asserting against a configuration no environment actually runs. One
     * static container shared by the whole suite is cheaper than the false negative.
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetDatabase() {
        // CASCADE follows the FKs from users into the token tables, and from clients into
        // cases, assignments and timeline entries. It does not reach across schemas —
        // casework deliberately has no foreign key into identity or organization — so the
        // casework tables have to be named here explicitly. Leaving them out would let one
        // test's matters be counted by the next one's list assertions.
        jdbcTemplate.execute("""
                TRUNCATE TABLE identity.users,
                               organization.organizations,
                               casework.clients,
                               casework.cases,
                               casework.case_assignments,
                               casework.case_events,
                               casework.case_number_sequences,
                               case_management.courts,
                               case_management.hearings,
                               case_management.tasks,
                               case_management.deadlines,
                               case_management.reminders,
                               documents.case_documents
                CASCADE
                """);
    }
}
