package com.juriscore.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.domain.User;
import com.juriscore.identity.repository.UserRepository;
import com.juriscore.identity.security.AuthProperties;
import com.juriscore.identity.service.LoginAttemptRecorder;
import com.juriscore.identity.service.SessionRevoker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security state written by a background transaction must not be undone by a request that
 * read the row before it.
 *
 * <h2>The mechanism</h2>
 *
 * <p>Three columns on {@code identity.users} are written by targeted bulk updates from
 * transactions of their own — {@code token_generation} by {@code SessionRevoker}, and
 * {@code failed_login_attempts} / {@code locked_until} by {@code LoginAttemptRecorder}.
 * They are written that way precisely so they survive a caller that throws.
 *
 * <p>Meanwhile ordinary request handling loads the same row as a managed entity and commits
 * it: {@code AuthService.login} stamps {@code last_login_at} on success,
 * {@code UserService.updateProfile} writes a name and phone. Those flushes carry the values
 * the entity was loaded with, and Hibernate emits every mapped column unless told otherwise
 * — so a request that read the row a moment earlier can write the pre-revocation values
 * back over it, and the {@code @Version} guard will not notice, because a bulk update does
 * not move the version either.
 *
 * <p>Both tests reproduce that interleaving deterministically rather than with threads:
 * one transaction loads the row, the security update runs to completion inside it via
 * {@code REQUIRES_NEW}, and only then does the outer transaction mutate and commit. That is
 * the real shape of the race — {@code login} holds its transaction open across a BCrypt
 * verify at cost 12, which is hundreds of milliseconds of window — with the timing made
 * certain.
 *
 * <h2>What is asserted</h2>
 *
 * <p>The invariant, not the mechanism. Whether the stale write is refused outright or
 * commits without touching the column is an implementation choice; what must never happen
 * is that a retired access token starts working again, or a locked account quietly unlocks.
 * The tests therefore tolerate the write failing and then check the security outcome.
 */
class StaleUserWriteIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StaleUserWriteIT.class);

    private static final String EMAIL = "asha@sharma-legal.test";
    private static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SessionRevoker sessionRevoker;

    @Autowired
    private LoginAttemptRecorder loginAttemptRecorder;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate inOneTransaction;

    @BeforeEach
    void prepareTransactionTemplate() {
        inOneTransaction = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("a stale User write cannot resurrect an access token that global revocation retired")
    void staleUserWriteCannotRevertTokenGeneration() throws Exception {
        String accessToken = register();
        UUID userId = userId();
        assertThat(callMe(accessToken)).as("precondition: the token works").isEqualTo(200);

        // The shape of AuthService.login's success path, with a global revocation landing
        // between the read and the commit.
        try {
            inOneTransaction.executeWithoutResult(status -> {
                User user = userRepository.findById(userId).orElseThrow();
                assertThat(user.getTokenGeneration()).isZero();

                sessionRevoker.revokeEverythingForUser(userId);

                user.setLastLoginAt(Instant.now());
            });
        } catch (RuntimeException refused) {
            // Refusing the stale write is one valid way to keep the invariant; committing it
            // without touching token_generation is another. The type is not what is under
            // test, so it is recorded rather than asserted — and if the write failed for some
            // unrelated reason, the revocation never ran and the assertions below say so.
            log.info("[stale write] racing write refused with {}", refused.getClass().getSimpleName());
        }

        assertThat(tokenGeneration())
                .as("the revocation must stand; a value of 0 means the stale entity flush wrote the "
                        + "pre-revocation snapshot back over it")
                .isEqualTo(1);
        assertThat(callMe(accessToken))
                .as("the security invariant: an access token retired by global revocation must not "
                        + "start authenticating again because an unrelated request committed afterwards")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a stale User write cannot clear a lockout recorded while it was in flight")
    void staleUserWriteCannotRevertLockout() throws Exception {
        register();
        UUID userId = userId();
        int threshold = authProperties.getMaxFailedAttempts();

        // The shape of UserService.updateProfile — a routine, self-service write — with the
        // account being brute-forced while it is open.
        try {
            inOneTransaction.executeWithoutResult(status -> {
                User user = userRepository.findById(userId).orElseThrow();
                assertThat(user.getFailedLoginAttempts()).isZero();

                for (int attempt = 0; attempt < threshold; attempt++) {
                    loginAttemptRecorder.recordFailure(userId);
                }

                user.setPhone("+91 90000 00000");
            });
        } catch (RuntimeException refused) {
            log.info("[stale write] racing write refused with {}", refused.getClass().getSimpleName());
        }

        assertThat(failedAttempts())
                .as("the counter must not fall back to the value the stale entity was holding")
                .isEqualTo(threshold);
        assertThat(lockedUntil())
                .as("locked_until must survive the concurrent profile write")
                .isNotNull()
                .isAfter(Instant.now());

        // The invariant that actually matters: the lock is still enforced at the front door.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("an ordinary profile write still commits when nothing is racing it")
    void anUncontendedUserWriteStillWorks() throws Exception {
        // Guards the fix from the other side: making stale writes fail must not make ordinary
        // ones fail. This is the same transaction shape with no security update in the middle.
        register();
        UUID userId = userId();

        inOneTransaction.executeWithoutResult(status -> {
            User user = userRepository.findById(userId).orElseThrow();
            user.setPhone("+91 90000 00001");
            user.setLastLoginAt(Instant.now());
        });

        String phone = jdbcTemplate.queryForObject(
                "SELECT phone FROM identity.users WHERE email = ?", String.class, EMAIL);
        assertThat(phone).isEqualTo("+91 90000 00001");
        assertThat(tokenGeneration()).as("an uncontended write must not disturb security state").isZero();
        assertThat(failedAttempts()).isZero();
    }

    // ------------------------------------------------------------------------ helpers

    /** Registers the firm and returns the administrator's access token. */
    private String register() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/register")
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
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("accessToken").asText();
    }

    private int callMe(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    private UUID userId() {
        return UUID.fromString(jdbcTemplate.queryForObject(
                "SELECT id::text FROM identity.users WHERE email = ?", String.class, EMAIL));
    }

    private int tokenGeneration() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT token_generation FROM identity.users WHERE email = ?", Integer.class, EMAIL);
        return value == null ? 0 : value;
    }

    private int failedAttempts() {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT failed_login_attempts FROM identity.users WHERE email = ?", Integer.class, EMAIL);
        return value == null ? 0 : value;
    }

    private Instant lockedUntil() {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT locked_until FROM identity.users WHERE email = ?", Timestamp.class, EMAIL);
        return value == null ? null : value.toInstant();
    }
}
