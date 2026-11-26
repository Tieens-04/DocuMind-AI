package com.documind.infrastructure.security;

import com.documind.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorsSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3002";
    private static final String DISALLOWED_ORIGIN = "https://evil.example.com";

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(springSecurity())
            .build();
    }

    @Test
    void preflightAllowsFrontendOriginAndDoesNotAllowCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflightRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/chat/sessions")
                .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void errorResponsesStillCarryCorsAllowOriginAndExposedHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"manager.hr@documind.ai\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Retry-After")))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("WWW-Authenticate")))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Content-Disposition")))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Content-Range")));
    }

    @Test
    void corsPropertiesFallBackToSafeDefaultsWhenBlankOrNonPositive() {
        CorsProperties defaulted = new CorsProperties(
            null,
            List.of(" ", ""),
            Arrays.asList(null, "  "),
            List.of(),
            Duration.ZERO
        );
        assertThat(defaulted.allowedOrigins()).isEqualTo(CorsProperties.DEFAULT_ALLOWED_ORIGINS);
        assertThat(defaulted.allowedMethods()).isEqualTo(CorsProperties.DEFAULT_ALLOWED_METHODS);
        assertThat(defaulted.allowedHeaders()).isEqualTo(CorsProperties.DEFAULT_ALLOWED_HEADERS);
        assertThat(defaulted.exposedHeaders()).isEqualTo(CorsProperties.DEFAULT_EXPOSED_HEADERS);
        assertThat(defaulted.maxAge()).isEqualTo(CorsProperties.DEFAULT_MAX_AGE);

        CorsProperties trimmed = new CorsProperties(
            List.of(" https://app.documind.ai ", " "),
            List.of(" GET "),
            List.of(" Authorization "),
            List.of(" Retry-After "),
            Duration.ofMinutes(15)
        );
        assertThat(trimmed.allowedOrigins()).containsExactly("https://app.documind.ai");
        assertThat(trimmed.allowedMethods()).containsExactly("GET");
        assertThat(trimmed.allowedHeaders()).containsExactly("Authorization");
        assertThat(trimmed.exposedHeaders()).containsExactly("Retry-After");
        assertThat(trimmed.maxAge()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void corsPropertiesStripsTrailingSlashesAndRejectsWildcardOrigins() {
        CorsProperties normalized = new CorsProperties(
            List.of(" https://app.documind.ai/// "),
            null,
            null,
            null,
            null
        );
        assertThat(normalized.allowedOrigins()).containsExactly("https://app.documind.ai");

        assertThatThrownBy(() -> new CorsProperties(List.of("*"), null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorsProperties(
            List.of("http://localhost:3002", "https://*.documind.ai"),
            null,
            null,
            null,
            null
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
