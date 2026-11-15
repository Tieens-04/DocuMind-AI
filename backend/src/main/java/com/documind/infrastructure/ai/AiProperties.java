package com.documind.infrastructure.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "documind.ai")
public record AiProperties(GeminiProperties gemini) {

    public record GeminiProperties(
        String baseUrl,
        String completionsPath,
        String apiKey,
        String model,
        Double temperature
    ) {
    }
}
