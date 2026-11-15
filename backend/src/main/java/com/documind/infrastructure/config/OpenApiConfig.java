package com.documind.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI docuMindOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("DocuMind AI API")
                .version("v1.0.0")
                .description("Enterprise Intelligent Document & Knowledge Base Assistant")
                .contact(new Contact().name("DocuMind AI Engineering")));
    }
}
