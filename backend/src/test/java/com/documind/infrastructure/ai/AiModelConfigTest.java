package com.documind.infrastructure.ai;

import com.documind.controller.dto.chat.ChatTokenUsageDto;
import com.documind.service.rag.RagProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .model("openai/gpt-oss-120b")
                .temperature(0.1)
                .build())
            .build();
        var props = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "chat/completions",
            "",
            "gemini-3.5-flash",
            0.1
        ));

        var config = new AiModelConfig();
        ChatClient groq = config.groqChatClient(baseModel);
        ChatClient gemini = config.geminiChatClient(baseApi, baseModel, props);

        assertThat(groq).isNotNull();
        assertThat(gemini).isNotNull().isNotSameAs(groq);
    }

    @Test
    void sanitizerEnablesStreamUsageWhenUnsetOnOpenAiChatProperties() {
        BeanPostProcessor sanitizer = AiModelConfig.openAiConnectionPropertiesSanitizer();

        OpenAiChatProperties chatProperties = new OpenAiChatProperties();
        chatProperties.getOptions().setModel("openai/gpt-oss-120b");
        assertThat(chatProperties.getOptions().getStreamOptions()).isNull();

        sanitizer.postProcessAfterInitialization(chatProperties, "openAiChatProperties");

        assertThat(chatProperties.getOptions().getStreamUsage()).isTrue();
        assertThat(chatProperties.getOptions().getStreamOptions()).isNotNull();
    }

    @Test
    void streamingWebClientCustomizerAppliesBoundedConnectTimeoutAndClosesCleanly() {
        AiModelConfig config = new AiModelConfig();
        try (HttpClient configuredClient = config.buildStreamingHttpClient(Duration.ofSeconds(7));
             HttpClient fallbackClient = config.buildStreamingHttpClient(Duration.ZERO)) {
            assertThat(configuredClient.connectTimeout()).contains(Duration.ofSeconds(7));
            assertThat(fallbackClient.connectTimeout()).contains(Duration.ofSeconds(10));
        }

        WebClientCustomizer customizer = config.streamingWebClientConnectTimeoutCustomizer(Duration.ofSeconds(10));
        WebClient.Builder builder = WebClient.builder();
        customizer.customize(builder);
        assertThat(builder.build()).isNotNull();
        config.closeStreamingHttpClient();
    }

    @Test
    void buildGroqChatModelEnablesStreamUsageWhilePreservingModelAndTemperature() {
        AiModelConfig config = new AiModelConfig();
        OpenAiApi baseApi = OpenAiApi.builder()
            .baseUrl("https://api.groq.com/openai")
            .apiKey("test-groq-key")
            .build();
        OpenAiChatModel baseModel = OpenAiChatModel.builder()
            .openAiApi(baseApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model("openai/gpt-oss-120b")
                .temperature(0.1d)
                .build())
            .build();

        OpenAiChatModel groqModel = config.buildGroqChatModel(baseModel);

        assertThat(groqModel.getDefaultOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) groqModel.getDefaultOptions();
        assertThat(options.getStreamUsage()).isTrue();
        assertThat(options.getModel()).isEqualTo("openai/gpt-oss-120b");
        assertThat(options.getTemperature()).isEqualTo(0.1d);
    }

    @Test
    void buildGeminiChatModelEnablesStreamUsageAndAppliesConfiguredOptions() {
        AiModelConfig config = new AiModelConfig();
        OpenAiApi baseApi = OpenAiApi.builder()
            .baseUrl("https://api.groq.com/openai")
            .apiKey("test-groq-key")
            .build();
        OpenAiChatModel baseModel = OpenAiChatModel.builder()
            .openAiApi(baseApi)
            .build();
        AiProperties aiProperties = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai/",
            "chat/completions",
            "test-gemini-key",
            "gemini-2.5-flash",
            0.2d
        ));

        OpenAiChatModel geminiModel = config.buildGeminiChatModel(baseApi, baseModel, aiProperties);

        assertThat(geminiModel.getDefaultOptions()).isInstanceOf(OpenAiChatOptions.class);
        OpenAiChatOptions options = (OpenAiChatOptions) geminiModel.getDefaultOptions();
        assertThat(options.getStreamUsage()).isTrue();
        assertThat(options.getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(options.getTemperature()).isEqualTo(0.2d);
    }

    @Test
    void ragPropertiesDefaultsIncludeSseTimeoutAndRejectOutOfRangeValues() {
        RagProperties defaults = RagProperties.defaults();
        assertThat(defaults.sse()).isNotNull();
        assertThat(defaults.sse().timeout()).isEqualTo(Duration.ofSeconds(120));

        assertThatThrownBy(() -> new RagProperties.SseProperties(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagProperties.SseProperties(Duration.ofMinutes(15)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chatTokenUsageDtoAlwaysEnforcesTotalTokensInvariant() {
        ChatTokenUsageDto usage = new ChatTokenUsageDto(100, 25, 0);
        assertThat(usage.promptTokens()).isEqualTo(100);
        assertThat(usage.completionTokens()).isEqualTo(25);
        assertThat(usage.totalTokens()).isEqualTo(125);

        ChatTokenUsageDto clamped = new ChatTokenUsageDto(-5, 10, -99);
        assertThat(clamped.promptTokens()).isZero();
        assertThat(clamped.completionTokens()).isEqualTo(10);
        assertThat(clamped.totalTokens()).isEqualTo(10);
    }
}
