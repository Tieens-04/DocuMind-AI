package com.documind.infrastructure.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AiModelConfig {

    public static final String UNCONFIGURED_API_KEY = "unconfigured-api-key";
    private static final String DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String DEFAULT_GEMINI_COMPLETIONS_PATH = "/chat/completions";
    private static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash";
    private static final double DEFAULT_TEMPERATURE = 0.1;

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
                }
                return bean;
            }
        };
    }

    @Bean
    @Primary
    public ChatClient groqChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.create(openAiChatModel);
    }

    @Bean("geminiChatClient")
    public ChatClient geminiChatClient(
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

        OpenAiChatModel geminiModel = baseChatModel.mutate()
            .openAiApi(geminiApi)
            .defaultOptions(OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build())
            .build();

        return ChatClient.create(geminiModel);
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
