package com.juriscore.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the monitoring surface does and does not give away.
 *
 * <p>Actuator is the easiest way to leak a production secret: one careless addition to
 * {@code management.endpoints.web.exposure.include} publishes the JWT signing key and the
 * database password to anyone who can reach the port. Nothing about that failure is
 * visible in normal operation, so it needs a test rather than a convention.
 */
class ActuatorExposureIT extends AbstractIntegrationTest {

    /** Matches the value in application-test.yml; the point is that it must never appear. */
    private static final String TEST_JWT_SECRET =
            "dGVzdC1vbmx5LXNlY3JldC1mb3ItanVyaXNjb3JlLWludGVncmF0aW9uLXRlc3Rz";

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("health is public and reports UP with both datastores connected")
    void healthIsPublicAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("readiness covers the database, liveness does not")
    void probesAreScopedCorrectly() throws Exception {
        // Readiness gates traffic, so it must fail when the database is unusable.
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        // Liveness gates restarts. A database outage must not appear here, or the
        // platform restart-loops through an outage it cannot fix by restarting.
        MvcResult liveness = mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(liveness.getResponse().getContentAsString()).doesNotContain("db");
    }

    @Test
    @DisplayName("an anonymous caller gets a bare status, not the component breakdown")
    void anonymousHealthHasNoDetails() throws Exception {
        // show-details is when-authorized in the base profile. The local and docker
        // profiles widen it deliberately; the default must not.
        MvcResult result = mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.has("components"))
                .as("component details must not be shown to an unauthenticated caller")
                .isFalse();
    }

    @ParameterizedTest
    @DisplayName("configuration-bearing endpoints are unreachable even to a firm administrator")
    @ValueSource(strings = {
            "/actuator/env",
            "/actuator/configprops",
            "/actuator/beans",
            "/actuator/loggers",
            "/actuator/mappings",
            "/actuator/threaddump",
            "/actuator/heapdump",
            "/actuator/metrics"
    })
    void sensitiveEndpointsAreClosed(String path) throws Exception {
        String token = registerAndGetToken();

        // 403, not 401: the caller is a fully authenticated FIRM_ADMIN. This asserts the
        // role gate, not merely that authentication is required.
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("no response from the monitoring surface contains the signing secret")
    void noEndpointLeaksTheSigningSecret() throws Exception {
        String token = registerAndGetToken();

        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness",
                "/actuator/health/liveness", "/actuator/info"}) {
            String anonymous = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
            String authenticated = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();

            assertThat(anonymous).as(path + " (anonymous)")
                    .doesNotContain(TEST_JWT_SECRET).doesNotContain("juriscore.security");
            assertThat(authenticated).as(path + " (authenticated)")
                    .doesNotContain(TEST_JWT_SECRET).doesNotContain("password");
        }
    }

    private String registerAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firmName": "Sharma & Associates",
                                  "firstName": "Asha",
                                  "lastName": "Menon",
                                  "email": "asha@sharma-legal.test",
                                  "password": "Adv0cate!Chamber"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }
}
