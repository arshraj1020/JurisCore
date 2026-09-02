package com.juriscore.identity;

import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.security.AuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Brute-force lockout, asserted against the database rather than against an object.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>{@code AuthServiceTest} already covers the lockout rule and passes. It cannot cover
 * the thing that actually matters here: {@code AuthService.login} is {@code @Transactional},
 * it records the failed attempt on the managed {@code User} and then throws
 * {@code ApiException}, which extends {@code RuntimeException} — so Spring marks the
 * transaction rollback-only and the recorded failure is discarded on the way out. A unit
 * test with a mocked repository has no transaction to roll back, so the mutation survives
 * on the in-memory object and the rule looks enforced.
 *
 * <p>Only a real transaction over a real database can tell the difference, and only by
 * reading the row back. Every assertion below that reads {@code identity.users} directly
 * is doing so deliberately: going through the API a second time would ask the same broken
 * write path whether it had written anything.
 *
 * <p>The threshold is read from {@link AuthProperties} rather than hardcoded — the test
 * profile lowers it to 3 while production runs 8, and a test that assumes either number is
 * a test that starts lying the moment the configuration moves.
 */
class AccountLockoutIT extends AbstractIntegrationTest {

    private static final String EMAIL = "asha@sharma-legal.test";
    private static final String PASSWORD = "Adv0cate!Chamber";
    private static final String WRONG_PASSWORD = "Wr0ng!Password123";

    @Autowired
    private AuthProperties authProperties;

    @BeforeEach
    void registerTheFirm() throws Exception {
        // Runs after AbstractIntegrationTest#resetDatabase, so every test starts from one
        // known account with a clean failure history.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firmName": "Sharma & Associates",
                                  "firstName": "Asha",
                                  "lastName": "Menon",
                                  "email": "%s",
                                  "password": "%s",
                                  "timezone": "Asia/Kolkata"
                                }
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isCreated());

        assertThat(failedAttempts()).isZero();
        assertThat(lockedUntil()).isNull();
    }

    @Test
    @DisplayName("a single failed sign-in is persisted, not discarded with the rolled-back transaction")
    void recordsASingleFailedAttempt() throws Exception {
        signIn(WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        assertThat(failedAttempts())
                .as("identity.users.failed_login_attempts after one wrong password — a value of 0 "
                        + "means the counter was written inside login()'s transaction and lost when "
                        + "ApiException rolled it back")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("consecutive failures accumulate and lock the account at the configured threshold")
    void locksTheAccountOnceTheThresholdIsReached() throws Exception {
        int threshold = authProperties.getMaxFailedAttempts();

        for (int attempt = 1; attempt <= threshold; attempt++) {
            signIn(WRONG_PASSWORD).andExpect(status().isUnauthorized());
            assertThat(failedAttempts())
                    .as("failed_login_attempts after wrong password %d of %d", attempt, threshold)
                    .isEqualTo(attempt);
        }

        assertThat(lockedUntil())
                .as("locked_until must be set once %d consecutive failures are reached", threshold)
                .isNotNull()
                .isAfter(Instant.now());

        signIn(WRONG_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("the correct password is refused while the lock holds")
    void refusesTheCorrectPasswordWhileLocked() throws Exception {
        lockTheAccount();

        signIn(PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("sign-in works again once the lock has expired, and the counter is cleared")
    void allowsSignInOnceTheLockHasExpired() throws Exception {
        lockTheAccount();

        // The lock lasts 15 minutes, which no test should wait for. Moving the stored
        // expiry into the past is the same state the clock would have produced, and it
        // exercises the real comparison in User#isLocked against a real persisted value.
        expireTheLock();

        signIn(PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        assertThat(failedAttempts())
                .as("a successful sign-in after the lock expires must clear the counter")
                .isZero();
        assertThat(lockedUntil())
                .as("a successful sign-in must clear the expired lock rather than leave it behind")
                .isNull();
    }

    @Test
    @DisplayName("a successful sign-in resets a partial run of failures")
    void aSuccessfulSignInClearsTheFailureCounter() throws Exception {
        signIn(WRONG_PASSWORD).andExpect(status().isUnauthorized());
        assertThat(failedAttempts()).isEqualTo(1);

        signIn(PASSWORD).andExpect(status().isOk());

        assertThat(failedAttempts())
                .as("the counter is for *consecutive* failures, so a success must zero it — "
                        + "otherwise a user who mistypes once a month is eventually locked out")
                .isZero();
    }

    // ------------------------------------------------------------------------ helpers

    private ResultActions signIn(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(EMAIL, password)));
    }

    /** Drives the account into a lock through the real endpoint, and proves it landed. */
    private void lockTheAccount() throws Exception {
        for (int attempt = 0; attempt < authProperties.getMaxFailedAttempts(); attempt++) {
            signIn(WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }
        assertThat(lockedUntil())
                .as("precondition: %d wrong passwords should have locked the account",
                        authProperties.getMaxFailedAttempts())
                .isNotNull()
                .isAfter(Instant.now());
    }

    private void expireTheLock() {
        int updated = jdbcTemplate.update(
                "UPDATE identity.users SET locked_until = ? WHERE email = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), EMAIL);
        assertThat(updated).isEqualTo(1);
    }

    private int failedAttempts() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT failed_login_attempts FROM identity.users WHERE email = ?",
                Integer.class, EMAIL);
        assertThat(value).as("the account under test must exist").isNotNull();
        return value;
    }

    private Instant lockedUntil() {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM identity.users WHERE email = ?",
                Timestamp.class, EMAIL);
        return value == null ? null : value.toInstant();
    }
}
