package com.juriscore.identity.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * The application's security policy.
 *
 * <p>Stateless by design: no session, no CSRF token, authorization decided entirely
 * from the bearer token. Endpoint-level rules stay coarse here — "authenticated or
 * not" — while role checks live next to the handlers as {@code @PreAuthorize}, where
 * they are visible to whoever is reading the controller.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, AuthProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password"
    };

    private static final String[] DOCS_ENDPOINTS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    /**
     * Whether the OpenAPI document and Swagger UI are reachable at all.
     *
     * <p>Defaults to true so a developer checkout keeps working, and is set to false in
     * {@code application-prod.yml} alongside {@code springdoc.*.enabled}. Both halves are
     * needed and they answer different questions: springdoc's own flags stop the endpoints
     * from being registered, and this flag stops the security chain from declaring them
     * public. Leaving the {@code permitAll} rule in place while springdoc is off would be
     * harmless today and quietly wrong the moment anything else is mapped under
     * {@code /v3/api-docs} or {@code /swagger-ui}.
     */
    @Value("${juriscore.security.docs.enabled:true}")
    private boolean docsEnabled;

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Value("${juriscore.security.cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_ENDPOINTS).permitAll();
                    if (docsEnabled) {
                        auth.requestMatchers(DOCS_ENDPOINTS).permitAll();
                    } else {
                        // Not merely unmapped: anything still answering under these paths
                        // in a deployed environment requires a platform administrator.
                        auth.requestMatchers(DOCS_ENDPOINTS).hasRole("SUPER_ADMIN");
                    }
                    auth
                            .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**",
                                    "/actuator/info").permitAll()
                            .requestMatchers("/actuator/**").hasRole("SUPER_ADMIN")
                            .anyRequest().authenticated();
                })
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Keeps Boot from registering the JWT filter a second time.
     *
     * <p>Any {@code Filter} bean is auto-registered with the servlet container, which
     * would put this one in the chain twice: once here, inside the security chain, and
     * once at servlet level outside it. {@code OncePerRequestFilter} would mask the
     * duplicate at runtime, but the outer registration also runs on paths the security
     * chain never sees. Disabling the automatic registration keeps the filter exactly
     * where {@code addFilterBefore} puts it.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * BCrypt at strength 12. Deliberately slower than the default 10: an offline attack
     * on a leaked table of legal-sector credentials is worth the ~100ms per sign-in.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        /*
         * Credentials are NOT allowed, because this API does not use any.
         *
         * Authentication is a bearer token that the client reads from memory and puts in
         * the Authorization header; there is no cookie, no HTTP Basic prompt and no TLS
         * client certificate for the browser to attach. `allowCredentials(true)` therefore
         * bought nothing, while instructing browsers to send whatever ambient credentials
         * a future change might introduce — a cookie added later would start riding along
         * on cross-origin calls without anyone deciding that it should.
         *
         * It also removes a foot-gun: with credentials allowed, a wildcard origin is
         * illegal, and the temptation under a deployment problem is to widen the origin
         * list rather than fix it. The Authorization header is unaffected — it is listed
         * in the allowed headers above and is sent explicitly by the client, not by the
         * browser's credential machinery.
         */
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
