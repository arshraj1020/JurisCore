package com.juriscore.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.casework.AbstractCaseworkIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two refreshes, one token, at the same instant.
 *
 * <p>Rotation is read-then-write: check the presented token is live, then revoke it and mint
 * its successor. Under READ COMMITTED that sequence is not safe against itself. Two requests
 * arriving together could both read the row while it was still live and both rotate it,
 * which produces two valid successor chains from one credential — and, worse, a reuse that
 * <em>reuse detection cannot see</em>, because neither transaction observed the other's
 * revocation. The security story the whole design rests on ("a second presentation means two
 * parties hold it") quietly stopped being true under concurrency.
 *
 * <p>{@code findByTokenHashForUpdate} takes {@code SELECT ... FOR UPDATE}, so the second
 * transaction blocks until the first commits and then re-reads a revoked row. This class is
 * the proof, and it is deliberately a real integration test against real PostgreSQL: row
 * locking is a database behaviour, and a mock would assert only that the code calls a method
 * whose name contains "ForUpdate".
 */
class RefreshTokenConcurrencyIT extends AbstractCaseworkIT {

    @Autowired
    private ObjectMapper objectMapper;

    private String refreshBody(String token) {
        return "{\"refreshToken\":\"%s\"}".formatted(token);
    }

    /** Signs in and returns the refresh token from the response. */
    private String signInForRefreshToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("data").path("refreshToken").asText();
    }

    private record Attempt(int status, String body) {

        boolean succeeded() {
            return status == 200;
        }
    }

    /** Fires `count` refreshes with the same token, released together. */
    private List<Attempt> refreshConcurrently(String token, int count) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(count);
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tasks.add(() -> {
                // Every thread waits here, so the requests overlap inside the transaction
                // rather than politely queueing behind each other.
                startLine.await();
                MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(refreshBody(token)))
                        .andReturn();
                return new Attempt(result.getResponse().getStatus(),
                        result.getResponse().getContentAsString());
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Attempt> attempts = new ArrayList<>();
            for (Future<Attempt> future : pool.invokeAll(tasks)) {
                attempts.add(future.get());
            }
            return attempts;
        } finally {
            pool.shutdownNow();
        }
    }

    private long liveTokenCount(String email) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM identity.refresh_tokens t
                  JOIN identity.users u ON u.id = t.user_id
                 WHERE lower(u.email) = lower(?) AND t.revoked_at IS NULL
                """, Long.class, email);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("exactly one of two simultaneous refreshes rotates the token")
    void onlyOneConcurrentRefreshSucceeds() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String refreshToken = signInForRefreshToken("asha@sharma-legal.test");

        List<Attempt> attempts = refreshConcurrently(refreshToken, 2);

        long succeeded = attempts.stream().filter(Attempt::succeeded).count();
        assertThat(succeeded)
                .as("both succeeding means two valid chains from one credential, and a "
                        + "reuse that detection never saw: %s", attempts)
                .isEqualTo(1);

        // The loser is refused under the existing policy, not with a 500 from a lock timeout
        // or a constraint violation leaking out.
        Attempt loser = attempts.stream().filter(a -> !a.succeeded()).findFirst().orElseThrow();
        assertThat(loser.status()).isEqualTo(401);
        assertThat(loser.body()).contains("REFRESH_TOKEN_INVALID");
    }

    @Test
    @DisplayName("eight simultaneous refreshes still rotate exactly once")
    void heavierConcurrencyStillRotatesOnce() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String refreshToken = signInForRefreshToken("asha@sharma-legal.test");

        List<Attempt> attempts = refreshConcurrently(refreshToken, 8);

        assertThat(attempts.stream().filter(Attempt::succeeded).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the losing request leaves no second replacement chain behind")
    void noDuplicateReplacementChain() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String refreshToken = signInForRefreshToken("asha@sharma-legal.test");

        refreshConcurrently(refreshToken, 4);

        // The database is the witness rather than the HTTP responses: one live successor,
        // and the presented token pointing at exactly one replacement.
        Long replacements = jdbcTemplate.queryForObject("""
                SELECT count(DISTINCT replaced_by) FROM identity.refresh_tokens
                 WHERE replaced_by IS NOT NULL
                """, Long.class);
        assertThat(replacements).isEqualTo(1L);
    }

    @Test
    @DisplayName("a normal, sequential refresh still works")
    void sequentialRefreshStillWorks() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String first = signInForRefreshToken("asha@sharma-legal.test");

        // The lock must not have made the ordinary path fail; this is the case that matters
        // for every real user, and a lock that serialises it into a deadlock would pass the
        // concurrency test above while breaking the product.
        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(first)))
                .andExpect(status().isOk())
                .andReturn();

        String second = json(rotated).path("data").path("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(second)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("replaying a rotated token still revokes every session")
    void reuseStillTriggersFullRevocation() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String first = signInForRefreshToken("asha@sharma-legal.test");

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(first)))
                .andExpect(status().isOk())
                .andReturn();
        String second = json(rotated).path("data").path("refreshToken").asText();

        // The old token comes back: either a replay or a thief holding a copy.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(first)))
                .andExpect(status().isUnauthorized());

        // Detection is unchanged by the lock: the successor is dead too.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(second)))
                .andExpect(status().isUnauthorized());
        assertThat(liveTokenCount("asha@sharma-legal.test")).isZero();
    }

    @Test
    @DisplayName("a token replayed after signing out is refused")
    void logoutThenReplayIsRefused() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String refreshToken = signInForRefreshToken("asha@sharma-legal.test");
        String accessToken = signIn("asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token replayed after a global revocation is refused")
    void globalRevocationThenReplayIsRefused() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String refreshToken = signInForRefreshToken("asha@sharma-legal.test");

        // What a password change does: revoke every refresh row for the user.
        jdbcTemplate.update("""
                UPDATE identity.refresh_tokens SET revoked_at = now()
                 WHERE user_id = (SELECT id FROM identity.users WHERE lower(email) = lower(?))
                """, "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(refreshToken)))
                .andExpect(status().isUnauthorized());
    }
}
