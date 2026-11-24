package com.documind.service.rag;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.MessageRole;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.document.DevCallerFallbackEnabledCondition;
import com.documind.service.document.DevFallbackCallerContextResolver;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates synchronous grounded single-turn and multi-turn RAG question answering with
 * session memory, follow-up query contextualization (bypassed on Turn 1 for TTFT),
 * deterministic zero-answer-token refusal, {@code SecurityContextHolder}-enforced RBAC vector retrieval,
 * pre-prompt Defense-in-Depth checks, optional Groq-to-Gemini failover, and FinOps cost/latency audit logging.
 */
@Service
public class RagChatService {

    private static final Logger log = LoggerFactory.getLogger(RagChatService.class);
    private static final int MAX_REFUSAL_TRAILING_CHARS = 80;

    private final VectorRetrievalService vectorRetrievalService;
    private final GroundedSystemPromptService groundedSystemPromptService;
    private final RbacVectorFilterFactory rbacVectorFilterFactory;
    private final ChatClient groqChatClient;
    private final ObjectProvider<ChatClient> geminiChatClientProvider;
    private final ObjectProvider<UserRepository> userRepositoryProvider;
    private final ObjectProvider<DepartmentRepository> departmentRepositoryProvider;
    private final ObjectProvider<AuditLogRepository> auditLogRepositoryProvider;
    private final ObjectProvider<TransactionTemplate> transactionTemplateProvider;
    private final ObjectProvider<ChatSessionService> chatSessionServiceProvider;
    private final ObjectProvider<QueryContextualizerService> queryContextualizerServiceProvider;
    private final ObjectProvider<RagCacheService> ragCacheServiceProvider;
    private final AiProperties aiProperties;
    private final RagProperties ragProperties;
    private final Environment environment;

    @Autowired
    public RagChatService(
        VectorRetrievalService vectorRetrievalService,
        GroundedSystemPromptService groundedSystemPromptService,
        RbacVectorFilterFactory rbacVectorFilterFactory,
        ChatClient groqChatClient,
        @Qualifier("geminiChatClient") ObjectProvider<ChatClient> geminiChatClientProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        ObjectProvider<ChatSessionService> chatSessionServiceProvider,
        ObjectProvider<QueryContextualizerService> queryContextualizerServiceProvider,
        ObjectProvider<RagCacheService> ragCacheServiceProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        Environment environment
    ) {
        this.vectorRetrievalService = Objects.requireNonNull(vectorRetrievalService, "vectorRetrievalService must not be null");
        this.groundedSystemPromptService = Objects.requireNonNull(groundedSystemPromptService, "groundedSystemPromptService must not be null");
        this.rbacVectorFilterFactory = Objects.requireNonNull(rbacVectorFilterFactory, "rbacVectorFilterFactory must not be null");
        this.groqChatClient = Objects.requireNonNull(groqChatClient, "groqChatClient must not be null");
        this.geminiChatClientProvider = Objects.requireNonNull(geminiChatClientProvider, "geminiChatClientProvider must not be null");
        this.userRepositoryProvider = Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null");
        this.departmentRepositoryProvider = Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null");
        this.auditLogRepositoryProvider = Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null");
        this.transactionTemplateProvider = Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null");
        this.chatSessionServiceProvider = Objects.requireNonNull(chatSessionServiceProvider, "chatSessionServiceProvider must not be null");
        this.queryContextualizerServiceProvider = Objects.requireNonNull(queryContextualizerServiceProvider, "queryContextualizerServiceProvider must not be null");
        this.ragCacheServiceProvider = ragCacheServiceProvider;
        this.aiProperties = aiProperties;
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    public RagChatService(
        VectorRetrievalService vectorRetrievalService,
        GroundedSystemPromptService groundedSystemPromptService,
        RbacVectorFilterFactory rbacVectorFilterFactory,
        ChatClient groqChatClient,
        ObjectProvider<ChatClient> geminiChatClientProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        ObjectProvider<ChatSessionService> chatSessionServiceProvider,
        ObjectProvider<QueryContextualizerService> queryContextualizerServiceProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        Environment environment
    ) {
        this(
            vectorRetrievalService,
            groundedSystemPromptService,
            rbacVectorFilterFactory,
            groqChatClient,
            geminiChatClientProvider,
            userRepositoryProvider,
            departmentRepositoryProvider,
            auditLogRepositoryProvider,
            transactionTemplateProvider,
            chatSessionServiceProvider,
            queryContextualizerServiceProvider,
            null,
            aiProperties,
            ragProperties,
            environment
        );
    }

    /**
     * Executes grounded RAG retrieval and synchronous generation outside any database transaction,
     * short-circuiting with zero answer LLM tokens whenever retrieval produces no eligible chunks.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatAskResponse ask(String rawQuestion, CallerContext caller) {
        return ask(rawQuestion, null, null, caller);
    }

    /**
     * Executes grounded RAG retrieval with optional {@code SYSTEM_ADMIN}-only {@code filterDepartmentId}
     * scoping, dual-layer Java Defense-in-Depth checks, and caller-department FinOps audit attribution.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatAskResponse ask(String rawQuestion, UUID filterDepartmentId, CallerContext caller) {
        return ask(rawQuestion, null, filterDepartmentId, caller);
    }

    /**
     * Executes single-turn or multi-turn grounded RAG question answering with optional session continuation,
     * Turn-1 rewrite bypass, bounded 6-message query contextualization, RBAC-scoped retrieval, FinOps token
     * summation (Rewrite + Answer), and atomic post-turn persistence outside LLM network I/O.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public ChatAskResponse ask(
        String rawQuestion,
        UUID sessionId,
        UUID filterDepartmentId,
        CallerContext caller
    ) {
        long startedAtNanos = System.nanoTime();
        PreparedChatTurn turn = prepareTurn(rawQuestion, sessionId, filterDepartmentId, caller);
        RbacVectorScope rbacScope = turn.rbacScope();
        CallerContext verifiedCaller = turn.verifiedCaller();
        String normalizedQuestion = turn.normalizedQuestion();
        UserEntity verifiedActor = turn.verifiedActor();
        ChatSessionService chatSessionService = turn.chatSessionService();
        QueryContextualizerService contextualizerService = turn.contextualizerService();
        List<ChatMessageEntity> recentHistory = turn.recentHistory();
        UUID auditDepartmentId = turn.auditDepartmentId();
        RagCacheService cacheService = turn.ragCacheService();

        RagCacheService.CacheLookupResult cacheLookup = cacheService != null
            ? cacheService.lookup(rbacScope, normalizedQuestion, recentHistory, turn.preloadedDepartmentVersion())
            : RagCacheService.CacheLookupResult.miss(1L, null, 0L, 0L, 0L);

        if (cacheLookup.isHit()) {
            long latencyMs = elapsedMillis(startedAtNanos);
            ChatSessionService.PersistedTurnResult persisted = persistCompletedTurnOrAuditOnly(
                chatSessionService,
                verifiedActor,
                auditDepartmentId,
                sessionId,
                normalizedQuestion,
                cacheLookup.rewrittenQuery(),
                cacheLookup.answer(),
                cacheLookup.citations(),
                false,
                cacheLookup.modelProvider(),
                cacheLookup.cacheTier(),
                0,
                0,
                latencyMs,
                verifiedCaller.ipAddress()
            );
            return new ChatAskResponse(
                persisted != null ? persisted.sessionId() : null,
                persisted != null ? persisted.assistantMessageId() : null,
                cacheLookup.rewrittenQuery(),
                cacheLookup.answer(),
                false,
                cacheLookup.citations(),
                cacheLookup.modelProvider(),
                true,
                cacheLookup.cacheTier(),
                0,
                0,
                latencyMs
            );
        }

        boolean skipRewrite = recentHistory.isEmpty();

        // Turn 1 bypasses QueryContextualizerService completely (0 rewrite tokens, optimal TTFT)
        QueryContextualizerService.ContextualizedQuery contextualized =
            (!skipRewrite && contextualizerService != null)
                ? contextualizerService.contextualize(normalizedQuestion, recentHistory)
                : QueryContextualizerService.ContextualizedQuery.skipped(normalizedQuestion);

        long versionBeforeRetrieval = cacheService != null
            ? cacheService.readAuthoritativeVersion(rbacScope)
            : cacheLookup.capturedVersion();

        List<RetrievedChunk> chunks;
        try {
            chunks = vectorRetrievalService.retrieve(contextualized.effectiveQuery(), rbacScope);
        } catch (TransientAiException | NonTransientAiException | RestClientException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            recordFailureAuditQuietly(
                verifiedActor,
                auditDepartmentId,
                sessionId,
                chatSessionService,
                verifiedCaller.ipAddress(),
                normalizedQuestion,
                contextualized.promptTokens(),
                contextualized.completionTokens(),
                failureLatencyMs,
                RagConstants.PROVIDER_OLLAMA
            );
            throw new LlmProviderException(RagConstants.PROVIDER_OLLAMA, "Embedding provider failed during vector retrieval", ex);
        } catch (LlmProviderException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            String failedProvider = ex.provider() != null ? ex.provider() : RagConstants.PROVIDER_OLLAMA;
            recordFailureAuditQuietly(
                verifiedActor,
                auditDepartmentId,
                sessionId,
                chatSessionService,
                verifiedCaller.ipAddress(),
                normalizedQuestion,
                contextualized.promptTokens(),
                contextualized.completionTokens(),
                failureLatencyMs,
                failedProvider
            );
            throw ex;
        }

        if (chunks.isEmpty()) {
            long latencyMs = elapsedMillis(startedAtNanos);
            int totalPromptTokens = contextualized.promptTokens();
            int totalCompletionTokens = contextualized.completionTokens();
            String effectiveProvider = contextualized.provider();

            ChatSessionService.PersistedTurnResult persisted = persistCompletedTurnOrAuditOnly(
                chatSessionService,
                verifiedActor,
                auditDepartmentId,
                sessionId,
                normalizedQuestion,
                contextualized.rewrittenQuery(),
                RagConstants.SAFE_REFUSAL_MESSAGE,
                List.of(),
                true,
                effectiveProvider,
                CacheTier.NONE,
                totalPromptTokens,
                totalCompletionTokens,
                latencyMs,
                verifiedCaller.ipAddress()
            );
            return new ChatAskResponse(
                persisted != null ? persisted.sessionId() : null,
                persisted != null ? persisted.assistantMessageId() : null,
                contextualized.rewrittenQuery(),
                RagConstants.SAFE_REFUSAL_MESSAGE,
                true,
                List.of(),
                effectiveProvider,
                totalPromptTokens,
                totalCompletionTokens,
                latencyMs
            );
        }

        // Layer 2b Pre-Prompt Hard Guard enforced inside GroundedSystemPromptService via rbacScope.assertAllAuthorized
        Message systemMessage = groundedSystemPromptService.buildSystemMessage(chunks, rbacScope);
        Prompt prompt = buildAnswerPrompt(systemMessage, recentHistory, normalizedQuestion, contextualized);

        ProviderResult result;
        try {
            result = invokeChatWithFailover(prompt);
        } catch (LlmProviderException ex) {
            long failureLatencyMs = elapsedMillis(startedAtNanos);
            String failedProvider = ex.provider() != null ? ex.provider() : RagConstants.PROVIDER_GROQ;
            recordFailureAuditQuietly(
                verifiedActor,
                auditDepartmentId,
                sessionId,
                chatSessionService,
                verifiedCaller.ipAddress(),
                normalizedQuestion,
                contextualized.promptTokens(),
                contextualized.completionTokens(),
                failureLatencyMs,
                failedProvider
            );
            throw ex;
        }

        long latencyMs = elapsedMillis(startedAtNanos);
        boolean llmRefused = isSafeRefusalResponse(result.answer());
        String finalAnswer = llmRefused ? RagConstants.SAFE_REFUSAL_MESSAGE : result.answer();
        List<CitationDto> finalCitations = llmRefused
            ? List.of()
            : chunks.stream().map(RetrievedChunk::citation).toList();

        int totalPromptTokens = contextualized.promptTokens() + result.promptTokens();
        int totalCompletionTokens = contextualized.completionTokens() + result.completionTokens();

        ChatSessionService.PersistedTurnResult persisted = persistCompletedTurnOrAuditOnly(
            chatSessionService,
            verifiedActor,
            auditDepartmentId,
            sessionId,
            normalizedQuestion,
            contextualized.rewrittenQuery(),
            finalAnswer,
            finalCitations,
            llmRefused,
            result.provider(),
            CacheTier.NONE,
            totalPromptTokens,
            totalCompletionTokens,
            latencyMs,
            verifiedCaller.ipAddress()
        );

        if (cacheService != null && !llmRefused && !finalCitations.isEmpty()) {
            try {
                cacheService.publishIfCurrent(
                    rbacScope,
                    versionBeforeRetrieval,
                    normalizedQuestion,
                    contextualized.rewrittenQuery(),
                    recentHistory,
                    cacheLookup.queryEmbedding(),
                    finalAnswer,
                    finalCitations,
                    false,
                    result.provider()
                );
            } catch (RuntimeException ex) {
                log.warn("Best-effort RAG cache publish failed for scope={}: {}", rbacScope.cacheScopeKey(), ex.getMessage());
            }
        }

        return new ChatAskResponse(
            persisted != null ? persisted.sessionId() : null,
            persisted != null ? persisted.assistantMessageId() : null,
            contextualized.rewrittenQuery(),
            finalAnswer,
            llmRefused,
            finalCitations,
            result.provider(),
            false,
            CacheTier.NONE,
            totalPromptTokens,
            totalCompletionTokens,
            latencyMs
        );
    }

    PreparedChatTurn prepareTurn(
        String rawQuestion,
        UUID sessionId,
        UUID filterDepartmentId,
        CallerContext caller
    ) {
        RbacVectorScope rbacScope = rbacVectorFilterFactory.resolveScope(caller, filterDepartmentId);
        CallerContext verifiedCaller = rbacScope.caller();
        String normalizedQuestion = normalizeAndValidateQuestion(rawQuestion);
        UserEntity verifiedActor = resolveVerifiedActor(verifiedCaller);

        if (rbacScope.filterDepartmentId() != null
            && !departmentRepositoryProvider.getObject().existsById(rbacScope.filterDepartmentId())) {
            throw new ResourceNotFoundException("Department not found: " + rbacScope.filterDepartmentId());
        }

        ChatSessionService chatSessionService = chatSessionServiceProvider.getIfAvailable();
        QueryContextualizerService contextualizerService = queryContextualizerServiceProvider.getIfAvailable();
        RagCacheService ragCacheService = ragCacheServiceProvider != null ? ragCacheServiceProvider.getIfAvailable() : null;

        List<ChatMessageEntity> recentHistory = List.of();
        if (sessionId != null) {
            if (chatSessionService == null || verifiedActor == null) {
                throw new ResourceNotFoundException("Chat session not found: " + sessionId);
            }
            recentHistory = chatSessionService.loadAuthorizedHistoryForTurn(sessionId, verifiedActor.getId(), rbacScope);
        }

        return new PreparedChatTurn(
            rbacScope,
            verifiedCaller,
            verifiedActor,
            extractPreloadedDepartmentVersion(verifiedActor, verifiedCaller),
            resolveAuditDepartmentId(rbacScope),
            normalizedQuestion,
            List.copyOf(recentHistory),
            chatSessionService,
            contextualizerService,
            ragCacheService
        );
    }

    private static Long extractPreloadedDepartmentVersion(UserEntity verifiedActor, CallerContext verifiedCaller) {
        if (verifiedActor == null || verifiedCaller == null || verifiedCaller.departmentId() == null) {
            return null;
        }
        DepartmentEntity dept = verifiedActor.getDepartment();
        if (dept == null || !Hibernate.isInitialized(dept)) {
            return null;
        }
        if (!Objects.equals(dept.getId(), verifiedCaller.departmentId())) {
            return null;
        }
        Long version = dept.getCacheVersion();
        return (version != null && version >= 1L) ? version : null;
    }

    Prompt buildAnswerPrompt(
        Message systemMessage,
        List<ChatMessageEntity> recentHistory,
        String normalizedQuestion,
        QueryContextualizerService.ContextualizedQuery contextualized
    ) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return new Prompt(List.of(systemMessage, new UserMessage(normalizedQuestion)));
        }

        int maxHistoryChars = ragProperties.memory().maxHistoryMessageChars();
        List<Message> messages = new ArrayList<>(recentHistory.size() + 2);
        messages.add(systemMessage);
        appendNonAbstainedHistory(messages, recentHistory, maxHistoryChars);

        String userPromptText = contextualized.wasRewritten()
            ? normalizedQuestion + "\n[Câu hỏi độc lập đã làm rõ: " + contextualized.effectiveQuery() + "]"
            : normalizedQuestion;
        messages.add(new UserMessage(userPromptText));
        return new Prompt(messages);
    }

    private static void appendNonAbstainedHistory(
        List<Message> target,
        List<ChatMessageEntity> chronologicalHistory,
        int maxMessageChars
    ) {
        int size = chronologicalHistory.size();
        for (int i = 0; i < size; i++) {
            ChatMessageEntity current = chronologicalHistory.get(i);
            if (current == null || current.getContent() == null || current.getContent().isBlank()) {
                continue;
            }
            // Skip abstained assistant turns and any orphan leading ASSISTANT turn (e.g. odd windowSize) so Gemini alternation starts with USER
            if (current.getRole() == MessageRole.ASSISTANT && (current.isAbstained() || target.size() == 1)) {
                continue;
            }
            if (current.getRole() == MessageRole.USER
                && i + 1 < size
                && chronologicalHistory.get(i + 1) != null
                && chronologicalHistory.get(i + 1).getRole() == MessageRole.ASSISTANT
                && chronologicalHistory.get(i + 1).isAbstained()) {
                // Skip the paired USER question whose ASSISTANT turn abstained so prior OOS turns don't pollute context
                i++;
                continue;
            }
            String boundedContent = QueryContextualizerService.truncateSafe(current.getContent(), maxMessageChars);
            if (current.getRole() == MessageRole.ASSISTANT) {
                target.add(new AssistantMessage(boundedContent));
            } else {
                target.add(new UserMessage(boundedContent));
            }
        }
    }

    ChatSessionService.PersistedTurnResult persistCompletedTurnOrAuditOnly(
        ChatSessionService chatSessionService,
        UserEntity verifiedActor,
        UUID auditDepartmentId,
        UUID sessionId,
        String normalizedQuestion,
        String rewrittenQuery,
        String finalAnswer,
        List<CitationDto> finalCitations,
        boolean abstained,
        String provider,
        CacheTier cacheTier,
        int totalPromptTokens,
        int totalCompletionTokens,
        long latencyMs,
        String ipAddress
    ) {
        CacheTier effectiveCacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        if (chatSessionService != null && verifiedActor != null) {
            return chatSessionService.recordCompletedTurn(
                new ChatSessionService.CompletedTurnCommand(
                    verifiedActor,
                    auditDepartmentId,
                    sessionId,
                    normalizedQuestion,
                    rewrittenQuery,
                    finalAnswer,
                    finalCitations,
                    abstained,
                    provider,
                    effectiveCacheTier,
                    totalPromptTokens,
                    totalCompletionTokens,
                    latencyMs,
                    ipAddress
                )
            );
        }
        saveAuditLog(
            verifiedActor,
            auditDepartmentId,
            normalizedQuestion,
            abstained,
            effectiveCacheTier,
            totalPromptTokens,
            totalCompletionTokens,
            latencyMs,
            provider,
            HttpStatus.OK.value(),
            ipAddress
        );
        return null;
    }

    private ProviderResult invokeChatWithFailover(Prompt prompt) {
        try {
            return invokeSingleProvider(groqChatClient, RagConstants.PROVIDER_GROQ, prompt);
        } catch (RuntimeException primaryFailure) {
            if (!QueryContextualizerService.isGeminiConfigured(ragProperties, geminiChatClientProvider, aiProperties)) {
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

        QueryContextualizerService.TokenCounts tokens = QueryContextualizerService.extractTokenCounts(response);
        return new ProviderResult(providerName, text.trim(), tokens.promptTokens(), tokens.completionTokens());
    }

    String normalizeAndValidateQuestion(String rawQuestion) {
        String normalized = VectorRetrievalService.normalizeQuery(rawQuestion);
        int maxQuestionChars = ragProperties.llm().maxQuestionChars();
        if (normalized.length() > maxQuestionChars) {
            throw new InvalidChatRequestException(
                "Question exceeds maximum allowed length of " + maxQuestionChars + " characters"
            );
        }
        return normalized;
    }

    UserEntity resolveVerifiedActor(CallerContext caller) {
        UUID userId = caller.userId();
        if (userId == null) {
            return null;
        }
        UserEntity user = userRepositoryProvider.getObject().findById(userId)
            .orElseGet(() -> {
                if (DevCallerFallbackEnabledCondition.isDevFallbackActive(environment)
                    && DevFallbackCallerContextResolver.DEFAULT_DEV_USER_ID.equals(userId)) {
                    return null;
                }
                throw new ResourceNotFoundException("User not found: " + userId);
            });
        if (user == null) {
            return null;
        }
        ChatSessionService.assertCallerMatchesUser(caller, user);
        return user;
    }

    static UUID resolveAuditDepartmentId(RbacVectorScope rbacScope) {
        return rbacScope.caller().departmentId() != null
            ? rbacScope.caller().departmentId()
            : rbacScope.filterDepartmentId();
    }

    private void recordFailureAuditQuietly(
        UserEntity verifiedActor,
        UUID auditDepartmentId,
        UUID sessionId,
        ChatSessionService chatSessionService,
        String ipAddress,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String failedProvider
    ) {
        recordFailureAuditQuietly(
            verifiedActor,
            auditDepartmentId,
            sessionId,
            chatSessionService,
            ipAddress,
            queryText,
            promptTokens,
            completionTokens,
            latencyMs,
            failedProvider,
            HttpStatus.BAD_GATEWAY.value()
        );
    }

    void recordFailureAuditQuietly(
        UserEntity verifiedActor,
        UUID auditDepartmentId,
        UUID sessionId,
        ChatSessionService chatSessionService,
        String ipAddress,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String failedProvider,
        int statusCode
    ) {
        recordFailureAuditQuietly(
            verifiedActor,
            auditDepartmentId,
            sessionId,
            chatSessionService,
            ipAddress,
            queryText,
            promptTokens,
            completionTokens,
            latencyMs,
            failedProvider,
            statusCode,
            CacheTier.NONE
        );
    }

    void recordFailureAuditQuietly(
        UserEntity verifiedActor,
        UUID auditDepartmentId,
        UUID sessionId,
        ChatSessionService chatSessionService,
        String ipAddress,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String failedProvider,
        int statusCode,
        CacheTier cacheTier
    ) {
        CacheTier effectiveCacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        try {
            if (chatSessionService != null) {
                if (effectiveCacheTier == CacheTier.NONE) {
                    chatSessionService.recordFailureAudit(
                        verifiedActor,
                        auditDepartmentId,
                        sessionId,
                        queryText,
                        promptTokens,
                        completionTokens,
                        latencyMs,
                        failedProvider,
                        statusCode,
                        ipAddress
                    );
                } else {
                    chatSessionService.recordFailureAudit(
                        verifiedActor,
                        auditDepartmentId,
                        sessionId,
                        queryText,
                        promptTokens,
                        completionTokens,
                        latencyMs,
                        failedProvider,
                        statusCode,
                        effectiveCacheTier,
                        ipAddress
                    );
                }
                return;
            }
            saveAuditLog(
                verifiedActor,
                auditDepartmentId,
                queryText,
                true,
                effectiveCacheTier,
                promptTokens,
                completionTokens,
                latencyMs,
                failedProvider,
                statusCode,
                ipAddress
            );
        } catch (RuntimeException auditEx) {
            log.warn("Failed to persist failure audit log (status={}): {}", statusCode, auditEx.getMessage());
        }
    }

    void saveAuditLog(
        UserEntity user,
        UUID departmentId,
        String queryText,
        boolean abstained,
        CacheTier cacheTier,
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
            auditLog.setCacheTier(cacheTier != null ? cacheTier : CacheTier.NONE);
            auditLog.setPromptTokens(Math.max(0, promptTokens));
            auditLog.setCompletionTokens(Math.max(0, completionTokens));
            auditLog.setLatencyMs(Math.max(0L, latencyMs));
            auditLog.setProvider(provider);
            auditLog.setStatusCode(statusCode);
            auditLog.setIpAddress(ChatSessionService.truncateIp(ipAddress));
            auditLogRepositoryProvider.getObject().save(auditLog);
        });
    }

    static boolean isSafeRefusalResponse(String answer) {
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

    static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    record PreparedChatTurn(
        RbacVectorScope rbacScope,
        CallerContext verifiedCaller,
        UserEntity verifiedActor,
        Long preloadedDepartmentVersion,
        UUID auditDepartmentId,
        String normalizedQuestion,
        List<ChatMessageEntity> recentHistory,
        ChatSessionService chatSessionService,
        QueryContextualizerService contextualizerService,
        RagCacheService ragCacheService
    ) {
    }

    private record ProviderResult(
        String provider,
        String answer,
        int promptTokens,
        int completionTokens
    ) {
    }
}
