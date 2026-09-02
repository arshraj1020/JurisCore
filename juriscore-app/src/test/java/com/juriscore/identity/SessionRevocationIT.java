package com.juriscore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What "revoke every session" has to mean.
 *
 * <p>JurisCore has two independent things to kill when a session ends: the refresh token
 * row, and the access tokens already issued from it. Revoking the row is not enough on its
 * own — an access token is a self-contained bearer credential that the API accepts for its
 * full 15-minute life. The mechanism that stops it is {@code users.token_generation}:
 * every access token carries the generation it was minted under, and
 * {@code JwtAuthenticationFilter} compares that to the row on every single request.
 *
 * <p>{@code UserService} already treats the two as one operation — suspending a member and
 * changing a member's role each bump the generation <em>and</em> revoke the refresh tokens,
 * for the reason its own javadoc gives: "Leaving them signed in until their access token
 * expires would mean a lawyer removed from a firm keeps reading case files for the rest of
 * the token's life."
 *
 * <p>These tests hold the two paths that make the same promise to the same standard:
 * detected refresh-token theft, and signing out of every session. They also pin the
 * boundary — signing out of <em>one</em> session must not log the user out everywhere.
 */
class SessionRevocationIT extends AbstractIntegrationTest {

    private static final String EMAIL = "asha@sharma-legal.test";
    private static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Test
    @DisplayName("detected refresh-token reuse also kills the access tokens already issued")
    void reuseDetectionInvalidatesLiveAccessTokens() throws Exception {
        JsonNode session = register();
        String stolenRefresh = session.path("refreshToken").asText();

        // The thief rotates the stolen token first and banks the access token it returns.
        JsonNode thief = refresh(stolenRefresh, status().isOk());
        String thiefAccessToken = thief.path("accessToken").asText();
        assertThat(callMe(thiefAccessToken)).isEqualTo(200);

        // The real owner then presents the same token, which is what exposes the theft.
        refresh(stolenRefresh, status().isUnauthorized());

        assertThat(usableRefreshTokens())
                .as("every refresh token for the user must be revoked on reuse")
                .isZero();
        assertThat(callMe(thiefAccessToken))
                .as("the access token the thief obtained must stop working too. Revoking only the "
                        + "refresh rows leaves them with a live bearer credential for the rest of its "
                        + "15-minute life — the platform detects the theft and then does nothing about "
                        + "the access it is currently granting")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("signing out of every session kills the access tokens already issued")
    void signOutEverywhereInvalidatesLiveAccessTokens() throws Exception {
        JsonNode session = register();
        String accessToken = session.path("accessToken").asText();
        assertThat(callMe(accessToken)).isEqualTo(200);

        // No body: the endpoint documents this as "every session".
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(usableRefreshTokens()).isZero();
        assertThat(callMe(accessToken))
                .as("a user who signs out of every session because they believe they are compromised "
                        + "must not still be signed in")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("signing out of one session leaves the other sessions alone")
    void signingOutOneSessionDoesNotSignOutEverywhere() throws Exception {
        // The boundary on the two tests above. Ending one session is a routine action, not a
        // security response, and it must not invalidate credentials the user is still using
        // on another device.
        register();
        JsonNode phone = login();
        JsonNode laptop = login();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + phone.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(phone.path("refreshToken").asText())))
                .andExpect(status().isOk());

        assertThat(callMe(laptop.path("accessToken").asText()))
                .as("the other device's access token must survive")
                .isEqualTo(200);
        refresh(laptop.path("refreshToken").asText(), status().isOk());
    }

    @Test
    @DisplayName("global revocation is a line in time: tokens minted before it die, tokens minted after it live")
    void globalRevocationSeparatesTokensByIssueTime() throws Exception {
        // Revocation that killed everything forever would pass the two tests above and be
        // useless. This pins the other edge: signing back in must produce a working session.
        register();
        String before = login().path("accessToken").asText();
        assertThat(callMe(before)).isEqualTo(200);

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + before))
                .andExpect(status().isOk());

        assertThat(callMe(before))
                .as("minted before the revocation")
                .isEqualTo(401);

        JsonNode after = login();
        assertThat(callMe(after.path("accessToken").asText()))
                .as("minted after the revocation — a token issued from a fresh sign-in must carry the "
                        + "current generation and be accepted")
                .isEqualTo(200);
        refresh(after.path("refreshToken").asText(), status().isOk());
    }

    @Test
    @DisplayName("two simultaneous refreshes of one token cannot both produce a session")
    void concurrentRefreshOfOneTokenIssuesAtMostOneSession() throws Exception {
        String refreshToken = register().path("refreshToken").asText();

        // Driven through the service rather than MockMvc: the invariant is transactional, and
        // this is the layer that owns the transaction. Whichever way the two interleave, the
        // assertions below hold — one wins outright, or the loser is caught as reuse.
        CyclicBarrier bothReady = new CyclicBarrier(2);
        Callable<Boolean> attempt = () -> {
            bothReady.await();
            try {
                authService.refresh(refreshToken, AuthService.RequestContext.unknown());
                return true;
            } catch (RuntimeException expectedForTheLoser) {
                return false;
            }
        };

        int succeeded = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<Boolean> outcome : pool.invokeAll(List.of(attempt, attempt))) {
                if (outcome.get()) {
                    succeeded++;
                }
            }
        }

        assertThat(succeeded)
                .as("exactly one of two simultaneous refreshes of the same token may succeed; two "
                        + "would mean one presented token had produced two live sessions")
                .isEqualTo(1);
        assertThat(usableRefreshTokens())
                .as("one live token after a clean rotation, none if the loser was treated as reuse")
                .isLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------------ helpers

    private JsonNode register() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
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
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode refresh(String refreshToken,
                             org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(expected)
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private int callMe(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    private int usableRefreshTokens() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM identity.refresh_tokens t
                  JOIN identity.users u ON u.id = t.user_id
                 WHERE u.email = ? AND t.revoked_at IS NULL
                """, Integer.class, EMAIL);
        return count == null ? 0 : count;
    }

}
