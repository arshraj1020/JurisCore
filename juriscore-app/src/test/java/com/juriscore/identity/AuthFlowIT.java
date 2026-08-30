package com.juriscore.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end authentication against a real database: sign up a firm, sign in, use the
 * token, rotate it, and confirm one firm cannot see another's members.
 */
class AuthFlowIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "Adv0cate!Chamber";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("registering a firm creates the tenant, the admin and a usable token pair")
    void registersFirmAndReturnsTokens() throws Exception {
        MvcResult result = register("Sharma & Associates", "asha@sharma-legal.test").andReturn();

        JsonNode body = json(result);
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").path("accessToken").asText()).isNotBlank();
        assertThat(body.path("data").path("refreshToken").asText()).isNotBlank();
        assertThat(body.path("data").path("user").path("role").asText()).isEqualTo("FIRM_ADMIN");
        // The response must never carry credential material.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("the same address cannot register twice")
    void rejectsDuplicateEmail() throws Exception {
        register("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("Another Firm", "asha@sharma-legal.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("a weak password is refused with field-level detail")
    void rejectsWeakPassword() throws Exception {
        String body = """
                {
                  "firmName": "Weak Password Firm",
                  "firstName": "Asha",
                  "lastName": "Menon",
                  "email": "weak@sharma-legal.test",
                  "password": "short"
                }
                """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("password"));
    }

    @Test
    @DisplayName("the access token identifies the caller on /users/me")
    void accessTokenIdentifiesCaller() throws Exception {
        String accessToken = json(register("Sharma & Associates", "asha@sharma-legal.test").andReturn())
                .path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("asha@sharma-legal.test"))
                .andExpect(jsonPath("$.data.role").value("FIRM_ADMIN"));
    }

    @Test
    @DisplayName("an unknown path answers 404 in the standard envelope, not 500")
    void unknownPathIsNotFound() throws Exception {
        // Spring reports this as NoResourceFoundException, a ServletException. Without an
        // explicit handler it reaches the catch-all and every typo becomes a 500 plus an
        // ERROR log with an incident id.
        String token = json(register("Sharma & Associates", "asha@sharma-legal.test").andReturn())
                .path("data").path("accessToken").asText();

        mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a missing required query parameter is a 400, not a 500")
    void missingRequiredParameterIsBadRequest() throws Exception {
        MvcResult registered = register("Sharma & Associates", "asha@sharma-legal.test").andReturn();
        String token = json(registered).path("data").path("accessToken").asText();
        String userId = json(registered).path("data").path("user").path("id").asText();

        mockMvc.perform(patch("/api/v1/users/" + userId + "/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("status")));
    }

    @Test
    @DisplayName("an unparseable enum value is a 400, not a 500")
    void badEnumValueIsBadRequest() throws Exception {
        MvcResult registered = register("Sharma & Associates", "asha@sharma-legal.test").andReturn();
        String token = json(registered).path("data").path("accessToken").asText();
        String userId = json(registered).path("data").path("user").path("id").asText();

        mockMvc.perform(patch("/api/v1/users/" + userId + "/status")
                        .header("Authorization", "Bearer " + token)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("a protected endpoint without a token returns the standard 401 envelope")
    void rejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("sign-in is case-insensitive on the address and rejects a wrong password")
    void signsInAndRejectsBadPassword() throws Exception {
        register("Sharma & Associates", "asha@sharma-legal.test");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "ASHA@sharma-legal.test", "password": "%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "asha@sharma-legal.test", "password": "Wr0ng!Password123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("a refresh token works once; presenting it again is treated as theft")
    void refreshTokenRotatesAndDetectsReuse() throws Exception {
        String refreshToken = json(register("Sharma & Associates", "asha@sharma-legal.test").andReturn())
                .path("data").path("refreshToken").asText();

        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn();

        String replacement = json(rotated).path("data").path("refreshToken").asText();
        assertThat(replacement).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));

        // The whole chain is revoked, so the replacement is dead too.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(replacement)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("forgot-password answers identically for known and unknown addresses")
    void forgotPasswordDoesNotLeakAccounts() throws Exception {
        register("Sharma & Associates", "asha@sharma-legal.test");

        for (String email : new String[]{"asha@sharma-legal.test", "nobody@nowhere.test"}) {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email": "%s"}
                                    """.formatted(email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Test
    @DisplayName("one firm cannot see another firm's members")
    void enforcesTenantIsolation() throws Exception {
        String firmAToken = json(register("Sharma & Associates", "asha@sharma-legal.test").andReturn())
                .path("data").path("accessToken").asText();
        MvcResult firmB = register("Rao Chambers", "vikram@rao-chambers.test").andReturn();
        String firmBUserId = json(firmB).path("data").path("user").path("id").asText();

        // Firm A holds a valid token and a real user id — from the wrong tenant.
        mockMvc.perform(get("/api/v1/users/" + firmBUserId)
                        .header("Authorization", "Bearer " + firmAToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        // Its own directory shows only itself.
        mockMvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + firmAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].email").value("asha@sharma-legal.test"));
    }

    @Test
    @DisplayName("a firm admin can invite a lawyer, who starts out invited rather than active")
    void invitesMember() throws Exception {
        String token = json(register("Sharma & Associates", "asha@sharma-legal.test").andReturn())
                .path("data").path("accessToken").asText();

        mockMvc.perform(post("/api/v1/users/invite")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "ravi@sharma-legal.test",
                                  "firstName": "Ravi",
                                  "lastName": "Kulkarni",
                                  "role": "LAWYER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("INVITED"))
                .andExpect(jsonPath("$.data.role").value("LAWYER"));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions register(String firmName, String email)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody(firmName, email)))
                .andExpect(status().isCreated());
    }

    private String registrationBody(String firmName, String email) {
        return """
                {
                  "firmName": "%s",
                  "firstName": "Asha",
                  "lastName": "Menon",
                  "email": "%s",
                  "password": "%s",
                  "timezone": "Asia/Kolkata"
                }
                """.formatted(firmName, email, PASSWORD);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
