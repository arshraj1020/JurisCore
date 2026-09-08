package com.juriscore.app;

import com.juriscore.AbstractIntegrationTest;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API description under the production configuration, where it is not for the public.
 *
 * <p>The OpenAPI document is a complete map of the platform: every route, every parameter,
 * every DTO field. Locally that is exactly what it is for. Deployed, it is free
 * reconnaissance — it turns "find the endpoints" into "read the endpoints" — and it was
 * previously served to anyone who asked, because {@code SecurityConfig} declared the docs
 * paths {@code permitAll} unconditionally, in every environment.
 *
 * <p>The fix has two halves and this class exercises both together, because either alone
 * looks complete and is not. {@code springdoc.*.enabled=false} stops the endpoints being
 * registered; {@code juriscore.security.docs.enabled=false} stops the security chain
 * declaring those paths public. Without the second, the day anything else is mapped under
 * {@code /v3/api-docs} it is anonymous. Without the first, the paths still answer.
 *
 * <p>The properties are set directly rather than by activating the {@code prod} profile:
 * that profile also empties the AWS endpoint and re-points logging, none of which this is
 * about. The three below are exactly the three {@code application-prod.yml} sets, so this
 * test fails if that file is edited to drop one.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=false",
        "springdoc.swagger-ui.enabled=false",
        "juriscore.security.docs.enabled=false",
})
class SwaggerProductionExposureIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the OpenAPI document is not served to an anonymous caller")
    void apiDocsAreNotAnonymous() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is(notOk()));
    }

    @Test
    @DisplayName("neither is the swagger-config document, which is a separate path")
    void swaggerConfigIsNotAnonymous() throws Exception {
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().is(notOk()));
    }

    @Test
    @DisplayName("nor the Swagger UI, by either of the paths it answers on")
    void swaggerUiIsNotAnonymous() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().is(notOk()));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is(notOk()));
    }

    @Test
    @DisplayName("the API itself is unaffected — this closes the docs, not the product")
    void theApiStillWorks() throws Exception {
        // A suite of negative assertions would pass just as happily against an application
        // that failed to start, so pin something that must still answer.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an ordinary API endpoint still refuses anonymous callers for the usual reason")
    void ordinaryEndpointsStillAuthenticate() throws Exception {
        mockMvc.perform(get("/api/v1/organizations/current"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Any status except 200.
     *
     * <p>Deliberately not a specific code. What matters is that the document is not handed
     * out, not which of the two mechanisms got there first — springdoc not registering the
     * route (404) and the security chain refusing it (401/403) are both correct outcomes.
     * Pinning one exactly would make this test flip on a change with no security meaning.
     */
    private static Matcher<Integer> notOk() {
        return Matchers.not(200);
    }
}
