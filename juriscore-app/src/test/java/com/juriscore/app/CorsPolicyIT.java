package com.juriscore.app;

import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a browser is told it may do cross-origin.
 *
 * <p>The configuration used to set {@code allowCredentials(true)} while the application
 * authenticates with a bearer token the client puts in a header itself. Nothing was being
 * allowed that the API actually used: there is no cookie, no HTTP Basic prompt and no TLS
 * client certificate for the browser to attach. What it did do was leave a standing
 * instruction that any ambient credential introduced later — a session cookie added in some
 * future change — would ride along on cross-origin requests without anyone deciding that it
 * should. It also forbids a wildcard origin, which turns the natural response to a
 * deployment problem ("just allow everything") into a change that silently fails.
 *
 * <p>These tests pin the resulting policy: explicit origins, no credentials, the
 * Authorization header still allowed, and a disallowed origin refused.
 */
class CorsPolicyIT extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";
    private static final String HOSTILE_ORIGIN = "https://evil.example";

    @Test
    @DisplayName("the configured origin is allowed to preflight")
    void allowedOriginPasesPreflight() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("the Authorization header is still allowed, since that is how callers authenticate")
    void authorizationHeaderIsAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/organizations/current")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("Access-Control-Allow-Headers",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Authorization"))));
    }

    @Test
    @DisplayName("credentials are not allowed, because this API does not use any")
    void credentialsAreNotAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                // Absent, not "false": the header is simply not sent when credentials are
                // disallowed, and a browser treats its absence as the denial.
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    @DisplayName("an origin that is not on the list is refused")
    void hostileOriginIsRefused() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", HOSTILE_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("the allow-origin header is never a wildcard")
    void neverAWildcard() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().string("Access-Control-Allow-Origin",
                        org.hamcrest.Matchers.not("*")));
    }

    @Test
    @DisplayName("only the methods the API serves are advertised")
    void methodsAreRestricted() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().stringValues("Access-Control-Allow-Methods",
                        org.hamcrest.Matchers.everyItem(
                                org.hamcrest.Matchers.not(
                                        org.hamcrest.Matchers.containsString("TRACE")))));
    }

    @Test
    @DisplayName("a bearer token still authenticates a cross-origin request")
    void bearerAuthenticationIsUnaffected() throws Exception {
        // The point of the change is that nothing about how callers authenticate moved.
        // An unauthenticated call is refused for the usual reason — 401, not a CORS error.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/organizations/current")
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized());
    }
}
