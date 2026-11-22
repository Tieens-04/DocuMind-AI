package com.documind.infrastructure.ai;

import jakarta.annotation.PreDestroy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AiModelConfig {

    public static final String UNCONFIGURED_API_KEY = "unconfigured-api-key";
    private static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEFAULT_GEMINI_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    private static final double DEFAULT_TEMPERATURE = 0.1;
    private static final Duration DEFAULT_WEBCLIENT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private volatile HttpClient streamingHttpClient;

    @Bean
    public WebClientCustomizer streamingWebClientConnectTimeoutCustomizer(
        @Value("${spring.http.client.connect-timeout:10s}") Duration connectTimeout
    ) {
        HttpClient httpClient = buildStreamingHttpClient(connectTimeout);
        this.streamingHttpClient = httpClient;
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        return builder -> builder.clientConnector(connector);
    }

    HttpClient buildStreamingHttpClient(Duration connectTimeout) {
        Duration effectiveTimeout = (connectTimeout != null && !connectTimeout.isNegative() && !connectTimeout.isZero())
            ? connectTimeout
            : DEFAULT_WEBCLIENT_CONNECT_TIMEOUT;
        return HttpClient.newBuilder()
            .connectTimeout(effectiveTimeout)
            .build();
    }

    @PreDestroy
    void closeStreamingHttpClient() {
        HttpClient client = this.streamingHttpClient;
        if (client != null) {
            client.close();
        }
    }

    @Bean
    public static BeanPostProcessor openAiConnectionPropertiesSanitizer() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof OpenAiConnectionProperties props) {
                    props.setApiKey(normalizeApiKey(props.getApiKey()));
                    if (props.getBaseUrl() != null && !props.getBaseUrl().isBlank()) {
                        props.setBaseUrl(normalizeBaseUrl(props.getBaseUrl()));
                    }
                } else if (bean instanceof OpenAiChatProperties chatProps
                    && chatProps.getOptions() != null
                    && chatProps.getOptions().getStreamOptions() == null) {
                    chatProps.getOptions().setStreamUsage(Boolean.TRUE);
                }
                return bean;
            }
        };
    }

    @Bean
    @Primary
    public ChatClient groqChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.create(buildGroqChatModel(openAiChatModel));
    }

    @Bean("geminiChatClient")
    public ChatClient geminiChatClient(
        OpenAiApi baseOpenAiApi,
        OpenAiChatModel baseChatModel,
        AiProperties aiProperties
    ) {
        return ChatClient.create(buildGeminiChatModel(baseOpenAiApi, baseChatModel, aiProperties));
    }

    OpenAiChatModel buildGroqChatModel(OpenAiChatModel openAiChatModel) {
        if (openAiChatModel.getDefaultOptions() instanceof OpenAiChatOptions defaultOptions
            && !Boolean.TRUE.equals(defaultOptions.getStreamUsage())) {
            OpenAiChatOptions withStreamUsage = OpenAiChatOptions.fromOptions(defaultOptions);
            withStreamUsage.setStreamUsage(Boolean.TRUE);
            return openAiChatModel.mutate()
                .defaultOptions(withStreamUsage)
                .build();
        }
        return openAiChatModel;
    }

    OpenAiChatModel buildGeminiChatModel(
        OpenAiApi baseOpenAiApi,
        OpenAiChatModel baseChatModel,
        AiProperties aiProperties
    ) {
        AiProperties.GeminiProperties gemini = (aiProperties != null && aiProperties.gemini() != null)
            ? aiProperties.gemini()
            : new AiProperties.GeminiProperties(
                DEFAULT_GEMINI_BASE_URL,
                DEFAULT_GEMINI_COMPLETIONS_PATH,
                UNCONFIGURED_API_KEY,
                DEFAULT_GEMINI_MODEL,
                DEFAULT_TEMPERATURE
            );

        String modelName = (gemini.model() == null || gemini.model().isBlank())
            ? DEFAULT_GEMINI_MODEL
            : gemini.model().trim();
        double temperature = gemini.temperature() != null ? gemini.temperature() : DEFAULT_TEMPERATURE;

        OpenAiApi geminiApi = baseOpenAiApi.mutate()
            .baseUrl(normalizeBaseUrl(gemini.baseUrl()))
            .completionsPath(normalizeCompletionsPath(gemini.completionsPath()))
            .apiKey(normalizeApiKey(gemini.apiKey()))
            .build();

        return baseChatModel.mutate()
            .openAiApi(geminiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .streamUsage(true)
                .build())
            .build();
    }

    public static String normalizeApiKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return UNCONFIGURED_API_KEY;
        }
        return rawKey.trim();
    }

    public static String normalizeBaseUrl(String rawUrl) {
        String candidate = (rawUrl == null || rawUrl.isBlank())
            ? DEFAULT_GEMINI_BASE_URL
            : rawUrl.trim();
        while (candidate.endsWith("/") && candidate.length() > 1) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate;
    }

    public static String normalizeCompletionsPath(String rawPath) {
        String candidate = (rawPath == null || rawPath.isBlank())
            ? DEFAULT_GEMINI_COMPLETIONS_PATH
            : rawPath.trim();
        return candidate.startsWith("/") ? candidate : "/" + candidate;
    }
}
