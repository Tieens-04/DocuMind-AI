package com.documind.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI docuMindOpenApi() {
        return new OpenAPI()
            .components(new Components()
                .addSecuritySchemes(
                    BEARER_AUTH_SCHEME,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                ))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME))
            .info(new Info()
                .title("DocuMind AI API")
                .version("v1.0.0")
                .description("Enterprise Intelligent Document & Knowledge Base Assistant")
                .contact(new Contact().name("DocuMind AI Engineering")));
    }
}
