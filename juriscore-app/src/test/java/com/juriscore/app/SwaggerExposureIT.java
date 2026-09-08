package com.juriscore.app;

import com.juriscore.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API description in a developer's checkout, where it is meant to be readable.
 *
 * <p>Half of a pair: {@link SwaggerProductionExposureIT} asserts the opposite under the
 * production configuration. Both exist because "we turned Swagger off" is the kind of claim
 * that is easy to make and easy to get wrong in one environment while testing the other.
 */
class SwaggerExposureIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("the OpenAPI document is readable without signing in, which is the point locally")
    void docsAreOpenLocally() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("so is the Swagger UI")
    void swaggerUiIsAvailableLocally() throws Exception {
        // springdoc redirects /swagger-ui.html to the bundled index; a redirect is a
        // perfectly good "yes, it is here", and pinning the exact target would break the
        // day springdoc changes its layout.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
