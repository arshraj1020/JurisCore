package com.juriscore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.event.PasswordResetRequestedEvent;
import com.juriscore.identity.event.UserInvitedEvent;
import com.juriscore.support.CapturingEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security properties Phase 1 claims, each asserted against a real database.
 *
 * <p>These are deliberately behavioural rather than unit tests: every one of them
 * describes a promise made to a law firm — a suspended lawyer loses access, a reset link
 * works once, one firm cannot see another — and a promise that only holds in a mock is
 * not a promise.
 */
@Import(CapturingEventListener.class)
class SecurityGuaranteesIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Adv0cate!Chamber";
    private static final String NEW_PASSWORD = "N3w!ChamberSecret";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingEventListener events;

    @BeforeEach
    void clearEvents() {
        events.clear();
    }

    // ================================================================= token revocation

    @Nested
    @DisplayName("An access token stops working the moment the account behind it changes")
    class TokenRevocation {

        @Test
        @DisplayName("suspending a lawyer kills the token they are already holding")
        void suspensionInvalidatesLiveToken() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Member lawyer = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

            // The token works right now.
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(lawyer.accessToken())))
                    .andExpect(status().isOk());

            mockMvc.perform(patch("/api/v1/users/" + lawyer.id() + "/status")
                            .header("Authorization", bearer(firm.adminAccessToken()))
                            .param("status", "SUSPENDED"))
                    .andExpect(status().isOk());

            // Same token, seconds later, well inside its 15-minute lifetime.
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(lawyer.accessToken())))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }

        @Test
        @DisplayName("changing a role kills tokens minted under the old one")
        void roleChangeInvalidatesLiveToken() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Member lawyer = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

            mockMvc.perform(patch("/api/v1/users/" + lawyer.id() + "/role")
                            .header("Authorization", bearer(firm.adminAccessToken()))
                            .param("role", "CLERK"))
                    .andExpect(status().isOk());

            // The role is baked into the token, so the stale one must not survive.
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(lawyer.accessToken())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("changing a password kills every token issued before it")
        void passwordChangeInvalidatesLiveToken() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

            mockMvc.perform(post("/api/v1/users/me/change-password")
                            .header("Authorization", bearer(firm.adminAccessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"currentPassword": "%s", "newPassword": "%s"}
                                    """.formatted(PASSWORD, NEW_PASSWORD)))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(firm.adminAccessToken())))
                    .andExpect(status().isUnauthorized());

            assertThat(tokenGeneration(firm.adminId())).isEqualTo(1);
        }
    }

    // ================================================================== refresh tokens

    @Nested
    @DisplayName("Refresh tokens rotate, and a replayed one burns the whole chain")
    class RefreshRotation {

        @Test
        @DisplayName("a refresh returns a new token and revokes the presented one")
        void rotationRevokesPresentedToken() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

            String rotated = json(refresh(firm.refreshToken()).andExpect(status().isOk()).andReturn())
                    .path("data").path("refreshToken").asText();

            assertThat(rotated).isNotEqualTo(firm.refreshToken());
            assertThat(revokedTokenCount(firm.adminId())).isEqualTo(1);
            assertThat(liveTokenCount(firm.adminId())).isEqualTo(1);
            // The replacement is linked to what it replaced, so a chain is auditable.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM identity.refresh_tokens WHERE replaced_by IS NOT NULL",
                    Integer.class)).isEqualTo(1);
        }

        /**
         * The behaviour this asserts is easy to write and easy to get silently wrong:
         * the reuse response is a failure, and a failure rolls the request's transaction
         * back. If the revocation rode in that same transaction it would be undone, and
         * the thief would keep a working chain while the test still saw its 401. Asserting
         * the database state <em>after</em> the failed request is the only way to tell the
         * two implementations apart.
         */
        @Test
        @DisplayName("replaying a rotated token revokes every session, and that revocation survives the failed request")
        void reuseRevocationCommitsInItsOwnTransaction() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            String rotated = json(refresh(firm.refreshToken()).andExpect(status().isOk()).andReturn())
                    .path("data").path("refreshToken").asText();

            assertThat(liveTokenCount(firm.adminId())).isEqualTo(1);

            refresh(firm.refreshToken())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));

            // REQUIRES_NEW proof: the request failed, yet nothing usable is left behind.
            assertThat(liveTokenCount(firm.adminId()))
                    .as("every session must be revoked even though the request threw")
                    .isZero();

            // And the replacement the thief may also hold is dead.
            refresh(rotated).andExpect(status().isUnauthorized());
        }
    }

    // ================================================================== password reset

    @Nested
    @DisplayName("Password reset links")
    class PasswordReset {

        /**
         * Regression test. The reset token was previously marked used on an entity that a
         * bulk update had just detached from the persistence context, so the write was
         * silently dropped and the link stayed usable for its full 30-minute life.
         */
        @Test
        @DisplayName("a reset link works exactly once")
        void resetLinkIsSingleUse() throws Exception {
            registerFirm("Sharma & Associates", "asha@sharma-legal.test");

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "asha@sharma-legal.test"}
                                    """))
                    .andExpect(status().isOk());

            String resetToken = events.require(PasswordResetRequestedEvent.class).getResetToken();

            resetPassword(resetToken, NEW_PASSWORD).andExpect(status().isOk());

            // Same link again — a forwarded email, a browser history entry, a proxy log.
            resetPassword(resetToken, "An0ther!PasswordX").andExpect(status().is4xxClientError());

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM identity.password_reset_tokens WHERE used_at IS NULL",
                    Integer.class))
                    .as("the consumed token must be marked used in the database")
                    .isZero();

            // The first reset stuck; the second did not.
            login("asha@sharma-legal.test", NEW_PASSWORD).andExpect(status().isOk());
            login("asha@sharma-legal.test", "An0ther!PasswordX").andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("completing a reset revokes sessions opened with the old password")
        void resetRevokesExistingSessions() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "asha@sharma-legal.test"}
                                    """))
                    .andExpect(status().isOk());
            resetPassword(events.require(PasswordResetRequestedEvent.class).getResetToken(), NEW_PASSWORD)
                    .andExpect(status().isOk());

            assertThat(liveTokenCount(firm.adminId())).isZero();
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(firm.adminAccessToken())))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============================================================================= RBAC

    @Nested
    @DisplayName("Role restrictions")
    class Rbac {

        @Test
        @DisplayName("a lawyer cannot invite members or change roles")
        void lawyerCannotPerformAdminActions() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Member lawyer = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

            mockMvc.perform(post("/api/v1/users/invite")
                            .header("Authorization", bearer(lawyer.accessToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"new@sharma-legal.test","firstName":"N","lastName":"P","role":"CLERK"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

            mockMvc.perform(patch("/api/v1/users/" + firm.adminId() + "/role")
                            .header("Authorization", bearer(lawyer.accessToken()))
                            .param("role", "CLERK"))
                    .andExpect(status().isForbidden());

            mockMvc.perform(patch("/api/v1/users/" + firm.adminId() + "/status")
                            .header("Authorization", bearer(lawyer.accessToken()))
                            .param("status", "SUSPENDED"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a lawyer may still read their own firm's directory")
        void lawyerCanReadDirectory() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Member lawyer = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

            mockMvc.perform(get("/api/v1/users").header("Authorization", bearer(lawyer.accessToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalItems").value(2));
        }

        @Test
        @DisplayName("a firm cannot be left without an active administrator")
        void lastAdminCannotBeSuspended() throws Exception {
            Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

            mockMvc.perform(patch("/api/v1/users/" + firm.adminId() + "/status")
                            .header("Authorization", bearer(firm.adminAccessToken()))
                            .param("status", "SUSPENDED"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ILLEGAL_STATE_TRANSITION"));
        }
    }

    // ================================================================ tenant isolation

    @Nested
    @DisplayName("Tenant isolation")
    class TenantIsolation {

        @Test
        @DisplayName("a valid token from firm A cannot read, suspend or re-role firm B's people")
        void writesAreTenantScopedToo() throws Exception {
            Firm firmA = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Firm firmB = registerFirm("Rao Chambers", "vikram@rao-chambers.test");

            // Reads: absent, not forbidden.
            mockMvc.perform(get("/api/v1/users/" + firmB.adminId())
                            .header("Authorization", bearer(firmA.adminAccessToken())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

            // Writes: firm A is a FIRM_ADMIN, so RBAC passes and only the tenant predicate
            // stands between it and another firm's user. This is the case worth testing.
            mockMvc.perform(patch("/api/v1/users/" + firmB.adminId() + "/status")
                            .header("Authorization", bearer(firmA.adminAccessToken()))
                            .param("status", "SUSPENDED"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(patch("/api/v1/users/" + firmB.adminId() + "/role")
                            .header("Authorization", bearer(firmA.adminAccessToken()))
                            .param("role", "CLERK"))
                    .andExpect(status().isNotFound());

            // Firm B is untouched.
            mockMvc.perform(get("/api/v1/users/me").header("Authorization", bearer(firmB.adminAccessToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("FIRM_ADMIN"));
        }

        @Test
        @DisplayName("the organization endpoint returns only the caller's own firm")
        void organizationIsScopedToCaller() throws Exception {
            Firm firmA = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            registerFirm("Rao Chambers", "vikram@rao-chambers.test");

            mockMvc.perform(get("/api/v1/organizations/current")
                            .header("Authorization", bearer(firmA.adminAccessToken())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("Sharma & Associates"))
                    .andExpect(jsonPath("$.data.slug").value("sharma-associates"));
        }

        @Test
        @DisplayName("a firm admin cannot read another firm through the platform-admin endpoint")
        void platformAdminEndpointIsClosedToFirmAdmins() throws Exception {
            Firm firmA = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
            Firm firmB = registerFirm("Rao Chambers", "vikram@rao-chambers.test");

            mockMvc.perform(get("/api/v1/organizations/" + firmB.organizationId())
                            .header("Authorization", bearer(firmA.adminAccessToken())))
                    .andExpect(status().isForbidden());
        }
    }

    // ========================================================================== helpers

    private record Firm(String organizationId, String adminId, String adminAccessToken,
                        String refreshToken) {
    }

    private record Member(String id, String accessToken) {
    }

    private Firm registerFirm(String name, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firmName": "%s",
                                  "firstName": "Asha",
                                  "lastName": "Menon",
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(name, email, PASSWORD)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = json(result).path("data");
        return new Firm(
                data.path("user").path("organizationId").asText(),
                data.path("user").path("id").asText(),
                data.path("accessToken").asText(),
                data.path("refreshToken").asText());
    }

    /** Invites a member and completes activation through the reset-password flow. */
    private Member inviteAndActivate(Firm firm, String email, String role) throws Exception {
        MvcResult invited = mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", bearer(firm.adminAccessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","firstName":"Ravi","lastName":"Kulkarni","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = json(invited).path("data").path("id").asText();

        String activationToken = events.require(UserInvitedEvent.class).getActivationToken();
        resetPassword(activationToken, PASSWORD).andExpect(status().isOk());

        String accessToken = json(login(email, PASSWORD).andExpect(status().isOk()).andReturn())
                .path("data").path("accessToken").asText();
        return new Member(id, accessToken);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken": "%s"}
                        """.formatted(refreshToken)));
    }

    private ResultActions resetPassword(String token, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token": "%s", "newPassword": "%s"}
                        """.formatted(token, newPassword)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private int liveTokenCount(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ?::uuid AND revoked_at IS NULL",
                Integer.class, userId);
    }

    private int revokedTokenCount(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ?::uuid AND revoked_at IS NOT NULL",
                Integer.class, userId);
    }

    private int tokenGeneration(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT token_generation FROM identity.users WHERE id = ?::uuid", Integer.class, userId);
    }
}
