package com.juriscore.organization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import com.juriscore.identity.event.UserInvitedEvent;
import com.juriscore.support.CapturingEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The organization module against a real database.
 *
 * <p>Firms are provisioned as a side effect of self-serve registration — there is no
 * "create firm" endpoint — so everything here starts from a sign-up. What is worth holding
 * down is the handle (derived, unique, immutable), the tenant boundary on a profile that
 * only ever addresses itself as {@code /current}, and the one endpoint in Phase 1 that
 * deliberately crosses tenants.
 *
 * <p>That last endpoint had no coverage at all: {@code /organizations/{id}} is
 * {@code SUPER_ADMIN}-only, and Phase 1 has no bootstrap path for that role, so the only
 * way to reach it is to insert the row the way an operator would. The negative case is in
 * {@code SecurityGuaranteesIT}; the positive case is here, so the endpoint is exercised in
 * both directions.
 */
@Import(CapturingEventListener.class)
class OrganizationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingEventListener events;

    @BeforeEach
    void clearEvents() {
        events.clear();
    }

    // -------------------------------------------------------------------- provisioning

    @Test
    @DisplayName("registering provisions an active firm with a handle derived from its name")
    void registrationProvisionsAnActiveFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        assertThat(column(firm.id(), "slug")).isEqualTo("sharma-associates");
        assertThat(column(firm.id(), "status")).isEqualTo("ACTIVE");
        assertThat(column(firm.id(), "contact_email")).isEqualTo("asha@sharma-legal.test");
        assertThat(column(firm.id(), "timezone"))
                .as("registration omitted a timezone, so the documented default applies")
                .isEqualTo("Asia/Kolkata");
    }

    @Test
    @DisplayName("two firms with the same name get distinct handles")
    void identicallyNamedFirmsGetDistinctHandles() throws Exception {
        Firm first = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm second = registerFirm("Sharma & Associates", "ravi@other-firm.test");

        assertThat(column(first.id(), "slug")).isEqualTo("sharma-associates");
        assertThat(column(second.id(), "slug"))
                .as("the unique index is the real arbiter; the counter keeps the common case readable")
                .isEqualTo("sharma-associates-2");
    }

    @Test
    @DisplayName("a rejected registration leaves no orphan firm behind")
    void rejectedRegistrationLeavesNoOrphanFirm() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration("Another Firm Entirely", "asha@sharma-legal.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));

        assertThat(firmCount())
                .as("a firm must never be provisioned for a sign-up that then fails")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ the tenant view

    @Test
    @DisplayName("the current-firm endpoint returns the caller's own firm and nobody else's")
    void currentReturnsOnlyTheCallersFirm() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(get("/api/v1/organizations/current").header("Authorization", bearer(mine.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(mine.id()))
                .andExpect(jsonPath("$.data.slug").value("sharma-associates"));

        mockMvc.perform(get("/api/v1/organizations/current").header("Authorization", bearer(theirs.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(theirs.id()));
    }

    @Test
    @DisplayName("a firm admin can edit the profile, and the handle survives the rename")
    void firmAdminCanEditTheProfileButNotTheHandle() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(put("/api/v1/organizations/current")
                        .header("Authorization", bearer(firm.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("Sharma Legal LLP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Sharma Legal LLP"))
                .andExpect(jsonPath("$.data.slug").value("sharma-associates"));

        assertThat(column(firm.id(), "name")).isEqualTo("Sharma Legal LLP");
        assertThat(column(firm.id(), "city")).isEqualTo("Mumbai");
        assertThat(column(firm.id(), "slug")).isEqualTo("sharma-associates");
    }

    @Test
    @DisplayName("one firm's edit does not touch another firm")
    void oneFirmsEditDoesNotTouchAnother() throws Exception {
        Firm mine = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        Firm theirs = registerFirm("Kulkarni Chambers", "ravi@kulkarni-legal.test");

        mockMvc.perform(put("/api/v1/organizations/current")
                        .header("Authorization", bearer(mine.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("Sharma Legal LLP")))
                .andExpect(status().isOk());

        assertThat(column(theirs.id(), "name")).isEqualTo("Kulkarni Chambers");
        assertThat(column(theirs.id(), "city")).isNull();
    }

    @Test
    @DisplayName("a lawyer cannot edit the firm profile")
    void aLawyerCannotEditTheFirmProfile() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String lawyerToken = inviteAndActivate(firm, "ravi@sharma-legal.test", "LAWYER");

        mockMvc.perform(put("/api/v1/organizations/current")
                        .header("Authorization", bearer(lawyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("Renamed By A Lawyer")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));

        assertThat(column(firm.id(), "name")).isEqualTo("Sharma & Associates");
    }

    @Test
    @DisplayName("an edit with a blank name is refused and changes nothing")
    void aBlankNameIsRefused() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(put("/api/v1/organizations/current")
                        .header("Authorization", bearer(firm.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(update("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        assertThat(column(firm.id(), "name")).isEqualTo("Sharma & Associates");
    }

    // ------------------------------------------------------------ the platform endpoint

    @Test
    @DisplayName("a platform administrator can read any firm")
    void aPlatformAdminCanReadAnyFirm() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String platformToken = platformAdminToken("asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/organizations/" + firm.id())
                        .header("Authorization", bearer(platformToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(firm.id()))
                .andExpect(jsonPath("$.data.slug").value("sharma-associates"));
    }

    @Test
    @DisplayName("a platform administrator belongs to no firm, so the current-firm endpoint refuses them")
    void aPlatformAdminHasNoFirmOfTheirOwn() throws Exception {
        registerFirm("Sharma & Associates", "asha@sharma-legal.test");
        String platformToken = platformAdminToken("asha@sharma-legal.test");

        mockMvc.perform(get("/api/v1/organizations/current").header("Authorization", bearer(platformToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
    }

    @Test
    @DisplayName("a firm admin is refused the platform endpoint even for a firm that does not exist")
    void thePlatformEndpointLeaksNoExistence() throws Exception {
        Firm firm = registerFirm("Sharma & Associates", "asha@sharma-legal.test");

        // 403 rather than 404: authorization is decided before the lookup, so the endpoint
        // cannot be used to probe which firm ids are real.
        mockMvc.perform(get("/api/v1/organizations/" + UUID.randomUUID())
                        .header("Authorization", bearer(firm.token())))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------ helpers

    private record Firm(String id, String token) {
    }

    private Firm registerFirm(String name, String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration(name, email)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode data = json(result).path("data");
        return new Firm(data.path("user").path("organizationId").asText(), data.path("accessToken").asText());
    }

    private String inviteAndActivate(Firm firm, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", bearer(firm.token()))
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

        MvcResult signedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json(signedIn).path("data").path("accessToken").asText();
    }

    /**
     * Inserts the platform administrator an operator would have to create by hand — Phase 1
     * has no bootstrap path for the role — reusing an existing account's password hash so the
     * test does not have to carry a precomputed one.
     */
    private String platformAdminToken(String borrowHashFrom) throws Exception {
        jdbcTemplate.update("""
                INSERT INTO identity.users
                    (id, version, created_at, updated_at, organization_id, email, password_hash,
                     first_name, last_name, role, status, failed_login_attempts, token_generation)
                SELECT gen_random_uuid(), 0, now(), now(), NULL, 'platform@juriscore.test',
                       password_hash, 'Platform', 'Admin', 'SUPER_ADMIN', 'ACTIVE', 0, 0
                  FROM identity.users WHERE email = ?
                """, borrowHashFrom);

        MvcResult signedIn = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"platform@juriscore.test","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json(signedIn).path("data").path("accessToken").asText();
    }

    private String registration(String firmName, String email) {
        return """
                {
                  "firmName": "%s",
                  "firstName": "Asha",
                  "lastName": "Menon",
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(firmName, email, PASSWORD);
    }

    private String update(String name) {
        return """
                {
                  "name": "%s",
                  "contactEmail": "billing@sharma-legal.test",
                  "contactPhone": "+91 22 1234 5678",
                  "addressLine1": "12 Fort Street",
                  "city": "Mumbai",
                  "state": "Maharashtra",
                  "country": "India",
                  "postalCode": "400001",
                  "registrationNumber": "REG-99"
                }
                """.formatted(name);
    }

    private String column(String organizationId, String name) {
        return jdbcTemplate.queryForObject(
                "SELECT " + name + "::text FROM organization.organizations WHERE id = ?::uuid",
                String.class, organizationId);
    }

    private int firmCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM organization.organizations", Integer.class);
        return count == null ? 0 : count;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
