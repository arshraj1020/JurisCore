package com.juriscore.app.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Value("${juriscore.api.public-url:http://localhost:8080}")
    private String publicUrl;

    @Bean
    public OpenAPI jurisCoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("JurisCore API")
                        .version("v1")
                        .description("""
                                Enterprise legal case management and court workflow platform.

                                Every response uses the same envelope: `{ "success": true, "data": ... }`
                                on success and `{ "success": false, "error": { "code", "message" } }`
                                on failure. Authenticate by sending the access token from
                                `POST /api/v1/auth/login` as `Authorization: Bearer <token>`.
                                """)
                        .contact(new Contact().name("JurisCore Engineering"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url(publicUrl).description("Current environment")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token issued by /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
