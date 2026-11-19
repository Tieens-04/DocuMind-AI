package com.documind.service.rag;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.ai.AiModelConfig;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevFallbackCallerContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates synchronous grounded RAG question answering with deterministic zero-token refusal,
 * RBAC vector retrieval, optional Groq-to-Gemini failover, and cost/latency audit logging.
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private static final int MAX_IP_LENGTH = 45;
    private static final int MAX_REFUSAL_TRAILING_CHARS = 80;

    private final VectorRetrievalService vectorRetrievalService;
    private final GroundedSystemPromptService groundedSystemPromptService;
    private final ChatClient groqChatClient;
    private final ObjectProvider<ChatClient> geminiChatClientProvider;
    private final ObjectProvider<UserRepository> userRepositoryProvider;
    private final ObjectProvider<DepartmentRepository> departmentRepositoryProvider;
    private final ObjectProvider<AuditLogRepository> auditLogRepositoryProvider;
    private final ObjectProvider<TransactionTemplate> transactionTemplateProvider;
    private final AiProperties aiProperties;
    private final RagProperties ragProperties;
    private final Environment environment;

    public RagChatService(
        VectorRetrievalService vectorRetrievalService,
        GroundedSystemPromptService groundedSystemPromptService,
        ChatClient groqChatClient,
        @Qualifier("geminiChatClient") ObjectProvider<ChatClient> geminiChatClientProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        Environment environment
    ) {
        this.vectorRetrievalService = Objects.requireNonNull(vectorRetrievalService, "vectorRetrievalService must not be null");
        this.groundedSystemPromptService = Objects.requireNonNull(groundedSystemPromptService, "groundedSystemPromptService must not be null");
        this.groqChatClient = Objects.requireNonNull(groqChatClient, "groqChatClient must not be null");
        this.geminiChatClientProvider = Objects.requireNonNull(geminiChatClientProvider, "geminiChatClientProvider must not be null");
        this.userRepositoryProvider = Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null");
        this.departmentRepositoryProvider = Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null");
        this.auditLogRepositoryProvider = Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null");
        this.transactionTemplateProvider = Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null");
        this.aiProperties = aiProperties;
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    /**
     * Executes grounded RAG retrieval and synchronous generation outside any database transaction,
     * short-circuiting with zero LLM tokens whenever retrieval produces no eligible chunks.
     */
    public ChatAskResponse ask(String rawQuestion, CallerContext caller) {
        long startedAtNanos = System.nanoTime();
        requireCaller(caller);
        String normalizedQuestion = normalizeAndValidateQuestion(rawQuestion);
        UserEntity verifiedActor = resolveVerifiedActor(caller);

        List<RetrievedChunk> chunks;
        try {
            chunks = vectorRetrievalService.retrieve(normalizedQuestion, caller);
        } catch (TransientAiException | NonTransientAiException | RestClientException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            recordFailureAuditQuietly(verifiedActor, caller, normalizedQuestion, failureLatencyMs, RagConstants.PROVIDER_OLLAMA);
            throw new LlmProviderException(RagConstants.PROVIDER_OLLAMA, "Embedding provider failed during vector retrieval", ex);
        } catch (LlmProviderException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            String failedProvider = ex.provider() != null ? ex.provider() : RagConstants.PROVIDER_OLLAMA;
            recordFailureAuditQuietly(verifiedActor, caller, normalizedQuestion, failureLatencyMs, failedProvider);
            throw ex;
        }
        if (chunks.isEmpty()) {
            long latencyMs = elapsedMillis(startedAtNanos);
            saveAuditLog(
                verifiedActor,
                caller.departmentId(),
                normalizedQuestion,
                true,
                0,
                0,
                latencyMs,
                null,
                HttpStatus.OK.value(),
                caller.ipAddress()
            );
            return new ChatAskResponse(
                RagConstants.SAFE_REFUSAL_MESSAGE,
                true,
                List.of(),
                null,
                0,
                0,
                latencyMs
            );
        }

        Message systemMessage = groundedSystemPromptService.buildSystemMessage(chunks);
        Prompt prompt = new Prompt(List.of(systemMessage, new UserMessage(normalizedQuestion)));

        ProviderResult result;
        try {
            result = invokeChatWithFailover(prompt);
        } catch (LlmProviderException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            String failedProvider = ex.provider() != null ? ex.provider() : RagConstants.PROVIDER_GROQ;
            recordFailureAuditQuietly(verifiedActor, caller, normalizedQuestion, failureLatencyMs, failedProvider);
            throw ex;
        }

        long latencyMs = elapsedMillis(startedAtNanos);
        boolean llmRefused = isSafeRefusalResponse(result.answer());
        String finalAnswer = llmRefused ? RagConstants.SAFE_REFUSAL_MESSAGE : result.answer();
        List<CitationDto> finalCitations = llmRefused
            ? List.of()
            : chunks.stream().map(RetrievedChunk::citation).toList();

        saveAuditLog(
            verifiedActor,
            caller.departmentId(),
            normalizedQuestion,
            llmRefused,
            result.promptTokens(),
            result.completionTokens(),
            latencyMs,
            result.provider(),
            HttpStatus.OK.value(),
            caller.ipAddress()
        );

        return new ChatAskResponse(
            finalAnswer,
            llmRefused,
            finalCitations,
            result.provider(),
            result.promptTokens(),
            result.completionTokens(),
            latencyMs
        );
    }

    private ProviderResult invokeChatWithFailover(Prompt prompt) {
        try {
            return invokeSingleProvider(groqChatClient, RagConstants.PROVIDER_GROQ, prompt);
        } catch (RuntimeException primaryFailure) {
            if (!isGeminiFallbackAvailable()) {
                if (primaryFailure instanceof LlmProviderException llmEx && llmEx.provider() != null) {
                    throw llmEx;
                }
                throw new LlmProviderException(RagConstants.PROVIDER_GROQ, "Primary LLM provider failed", primaryFailure);
            }
            log.warn("Primary Groq provider failed, falling back to Gemini: {}", primaryFailure.getMessage());
            try {
                return invokeSingleProvider(geminiChatClientProvider.getIfAvailable(), RagConstants.PROVIDER_GEMINI, prompt);
            } catch (RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(primaryFailure);
                throw new LlmProviderException(
                    RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER,
                    "All configured LLM providers failed",
                    fallbackFailure
                );
            }
        }
    }

    private static ProviderResult invokeSingleProvider(ChatClient client, String providerName, Prompt prompt) {
        ChatResponse response = client.prompt(prompt).call().chatResponse();
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LlmProviderException(providerName, "Empty response received from provider: " + providerName);
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new LlmProviderException(providerName, "Blank answer received from provider: " + providerName);
        }

        int promptTokens = 0;
        int completionTokens = 0;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                promptTokens = Math.max(0, usage.getPromptTokens());
            }
            if (usage.getCompletionTokens() != null) {
                completionTokens = Math.max(0, usage.getCompletionTokens());
            }
        }
        return new ProviderResult(providerName, text.trim(), promptTokens, completionTokens);
    }

    private boolean isGeminiFallbackAvailable() {
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

    private String normalizeAndValidateQuestion(String rawQuestion) {
        String normalized = VectorRetrievalService.normalizeQuery(rawQuestion);
        int maxQuestionChars = ragProperties.llm().maxQuestionChars();
        if (normalized.length() > maxQuestionChars) {
            throw new InvalidChatRequestException(
                "Question exceeds maximum allowed length of " + maxQuestionChars + " characters"
            );
        }
        return normalized;
    }

    // TODO(DA-12): Consolidate active/role verification into JWT SecurityFilterChain and use UserRepository.getReferenceById for audit FK.
    private UserEntity resolveVerifiedActor(CallerContext caller) {
        UUID userId = caller.userId();
        if (userId == null) {
            return null;
        }
        UserEntity user = userRepositoryProvider.getObject().findById(userId)
            .orElseGet(() -> {
                if (isDevFallbackActive(environment)
                    && DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID.equals(userId)) {
                    return null;
                }
                throw new ResourceNotFoundException("User not found: " + userId);
            });
        if (user == null) {
            return null;
        }
        if (!user.isActive()) {
            throw new DocumentAccessDeniedException("User account is inactive");
        }
        if (!user.getRole().canAssume(caller.role())) {
            throw new DocumentAccessDeniedException("Caller role exceeds authenticated user privileges");
        }
        if (!caller.isSystemAdmin()
            && user.getRole() != Role.SYSTEM_ADMIN
            && caller.departmentId() != null
            && !Objects.equals(user.getDepartmentId(), caller.departmentId())) {
            throw new DocumentAccessDeniedException("Caller user does not belong to target department");
        }
        return user;
    }

    private void recordFailureAuditQuietly(
        UserEntity verifiedActor,
        CallerContext caller,
        String queryText,
        long latencyMs,
        String failedProvider
    ) {
        try {
            saveAuditLog(
                verifiedActor,
                caller.departmentId(),
                queryText,
                true,
                0,
                0,
                latencyMs,
                failedProvider,
                HttpStatus.BAD_GATEWAY.value(),
                caller.ipAddress()
            );
        } catch (RuntimeException auditEx) {
            log.warn("Failed to persist failure audit log for chat ask: {}", auditEx.getMessage());
        }
    }

    private void saveAuditLog(
        UserEntity user,
        UUID departmentId,
        String queryText,
        boolean abstained,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String provider,
        int statusCode,
        String ipAddress
    ) {
        transactionTemplateProvider.getObject().executeWithoutResult(status -> {
            DepartmentEntity department = departmentId != null
                ? departmentRepositoryProvider.getObject().getReferenceById(departmentId)
                : null;
            AuditLogEntity auditLog = new AuditLogEntity();
            auditLog.setUser(user);
            auditLog.setDepartment(department);
            auditLog.setActionType(AuditActionType.CHAT_QUERY);
            auditLog.setQueryText(queryText);
            auditLog.setAbstained(abstained);
            auditLog.setCacheTier(CacheTier.NONE);
            auditLog.setPromptTokens(Math.max(0, promptTokens));
            auditLog.setCompletionTokens(Math.max(0, completionTokens));
            auditLog.setLatencyMs(Math.max(0L, latencyMs));
            auditLog.setProvider(provider);
            auditLog.setStatusCode(statusCode);
            auditLog.setIpAddress(truncateIp(ipAddress));
            auditLogRepositoryProvider.getObject().save(auditLog);
        });
    }

    private static void requireCaller(CallerContext caller) {
        if (caller == null || caller.role() == null) {
            throw new InvalidChatRequestException("Caller context and role must not be null");
        }
        if (!caller.isSystemAdmin() && caller.departmentId() == null) {
            throw new InvalidChatRequestException("Caller departmentId must not be null");
        }
    }

    private static boolean isSafeRefusalResponse(String answer) {
        String stripped = stripRefusalWrappers(answer);
        int refusalLen = RagConstants.SAFE_REFUSAL_MESSAGE.length();
        if (!stripped.regionMatches(true, 0, RagConstants.SAFE_REFUSAL_MESSAGE, 0, refusalLen)) {
            return false;
        }
        String rawRemainder = stripped.substring(refusalLen);
        if (rawRemainder.isEmpty()) {
            return true;
        }
        if (Character.isLetterOrDigit(rawRemainder.charAt(0))) {
            return false;
        }
        return rawRemainder.trim().length() <= MAX_REFUSAL_TRAILING_CHARS;
    }

    private static String stripRefusalWrappers(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isRefusalWrapperChar(text.charAt(start))) {
            start++;
        }
        while (end > start && isRefusalWrapperChar(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isRefusalWrapperChar(char ch) {
        return Character.isWhitespace(ch)
            || ch == '"'
            || ch == '\''
            || ch == '“'
            || ch == '”'
            || ch == '`'
            || ch == '*';
    }

    private static boolean isDevFallbackActive(Environment environment) {
        return environment != null
            && !environment.acceptsProfiles(Profiles.of("prod"))
            && environment.acceptsProfiles(Profiles.of("dev", "local"))
            && Boolean.parseBoolean(environment.getProperty("documind.security.dev-caller-fallback.enabled", "false"));
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private static String truncateIp(String ipAddress) {
        if (ipAddress == null) {
            return null;
        }
        String trimmed = ipAddress.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_IP_LENGTH ? trimmed.substring(0, MAX_IP_LENGTH) : trimmed;
    }

    private record ProviderResult(
        String provider,
        String answer,
        int promptTokens,
        int completionTokens
    ) {
    }
}
