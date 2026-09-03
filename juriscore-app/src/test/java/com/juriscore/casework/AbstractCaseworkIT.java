package com.juriscore.casework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.event.UserInvitedEvent;
import com.juriscore.support.CapturingEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared scaffolding for the casework integration tests.
 *
 * <p>Everything here goes through the real API. A firm exists because somebody signed up;
 * a lawyer exists because an administrator invited them and they set a password; a token
 * exists because somebody signed in. Seeding rows directly would let a test pass against
 * a state the application cannot actually produce — the exception is the platform
 * administrator, which Phase 1 has no bootstrap path for and an operator really does
 * insert by hand.
 */
@Import(CapturingEventListener.class)
abstract class AbstractCaseworkIT extends AbstractIntegrationTest {

    protected static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected CapturingEventListener events;

    @BeforeEach
    void clearCapturedEvents() {
        events.clear();
    }

    /** A firm, and a token for the administrator who created it by signing up. */
    protected record Firm(String id, String adminEmail, String adminToken) {
    }

    protected Firm registerFirm(String name, String email) throws Exception {
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
        return new Firm(data.path("user").path("organizationId").asText(), email,
                data.path("accessToken").asText());
    }

    /** Invites a member, activates them through the emailed token, and signs them in. */
    protected String inviteAndActivate(Firm firm, String email, String role) throws Exception {
        events.clear();
        mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", bearer(firm.adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","firstName":"Ravi","lastName":"Kulkarni","role":"%s"}
                                """.formatted(email, role)))
                .andExpect(status().isCreated());

        String activationToken = events.require(UserInvitedEvent.class).getActivationToken();
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","newPassword":"%s"}
                                """.formatted(activationToken, PASSWORD)))
                .andExpect(status().isOk());

        return signIn(email);
    }

    protected String signIn(String email) throws Exception {
        MvcResult signedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json(signedIn).path("data").path("accessToken").asText();
    }

    protected UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM identity.users WHERE lower(email) = lower(?)", UUID.class, email);
    }

    protected void suspend(Firm firm, String email) throws Exception {
        mockMvc.perform(patch("/api/v1/users/" + userIdOf(email) + "/status")
                        .header("Authorization", bearer(firm.adminToken()))
                        .param("status", "SUSPENDED"))
                .andExpect(status().isOk());
    }

    /**
     * The platform administrator an operator would create by hand, reusing an existing
     * account's password hash so the test does not have to carry a precomputed one.
     */
    protected String platformAdminToken(String borrowHashFrom) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO identity.users
                    (id, version, created_at, updated_at, organization_id, email, password_hash,
                     first_name, last_name, role, status, failed_login_attempts, token_generation)
                SELECT gen_random_uuid(), 0, now(), now(), NULL, 'platform@juriscore.test',
                       password_hash, 'Platform', 'Admin', 'SUPER_ADMIN', 'ACTIVE', 0, 0
                  FROM identity.users WHERE email = ?
                """, borrowHashFrom);
        return signIn("platform@juriscore.test");
    }

    // ------------------------------------------------------------- casework fixtures

    protected String clientBody(String displayName, String email) {
        return """
                {
                  "displayName": "%s",
                  "clientType": "INDIVIDUAL",
                  "email": %s,
                  "phone": "+91 22 5550 1234",
                  "addressLine1": "12 Marine Drive",
                  "city": "Mumbai",
                  "state": "Maharashtra",
                  "country": "India",
                  "postalCode": "400020",
                  "notes": "Referred by counsel"
                }
                """.formatted(displayName, email == null ? "null" : "\"" + email + "\"");
    }

    protected String createClient(String token, String displayName, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clientBody(displayName, email)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    protected String createCase(String token, String clientId, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"Tenancy dispute","clientId":"%s"}
                                """.formatted(title, clientId)))
                .andExpect(status().isCreated())
                .andReturn();
        return json(result).path("data").path("id").asText();
    }

    protected void changeStatus(String token, String caseId, String status, int expectedStatus)
            throws Exception {
        mockMvc.perform(patch("/api/v1/cases/" + caseId + "/status")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"%s\"}".formatted(status)))
                .andExpect(status().is(expectedStatus));
    }

    protected void assign(String adminToken, String caseId, UUID lawyerUserId, Boolean lead,
                          int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/cases/" + caseId + "/assignments")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lead == null
                                ? "{\"lawyerUserId\":\"%s\"}".formatted(lawyerUserId)
                                : "{\"lawyerUserId\":\"%s\",\"lead\":%s}".formatted(lawyerUserId, lead)))
                .andExpect(status().is(expectedStatus));
    }

    protected boolean isLead(String caseId, UUID lawyerUserId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT is_lead FROM casework.case_assignments
                 WHERE case_id = ?::uuid AND lawyer_user_id = ?
                """, Boolean.class, caseId, lawyerUserId));
    }

    protected long leadCount(String caseId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM casework.case_assignments
                 WHERE case_id = ?::uuid AND is_lead
                """, Long.class, caseId);
        return count == null ? 0 : count;
    }

    // ----------------------------------------------------------------------- plumbing

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
