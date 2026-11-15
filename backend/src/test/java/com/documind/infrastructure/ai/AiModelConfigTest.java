package com.documind.infrastructure.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.config.BeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class AiModelConfigTest {

    @Test
    void normalizesBlankApiKeysAndGeminiPathsSafely() {
        assertThat(AiModelConfig.normalizeApiKey(null)).isEqualTo("unconfigured-api-key");
        assertThat(AiModelConfig.normalizeApiKey("")).isEqualTo("unconfigured-api-key");
        assertThat(AiModelConfig.normalizeApiKey("   ")).isEqualTo("unconfigured-api-key");
        assertThat(AiModelConfig.normalizeApiKey("AIza-real")).isEqualTo("AIza-real");

        assertThat(AiModelConfig.normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
        assertThat(AiModelConfig.normalizeCompletionsPath("chat/completions"))
            .isEqualTo("/chat/completions");
        assertThat(AiModelConfig.normalizeCompletionsPath("/chat/completions"))
            .isEqualTo("/chat/completions");
    }

    @Test
    void sanitizesBlankGroqConnectionPropertiesAfterBinding() {
        BeanPostProcessor sanitizer = AiModelConfig.openAiConnectionPropertiesSanitizer();
        var props = new OpenAiConnectionProperties();
        props.setApiKey("   ");
        props.setBaseUrl("https://api.groq.com/openai/");

        sanitizer.postProcessAfterInitialization(props, "openAiConnectionProperties");

        assertThat(props.getApiKey()).isEqualTo("unconfigured-api-key");
        assertThat(props.getBaseUrl()).isEqualTo("https://api.groq.com/openai");
    }

    @Test
    void createsDistinctPrimaryGroqAndSecondaryGeminiChatClientsEvenWhenGeminiKeyBlank() {
        OpenAiApi baseApi = OpenAiApi.builder()
            .baseUrl("https://api.groq.com/openai")
            .apiKey("gsk_test")
            .build();
        OpenAiChatModel baseModel = OpenAiChatModel.builder()
            .openAiApi(baseApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model("llama-3.3-70b-versatile")
                .temperature(0.1)
                .build())
            .build();
        var props = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "chat/completions",
            "",
            "gemini-2.5-flash",
            0.1
        ));

        var config = new AiModelConfig();
        ChatClient groq = config.groqChatClient(baseModel);
        ChatClient gemini = config.geminiChatClient(baseApi, baseModel, props);

        assertThat(groq).isNotNull();
        assertThat(gemini).isNotNull().isNotSameAs(groq);
    }
}
