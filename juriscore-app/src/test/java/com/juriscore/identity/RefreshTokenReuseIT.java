package com.juriscore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a revoked refresh token means depends on why it was revoked.
 *
 * <h2>The distinction</h2>
 *
 * <p>{@code revoked_at} is set by three different events: rotation, signing out of one
 * session, and global revocation. Only the first is evidence of theft. A rotated token has
 * already been exchanged for a successor, so a second presentation means two parties hold
 * it — the legitimate client and someone else. A token revoked because the user signed out
 * has been exchanged for nothing; presenting it again is what a stale mobile client does
 * when it retries a queued request.
 *
 * <p>Treating all three alike made replaying any old revoked token a way to destroy the
 * account's current sessions — including a session created after the revocation. Anyone
 * holding a long-dead refresh token from an old device, a browser history entry or a backup
 * could keep an account signed out indefinitely, and every false alarm diluted the log line
 * that is supposed to mean "this account has been compromised".
 *
 * <p>{@code replaced_by} is the discriminator, and it already exists: it is written in the
 * same statement that rotates a token and nowhere else, and the bulk revocation used by
 * logout skips rows that are already revoked, so a rotated token keeps it. These tests hold
 * the three cases apart.
 */
class RefreshTokenReuseIT extends AbstractIntegrationTest {

    private static final String EMAIL = "asha@sharma-legal.test";
    private static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("replaying a ROTATED token is still treated as theft and burns the account's sessions")
    void replayingARotatedTokenStillTriggersTheTheftResponse() throws Exception {
        JsonNode original = register();
        String rotatedAway = original.path("refreshToken").asText();

        JsonNode successor = refresh(rotatedAway, status().isOk());
        assertThat(callMe(successor.path("accessToken").asText())).isEqualTo(200);

        // Second presentation of a token that was already exchanged: two parties hold it.
        refresh(rotatedAway, status().isUnauthorized());

        assertThat(callMe(successor.path("accessToken").asText()))
                .as("detection must not be weakened — the successor session dies with the chain")
                .isEqualTo(401);
        refresh(successor.path("refreshToken").asText(), status().isUnauthorized());
    }

    @Test
    @DisplayName("replaying a token revoked by single-session logout does not touch other sessions")
    void replayingALogoutRevokedTokenDoesNotRevokeTheAccount() throws Exception {
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

        // The stale client retries. It was never rotated, so this is not evidence of anything.
        refreshExpectingInvalid(phone.path("refreshToken").asText());

        assertThat(callMe(laptop.path("accessToken").asText()))
                .as("the other device must not be signed out by someone replaying a token that was "
                        + "already dead and had never been exchanged")
                .isEqualTo(200);
        refresh(laptop.path("refreshToken").asText(), status().isOk());
    }

    @Test
    @DisplayName("replaying a globally revoked token does not destroy the session created afterwards")
    void replayingAGloballyRevokedTokenDoesNotRevokeTheNewSession() throws Exception {
        JsonNode first = register();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + first.path("accessToken").asText()))
                .andExpect(status().isOk());

        JsonNode fresh = login();
        assertThat(callMe(fresh.path("accessToken").asText())).isEqualTo(200);

        // The replay that used to be an account-level denial of service: hold any old revoked
        // token, present it in a loop, and the victim is signed out every time they sign in.
        refreshExpectingInvalid(first.path("refreshToken").asText());

        assertThat(callMe(fresh.path("accessToken").asText()))
                .as("the session created after the revocation must survive the replay")
                .isEqualTo(200);
        refresh(fresh.path("refreshToken").asText(), status().isOk());
    }

    @Test
    @DisplayName("a new session survives replay of a token retired by an earlier single-session logout")
    void aNewSessionSurvivesReplayOfAnOldLogoutRevokedToken() throws Exception {
        JsonNode old = register();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + old.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(old.path("refreshToken").asText())))
                .andExpect(status().isOk());

        JsonNode fresh = login();

        refreshExpectingInvalid(old.path("refreshToken").asText());

        assertThat(callMe(fresh.path("accessToken").asText()))
                .as("signing back in after a logout must not be undone by the old token resurfacing")
                .isEqualTo(200);
        refresh(fresh.path("refreshToken").asText(), status().isOk());
    }

    // ------------------------------------------------------------------------ helpers

    /** The ordinary rejection: unusable token, no alarm, no side effects. */
    private void refreshExpectingInvalid(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));
    }

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
        return data(result);
    }

    private JsonNode login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode refresh(String refreshToken, ResultMatcher expected) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(expected)
                .andReturn();
        return data(result);
    }

    private int callMe(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
