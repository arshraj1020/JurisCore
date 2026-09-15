package com.juriscore.identity.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the browser is allowed to read back.
 *
 * <p>The frontend and the API are served from different origins — Cloudflare Pages in
 * front of Render — so every response header the SPA reads has to be named in
 * {@code Access-Control-Expose-Headers}. A header that is sent but not exposed is not a
 * degraded experience; it is invisible to {@code fetch}, indistinguishable from a header
 * the server never sent, and the failure is silent on both sides.
 *
 * <p>Asserted against the configuration object rather than through a live request on
 * purpose: this is a contract about what the bean declares, and pinning it here means a
 * header cannot quietly drop off the list.
 */
class SecurityConfigCorsTest {

    private CorsConfiguration corsConfiguration() {
        SecurityConfig config = new SecurityConfig(null, null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "https://app.juriscore.test");

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        return source.getCorsConfigurations().values().iterator().next();
    }

    @Test
    @DisplayName("the invoice PDF's filename header is readable cross-origin")
    void exposesContentDisposition() {
        // Without this the download in lib/api/client.ts cannot read the filename the
        // server chose and falls back to one it builds itself.
        assertThat(corsConfiguration().getExposedHeaders())
                .contains("Content-Disposition", "X-Request-Id");
    }

    @Test
    @DisplayName("exposing a header does not also hand over credentials")
    void doesNotAllowCredentials() {
        // Exposed headers are only safe to widen while the browser is not attaching
        // cookies to these requests. The API authenticates with a bearer token it is
        // handed explicitly, so this stays false.
        assertThat(corsConfiguration().getAllowCredentials()).isNotEqualTo(Boolean.TRUE);
    }
}
