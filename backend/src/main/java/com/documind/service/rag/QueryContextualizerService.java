package com.documind.service.rag;

import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.enums.MessageRole;
import com.documind.infrastructure.ai.AiModelConfig;
import com.documind.infrastructure.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rewrites terse multi-turn follow-up questions into standalone semantic queries using up to the
 * 6 most recent conversation turns before {@code bge-m3} vector embedding. Skips LLM invocation
 * completely on Turn 1 ({@code history.isEmpty()}) to optimize TTFT, and fails open to the
 * original normalized question if rewrite providers fail or return invalid output.
 */
@Service
public class QueryContextualizerService {

    private static final Logger log = LoggerFactory.getLogger(QueryContextualizerService.class);
    private static final String DEFAULT_TEMPLATE_PATH = "prompts/rag-query-contextualizer.st";
    private static final String ABSTAINED_HISTORY_PLACEHOLDER =
        "[Hệ thống không tìm thấy thông tin phù hợp trong tài liệu nội bộ ở lượt này]";
    private static final Pattern DELIMITER_TAG_PATTERN =
        Pattern.compile("(?i)<(\\s*/?\\s*(?:conversation_history|turn)\\b[^>]*)>");
    private static final Pattern LEADING_LABEL_PATTERN = Pattern.compile(
        "^(?iu)(?:câu\\s+hỏi\\s+(?:độc\\s+lập|được\\s+viết\\s+lại|viết\\s+lại)|standalone\\s+question|rewritten\\s+(?:query|question)|query)\\s*:\\s*"
    );
    private static final ChatOptions REWRITE_OPTIONS = ChatOptions.builder()
        .temperature(0.0d)
        .build();

    private final ChatClient groqChatClient;
    private final ObjectProvider<ChatClient> geminiChatClientProvider;
    private final AiProperties aiProperties;
    private final RagProperties ragProperties;
    private final SystemPromptTemplate systemPromptTemplate;

    @Autowired
    public QueryContextualizerService(
        ChatClient groqChatClient,
        @Qualifier("geminiChatClient") ObjectProvider<ChatClient> geminiChatClientProvider,
        AiProperties aiProperties,
        RagProperties ragProperties
    ) {
        this(
            groqChatClient,
            geminiChatClientProvider,
            aiProperties,
            ragProperties,
            new ClassPathResource(DEFAULT_TEMPLATE_PATH)
        );
    }

    QueryContextualizerService(
        ChatClient groqChatClient,
        ObjectProvider<ChatClient> geminiChatClientProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        Resource templateResource
    ) {
        this.groqChatClient = Objects.requireNonNull(groqChatClient, "groqChatClient must not be null");
        this.geminiChatClientProvider = Objects.requireNonNull(
            geminiChatClientProvider,
            "geminiChatClientProvider must not be null"
        );
        this.aiProperties = aiProperties;
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
        Objects.requireNonNull(templateResource, "templateResource must not be null");
        this.systemPromptTemplate = new SystemPromptTemplate(readUtf8Template(templateResource));
    }

    /**
     * Produces a standalone query for {@code bge-m3} retrieval. Bypasses LLM completely when
     * {@code chronologicalHistory} is empty (Turn 1 TTFT optimization).
     */
    public ContextualizedQuery contextualize(
        String normalizedQuestion,
        List<ChatMessageEntity> chronologicalHistory
    ) {
        Objects.requireNonNull(normalizedQuestion, "normalizedQuestion must not be null");
        if (chronologicalHistory == null || chronologicalHistory.isEmpty()) {
            return ContextualizedQuery.skipped(normalizedQuestion);
        }

        int windowSize = ragProperties.memory().historyWindowSize();
        int startIndex = Math.max(0, chronologicalHistory.size() - windowSize);
        List<ChatMessageEntity> boundedWindow = chronologicalHistory.subList(startIndex, chronologicalHistory.size());

        String serializedHistory = serializeHistory(boundedWindow, ragProperties.memory().maxHistoryMessageChars());
        Message systemMessage = systemPromptTemplate.createMessage(Map.of("history", serializedHistory));
        Prompt prompt = new Prompt(List.of(systemMessage, new UserMessage(normalizedQuestion)), REWRITE_OPTIONS);

        RewriteProviderResult providerResult;
        try {
            providerResult = invokeRewriteWithFailover(prompt);
        } catch (RuntimeException ex) {
            log.warn("Query contextualization failed across providers; falling back to original question: {}", ex.getMessage());
            return ContextualizedQuery.skipped(normalizedQuestion);
        }

        String sanitized = sanitizeRewrittenOutput(providerResult.rawText());
        int maxQuestionChars = ragProperties.llm().maxQuestionChars();
        if (sanitized.isEmpty()
            || sanitized.length() > maxQuestionChars
            || RagChatService.isSafeRefusalResponse(sanitized)) {
            log.warn(
                "Query contextualizer produced unusable output (len={}, provider={}); falling back to original question",
                sanitized.length(),
                providerResult.provider()
            );
            return new ContextualizedQuery(
                normalizedQuestion,
                null,
                providerResult.promptTokens(),
                providerResult.completionTokens(),
                providerResult.provider()
            );
        }

        return new ContextualizedQuery(
            sanitized,
            sanitized.equals(normalizedQuestion) ? null : sanitized,
            providerResult.promptTokens(),
            providerResult.completionTokens(),
            providerResult.provider()
        );
    }

    private RewriteProviderResult invokeRewriteWithFailover(Prompt prompt) {
        try {
            return invokeSingleProvider(groqChatClient, RagConstants.PROVIDER_GROQ, prompt);
        } catch (RuntimeException primaryFailure) {
            if (!isGeminiConfigured(ragProperties, geminiChatClientProvider, aiProperties)) {
                throw primaryFailure;
            }
            log.warn("Primary Groq contextualizer failed, falling back to Gemini: {}", primaryFailure.getMessage());
            try {
                return invokeSingleProvider(geminiChatClientProvider.getIfAvailable(), RagConstants.PROVIDER_GEMINI, prompt);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw fallbackFailure;
            }
        }
    }

    private static RewriteProviderResult invokeSingleProvider(ChatClient client, String providerName, Prompt prompt) {
        ChatResponse response = client.prompt(prompt).call().chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("Empty contextualizer response received from provider: " + providerName);
        }
        String rawText = response.getResult().getOutput().getText();
        TokenCounts tokens = extractTokenCounts(response);
        return new RewriteProviderResult(providerName, rawText, tokens.promptTokens(), tokens.completionTokens());
    }

    static TokenCounts extractTokenCounts(ChatResponse response) {
        int promptTokens = 0;
        int completionTokens = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                promptTokens = Math.max(0, usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                completionTokens = Math.max(0, usage.getCompletionTokens());
            }
        }
        return new TokenCounts(promptTokens, completionTokens);
    }

    static boolean isGeminiConfigured(
        RagProperties ragProperties,
        ObjectProvider<ChatClient> geminiChatClientProvider,
        AiProperties aiProperties
    ) {
        if (!ragProperties.llm().fallbackEnabled()) {
            return false;
        }
        ChatClient geminiClient = geminiChatClientProvider.getIfAvailable();
        if (geminiClient == null || aiProperties == null || aiProperties.gemini() == null) {
            return false;
        }
        String apiKey = aiProperties.gemini().apiKey();
        return apiKey != null && !apiKey.isBlank() && !AiModelConfig.UNCONFIGURED_API_KEY.equals(apiKey.trim());
    }

    private static String serializeHistory(List<ChatMessageEntity> messages, int maxMessageChars) {
        StringBuilder sb = new StringBuilder(messages.size() * 256);
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageEntity msg = Objects.requireNonNull(messages.get(i), "history message must not be null");
            MessageRole role = msg.getRole() != null ? msg.getRole() : MessageRole.USER;
            boolean abstained = msg.isAbstained();
            String rawContent = (role == MessageRole.ASSISTANT && abstained)
                ? ABSTAINED_HISTORY_PLACEHOLDER
                : (msg.getContent() != null ? msg.getContent() : "");
            String bounded = truncateSafe(rawContent, maxMessageChars);
            String sanitized = sanitizeDelimiterTags(bounded);

            if (i > 0) {
                sb.append('\n');
            }
            sb.append("<turn index=\"").append(i + 1)
                .append("\" role=\"").append(role.name())
                .append("\" abstained=\"").append(abstained)
                .append("\">\n")
                .append(sanitized)
                .append("\n</turn>");
        }
        return sb.toString();
    }

    private static String sanitizeRewrittenOutput(String rawText) {
        if (rawText == null) {
            return "";
        }
        String cleaned = rawText
            .replace("\0", "")
            .replace("\uFEFF", "")
            .replace("\u00AD", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }
        cleaned = stripSurroundingWrappers(cleaned.trim());
        cleaned = LEADING_LABEL_PATTERN.matcher(cleaned).replaceFirst("");
        cleaned = stripSurroundingWrappers(cleaned.trim());
        cleaned = cleaned.replace('\n', ' ').replaceAll(" {2,}", " ").trim();
        return cleaned;
    }

    private static String stripSurroundingWrappers(String text) {
        String current = text;
        if (current.startsWith("```") && current.endsWith("```") && current.length() >= 6) {
            int firstNewline = current.indexOf('\n');
            if (firstNewline > 0 && firstNewline < current.length() - 3) {
                current = current.substring(firstNewline + 1, current.length() - 3).trim();
            }
        }
        boolean stripped;
        do {
            stripped = false;
            if (isWrappedBy(current, '"', '"')
                || isWrappedBy(current, '\'', '\'')
                || isWrappedBy(current, '“', '”')
                || isWrappedBy(current, '`', '`')) {
                current = current.substring(1, current.length() - 1).trim();
                stripped = true;
            }
        } while (stripped);
        return current;
    }

    private static boolean isWrappedBy(String text, char open, char close) {
        return text.length() >= 2
            && text.charAt(0) == open
            && text.charAt(text.length() - 1) == close
            && text.indexOf(close, 1) == text.length() - 1;
    }

    private static String sanitizeDelimiterTags(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return "";
        }
        return DELIMITER_TAG_PATTERN.matcher(rawContent).replaceAll(match ->
            "&lt;" + match.group(1) + "&gt;"
        );
    }

    static String truncateSafe(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text != null ? text : "";
        }
        int cut = maxChars;
        if (cut > 0 && cut < text.length() && Character.isLowSurrogate(text.charAt(cut))) {
            cut--;
        }
        while (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))
            && (cut >= text.length() || !Character.isLowSurrogate(text.charAt(cut)))) {
            cut--;
        }
        return text.substring(0, Math.max(1, cut)).trim() + "...";
    }

    private static String readUtf8Template(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to load query contextualizer prompt template: " + resource.getDescription(), ex);
        }
    }

    record TokenCounts(int promptTokens, int completionTokens) {
    }

    private record RewriteProviderResult(
        String provider,
        String rawText,
        int promptTokens,
        int completionTokens
    ) {
    }

    /**
     * Outcome of query contextualization carrying the effective vector-search query, optional persisted
     * {@code rewrittenQuery}, and exact FinOps token telemetry.
     */
    public record ContextualizedQuery(
        String effectiveQuery,
        String rewrittenQuery,
        int promptTokens,
        int completionTokens,
        String provider
    ) {

        public ContextualizedQuery {
            Objects.requireNonNull(effectiveQuery, "effectiveQuery must not be null");
            promptTokens = Math.max(0, promptTokens);
            completionTokens = Math.max(0, completionTokens);
        }

        public static ContextualizedQuery skipped(String originalQuestion) {
            return new ContextualizedQuery(originalQuestion, null, 0, 0, null);
        }

        public boolean wasRewritten() {
            return rewrittenQuery != null && !rewrittenQuery.isBlank();
        }
    }
}
