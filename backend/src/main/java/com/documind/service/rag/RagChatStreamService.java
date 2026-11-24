package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.chat.ChatStreamCitationsEvent;
import com.documind.controller.dto.chat.ChatStreamDoneEvent;
import com.documind.controller.dto.chat.ChatStreamErrorEvent;
import com.documind.controller.dto.chat.ChatStreamTokenEvent;
import com.documind.controller.dto.chat.ChatTokenUsageDto;
import com.documind.domain.enums.CacheTier;
import com.documind.exception.LlmProviderException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.infrastructure.config.IngestionAsyncConfig;
import com.documind.infrastructure.security.PreviewBaseUrlResolver;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.OverlappingTokenTextSplitter;
import jakarta.annotation.PreDestroy;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

/**
 * Orchestrates real-time typed Server-Sent Events (SSE) RAG streaming with a two-phase error boundary:
 * synchronous pre-flight validation/RBAC/session-ownership checks return standard HTTP ProblemDetail
 * responses, while post-open execution runs on a Java 21 Virtual Thread emitting {@code citations},
 * {@code token}, {@code done}, and {@code error} events without blocking Reactor I/O loops.
 *
 * <p><b>Latency SLA Scope ({@code DA-16}):</b>
 * <ul>
 *   <li><b>Turn 1 (Zero-Rewrite Direct Retrieval Path, {@code recentHistory.isEmpty()}):</b>
 *       {@code citations < 500 ms} and first visible streamed token {@code TTFT < 2,500 ms} end-to-end
 *       on warm {@code bge-m3} + live streaming LLM provider.</li>
 *   <li><b>Turn 2+ (Contextualized Multi-Turn Follow-Up Path):</b> executes synchronous standalone
 *       query rewrite via {@link QueryContextualizerService} prior to {@code bge-m3} embedding;
 *       the {@code < 500 ms} retrieval-to-citations threshold and {@code < 2,500 ms} answer-generation
 *       TTFT threshold govern the post-rewrite retrieval and answer-streaming phases respectively
 *       ({@code postRewriteCitationsMs < 500 ms}, {@code postCitationsFirstTokenMs < 2,500 ms}).</li>
 * </ul>
 */
@Service
public class RagChatStreamService {

    public static final String EVENT_CITATIONS = "citations";
    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_DONE = "done";
    public static final String EVENT_ERROR = "error";
    public static final int STATUS_CLIENT_CLOSED_REQUEST = 499;

    private static final Logger log = LoggerFactory.getLogger(RagChatStreamService.class);
    private static final long EMITTER_SAFETY_BUFFER_MS = 5_000L;
    private static final int STREAM_PREFETCH_WINDOW = 64;

    private final RagChatService ragChatService;
    private final VectorRetrievalService vectorRetrievalService;
    private final GroundedSystemPromptService groundedSystemPromptService;
    private final ChatClient groqChatClient;
    private final ObjectProvider<ChatClient> geminiChatClientProvider;
    private final AiProperties aiProperties;
    private final RagProperties ragProperties;
    private final AsyncTaskExecutor chatSseExecutor;
    private final LongFunction<SseEmitter> emitterFactory;
    private final ScheduledThreadPoolExecutor timeoutScheduler;

    @Autowired
    public RagChatStreamService(
        RagChatService ragChatService,
        VectorRetrievalService vectorRetrievalService,
        GroundedSystemPromptService groundedSystemPromptService,
        ChatClient groqChatClient,
        @Qualifier("geminiChatClient") ObjectProvider<ChatClient> geminiChatClientProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        @Qualifier(IngestionAsyncConfig.CHAT_SSE_TASK_EXECUTOR) AsyncTaskExecutor chatSseExecutor
    ) {
        this(
            ragChatService,
            vectorRetrievalService,
            groundedSystemPromptService,
            groqChatClient,
            geminiChatClientProvider,
            aiProperties,
            ragProperties,
            chatSseExecutor,
            SseEmitter::new
        );
    }

    RagChatStreamService(
        RagChatService ragChatService,
        VectorRetrievalService vectorRetrievalService,
        GroundedSystemPromptService groundedSystemPromptService,
        ChatClient groqChatClient,
        ObjectProvider<ChatClient> geminiChatClientProvider,
        AiProperties aiProperties,
        RagProperties ragProperties,
        AsyncTaskExecutor chatSseExecutor,
        LongFunction<SseEmitter> emitterFactory
    ) {
        this.ragChatService = Objects.requireNonNull(ragChatService, "ragChatService must not be null");
        this.vectorRetrievalService = Objects.requireNonNull(vectorRetrievalService, "vectorRetrievalService must not be null");
        this.groundedSystemPromptService = Objects.requireNonNull(groundedSystemPromptService, "groundedSystemPromptService must not be null");
        this.groqChatClient = Objects.requireNonNull(groqChatClient, "groqChatClient must not be null");
        this.geminiChatClientProvider = Objects.requireNonNull(geminiChatClientProvider, "geminiChatClientProvider must not be null");
        this.aiProperties = aiProperties;
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
        this.chatSseExecutor = Objects.requireNonNull(chatSseExecutor, "chatSseExecutor must not be null");
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory must not be null");
        this.timeoutScheduler = new ScheduledThreadPoolExecutor(
            1,
            Thread.ofPlatform().daemon(true).name("documind-sse-timeout-", 0).factory()
        );
        this.timeoutScheduler.setRemoveOnCancelPolicy(true);
    }

    @PreDestroy
    void shutdownTimeoutScheduler() {
        timeoutScheduler.shutdownNow();
    }

    /**
     * Phase 1: validates input, RBAC scope, optional admin department filter, and session ownership
     * synchronously before returning an {@link SseEmitter} driven by a Java 21 Virtual Thread.
     */
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')")
    public SseEmitter openStream(
        String rawQuestion,
        UUID sessionId,
        UUID filterDepartmentId,
        CallerContext caller
    ) {
        long startedAtNanos = System.nanoTime();
        RagChatService.PreparedChatTurn turn = ragChatService.prepareTurn(
            rawQuestion,
            sessionId,
            filterDepartmentId,
            caller
        );

        RagCacheService cacheService = turn.ragCacheService();
        RagCacheService.L1LookupResult l1Outcome = cacheService != null
            ? cacheService.lookupL1(
                turn.rbacScope(),
                turn.normalizedQuestion(),
                turn.recentHistory(),
                turn.preloadedDepartmentVersion()
            )
            : null;

        SecurityContext securityContextSnapshot = snapshotSecurityContext();
        String requestBaseUrl = PreviewBaseUrlResolver.captureCurrentRequestOriginOrNull();
        long configuredTimeoutMs = ragProperties.sse().timeout().toMillis();
        long deadlineNanos = startedAtNanos + TimeUnit.MILLISECONDS.toNanos(configuredTimeoutMs);
        SseEmitter emitter = Objects.requireNonNull(
            emitterFactory.apply(configuredTimeoutMs + EMITTER_SAFETY_BUFFER_MS),
            "emitterFactory must not return null"
        );

        StreamExecutionContext ctx = new StreamExecutionContext(
            startedAtNanos,
            deadlineNanos,
            turn,
            sessionId,
            securityContextSnapshot,
            requestBaseUrl,
            emitter,
            l1Outcome
        );
        StreamExecutionState state = new StreamExecutionState();

        emitter.onCompletion(() -> {
            if (!state.terminalHandled.get()) {
                state.cancelUpstream(false);
            }
        });
        emitter.onTimeout(() -> {
            if (!state.terminalHandled.get()) {
                state.cancelUpstream(true);
                chatSseExecutor.execute(() -> runWithSecurityContext(
                    ctx.securityContextSnapshot(),
                    () -> handleStreamTimeout(ctx, state)
                ));
            }
        });
        emitter.onError(ex -> {
            if (!state.terminalHandled.get()) {
                log.debug("SSE emitter transport error (client disconnect): {}", ex != null ? ex.getMessage() : "unknown");
                state.cancelUpstream(false);
            }
        });

        // Synchronous L1 fast-path avoids Virtual Thread + Tomcat AsyncContext dispatch overhead on exact hits
        if (l1Outcome != null && l1Outcome.isHit()) {
            PreviewBaseUrlResolver.runWithBaseUrl(
                requestBaseUrl,
                () -> executeSyncL1CacheReplay(ctx, state, l1Outcome.hitResult())
            );
            return emitter;
        }

        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(
            () -> {
                if (!state.terminalHandled.get()) {
                    state.cancelUpstream(true);
                }
            },
            configuredTimeoutMs,
            TimeUnit.MILLISECONDS
        );
        state.timeoutFuture.set(timeoutTask);

        chatSseExecutor.execute(() -> PreviewBaseUrlResolver.runWithBaseUrl(
            ctx.requestBaseUrl(),
            () -> runStreamPipeline(ctx, state)
        ));
        return emitter;
    }

    private void executeSyncL1CacheReplay(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        RagCacheService.CacheLookupResult hit
    ) {
        try {
            handleCacheHitReplay(ctx, state, hit);
        } catch (Throwable unexpected) {
            if (unexpected instanceof Error err) {
                throw err;
            }
            log.error("Unhandled exception during synchronous SSE L1 cache replay", unexpected);
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_INTERNAL_ERROR,
                "An unexpected internal error occurred",
                state.currentProvider.get(),
                false,
                HttpStatus.INTERNAL_SERVER_ERROR.value()
            );
        } finally {
            state.detachWorkerAndCleanup();
            if (state.terminalHandled.get()) {
                completeQuietly(ctx.emitter());
            }
        }
    }

    private void runStreamPipeline(StreamExecutionContext ctx, StreamExecutionState state) {
        SecurityContextHolder.setContext(ctx.securityContextSnapshot());
        state.registerWorker(Thread.currentThread());
        try {
            if (checkAbortOrTimeout(ctx, state)) {
                return;
            }

            RagCacheService cacheService = ctx.turn().ragCacheService();
            RagCacheService.L1LookupResult l1Outcome = ctx.l1Outcome();
            RagCacheService.CacheLookupResult cacheLookup;
            if (cacheService != null && l1Outcome != null && !l1Outcome.contextual()) {
                cacheLookup = cacheService.lookupL2(
                    ctx.turn().rbacScope(),
                    ctx.turn().normalizedQuestion(),
                    l1Outcome.capturedVersion(),
                    l1Outcome.l1LookupNanos()
                );
            } else if (l1Outcome != null) {
                cacheLookup = RagCacheService.CacheLookupResult.miss(
                    l1Outcome.capturedVersion(),
                    null,
                    l1Outcome.l1LookupNanos(),
                    0L,
                    0L
                );
            } else {
                cacheLookup = RagCacheService.CacheLookupResult.miss(1L, null, 0L, 0L, 0L);
            }

            if (checkAbortOrTimeout(ctx, state)) {
                return;
            }
            if (cacheLookup.isHit()) {
                handleCacheHitReplay(ctx, state, cacheLookup);
                return;
            }

            boolean followUpTurn = !ctx.turn().recentHistory().isEmpty() && ctx.turn().contextualizerService() != null;
            long rewriteStartedAtNanos = System.nanoTime();
            QueryContextualizerService.ContextualizedQuery contextualized = followUpTurn
                ? ctx.turn().contextualizerService().contextualize(ctx.turn().normalizedQuestion(), ctx.turn().recentHistory())
                : QueryContextualizerService.ContextualizedQuery.skipped(ctx.turn().normalizedQuestion());
            long rewriteCompletedAtNanos = System.nanoTime();
            long rewriteLatencyMs = followUpTurn
                ? TimeUnit.NANOSECONDS.toMillis(rewriteCompletedAtNanos - rewriteStartedAtNanos)
                : 0L;
            state.rewritePromptTokens.set(contextualized.promptTokens());
            state.rewriteCompletionTokens.set(contextualized.completionTokens());
            state.currentProvider.set(contextualized.provider());

            if (checkAbortOrTimeout(ctx, state)) {
                return;
            }

            long versionBeforeRetrieval = cacheService != null
                ? cacheService.readAuthoritativeVersion(ctx.turn().rbacScope())
                : cacheLookup.capturedVersion();

            // Step 2: RBAC-enforced vector retrieval
            List<RetrievedChunk> chunks;
            try {
                chunks = vectorRetrievalService.retrieve(contextualized.effectiveQuery(), ctx.turn().rbacScope());
            } catch (TransientAiException | NonTransientAiException | RestClientException ex) {
                if (checkAbortOrTimeout(ctx, state)) {
                    return;
                }
                emitFailureAndClose(
                    ctx,
                    state,
                    ChatStreamErrorEvent.CODE_LLM_PROVIDER_UNAVAILABLE,
                    "Embedding provider is temporarily unavailable",
                    RagConstants.PROVIDER_OLLAMA,
                    true,
                    HttpStatus.BAD_GATEWAY.value()
                );
                return;
            } catch (LlmProviderException ex) {
                if (checkAbortOrTimeout(ctx, state)) {
                    return;
                }
                String failedProvider = ex.provider() != null ? ex.provider() : RagConstants.PROVIDER_OLLAMA;
                emitFailureAndClose(
                    ctx,
                    state,
                    ChatStreamErrorEvent.CODE_LLM_PROVIDER_UNAVAILABLE,
                    "Embedding provider is temporarily unavailable",
                    failedProvider,
                    true,
                    HttpStatus.BAD_GATEWAY.value()
                );
                return;
            }

            if (checkAbortOrTimeout(ctx, state)) {
                return;
            }

            // Step 3: Deterministic zero-answer-token refusal when no chunks meet similarity threshold
            if (chunks.isEmpty()) {
                handleDeterministicEmptyRefusal(ctx, state, contextualized);
                return;
            }

            // Step 4: Emit structured citations immediately (< 500ms post-rewrite / end-to-end Turn 1) prior to LLM generation
            List<CitationDto> retrievedCitations = chunks.stream().map(RetrievedChunk::citation).toList();
            if (!sendEvent(ctx, state, EVENT_CITATIONS, new ChatStreamCitationsEvent(retrievedCitations, contextualized.rewrittenQuery()))) {
                handleClientDisconnected(ctx, state);
                return;
            }
            if (log.isDebugEnabled()) {
                long postRewriteCitationsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - rewriteCompletedAtNanos);
                long totalCitationsMs = RagChatService.elapsedMillis(ctx.startedAtNanos());
                log.debug(
                    "SSE citations emitted: sessionId={}, followUpTurn={}, rewriteMs={}, postRewriteCitationsMs={}, totalCitationsMs={}",
                    ctx.sessionId(),
                    followUpTurn,
                    rewriteLatencyMs,
                    postRewriteCitationsMs,
                    totalCitationsMs
                );
            }

            // Layer 2b Pre-Prompt RBAC Hard Guard enforced inside GroundedSystemPromptService
            Message systemMessage = groundedSystemPromptService.buildSystemMessage(chunks, ctx.turn().rbacScope());
            Prompt prompt = ragChatService.buildAnswerPrompt(
                systemMessage,
                ctx.turn().recentHistory(),
                ctx.turn().normalizedQuestion(),
                contextualized
            );
            state.activePromptRef.set(prompt);

            if (!streamWithPreTokenFailover(ctx, state, prompt)) {
                return;
            }

            completeAndPersistTurn(
                ctx,
                state,
                contextualized,
                retrievedCitations,
                prompt,
                new CachePublishContext(cacheService, versionBeforeRetrieval, cacheLookup.queryEmbedding())
            );
        } catch (Throwable unexpected) {
            if (unexpected instanceof Error err) {
                throw err;
            }
            if (state.cancelled.get()) {
                if (state.timedOut.get()) {
                    handleStreamTimeout(ctx, state);
                } else {
                    handleClientDisconnected(ctx, state);
                }
                return;
            }
            log.error("Unhandled exception during SSE chat streaming", unexpected);
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_INTERNAL_ERROR,
                "An unexpected internal error occurred",
                state.currentProvider.get(),
                false,
                HttpStatus.INTERNAL_SERVER_ERROR.value()
            );
        } finally {
            state.detachWorkerAndCleanup();
            if (state.terminalHandled.get()) {
                completeQuietly(ctx.emitter());
            }
            // Clear interrupt flag if cancellation interrupted this virtual thread before returning it to pool
            Thread.interrupted();
            SecurityContextHolder.clearContext();
        }
    }

    private void handleCacheHitReplay(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        RagCacheService.CacheLookupResult hit
    ) {
        state.activeCacheTier.set(hit.cacheTier());
        state.currentProvider.set(hit.modelProvider());
        if (!sendEvent(ctx, state, EVENT_CITATIONS, new ChatStreamCitationsEvent(hit.citations(), hit.rewrittenQuery()))) {
            handleClientDisconnected(ctx, state);
            return;
        }
        if (!sendEvent(ctx, state, EVENT_TOKEN, new ChatStreamTokenEvent(hit.answer()))) {
            handleClientDisconnected(ctx, state);
            return;
        }
        state.anyTokenEmitted.set(true);
        persistTurnAndEmitTerminal(
            ctx,
            state,
            hit.rewrittenQuery(),
            hit.answer(),
            hit.citations(),
            false,
            hit.modelProvider(),
            hit.cacheTier(),
            0,
            0,
            null
        );
    }

    private void handleDeterministicEmptyRefusal(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        QueryContextualizerService.ContextualizedQuery contextualized
    ) {
        if (!sendEvent(ctx, state, EVENT_CITATIONS, new ChatStreamCitationsEvent(List.of(), contextualized.rewrittenQuery()))) {
            handleClientDisconnected(ctx, state);
            return;
        }
        if (!sendEvent(ctx, state, EVENT_TOKEN, new ChatStreamTokenEvent(RagConstants.SAFE_REFUSAL_MESSAGE))) {
            handleClientDisconnected(ctx, state);
            return;
        }
        persistTurnAndEmitTerminal(
            ctx,
            state,
            contextualized.rewrittenQuery(),
            RagConstants.SAFE_REFUSAL_MESSAGE,
            List.of(),
            true,
            contextualized.provider(),
            CacheTier.NONE,
            contextualized.promptTokens(),
            contextualized.completionTokens(),
            null
        );
    }

    private boolean streamWithPreTokenFailover(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        Prompt prompt
    ) {
        ProviderStreamOutcome primaryOutcome = streamSingleProvider(
            groqChatClient,
            RagConstants.PROVIDER_GROQ,
            prompt,
            ctx,
            state
        );
        if (primaryOutcome.status() == ProviderStreamStatus.COMPLETED) {
            return true;
        }
        if (primaryOutcome.status() == ProviderStreamStatus.CLIENT_DISCONNECTED) {
            handleClientDisconnected(ctx, state);
            return false;
        }
        if (primaryOutcome.status() == ProviderStreamStatus.TIMED_OUT) {
            handleStreamTimeout(ctx, state);
            return false;
        }

        if (checkAbortOrTimeout(ctx, state)) {
            return false;
        }

        // Groq failed: only fail over to Gemini if ZERO visible tokens have been flushed to the client
        if (state.anyTokenEmitted.get()) {
            log.warn(
                "Primary Groq stream interrupted mid-response after emitting partial tokens: {}",
                primaryOutcome.error() != null ? primaryOutcome.error().getMessage() : "unknown"
            );
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_LLM_STREAM_INTERRUPTED,
                "Language model stream was interrupted before completion",
                RagConstants.PROVIDER_GROQ,
                true,
                HttpStatus.BAD_GATEWAY.value()
            );
            return false;
        }

        if (!QueryContextualizerService.isGeminiConfigured(ragProperties, geminiChatClientProvider, aiProperties)) {
            log.warn(
                "Primary Groq stream failed and Gemini fallback is not configured: {}",
                primaryOutcome.error() != null ? primaryOutcome.error().getMessage() : "unknown"
            );
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_LLM_PROVIDER_UNAVAILABLE,
                "Language model service is temporarily unavailable",
                RagConstants.PROVIDER_GROQ,
                true,
                HttpStatus.BAD_GATEWAY.value()
            );
            return false;
        }

        log.warn(
            "Primary Groq stream failed before first token, falling back to Gemini: {}",
            primaryOutcome.error() != null ? primaryOutcome.error().getMessage() : "unknown"
        );
        state.resetAnswerAttempt();

        ChatClient geminiClient = geminiChatClientProvider.getIfAvailable();
        ProviderStreamOutcome fallbackOutcome = streamSingleProvider(
            geminiClient,
            RagConstants.PROVIDER_GEMINI,
            prompt,
            ctx,
            state
        );
        if (fallbackOutcome.status() == ProviderStreamStatus.COMPLETED) {
            return true;
        }
        if (fallbackOutcome.status() == ProviderStreamStatus.CLIENT_DISCONNECTED) {
            handleClientDisconnected(ctx, state);
            return false;
        }
        if (fallbackOutcome.status() == ProviderStreamStatus.TIMED_OUT) {
            handleStreamTimeout(ctx, state);
            return false;
        }

        if (checkAbortOrTimeout(ctx, state)) {
            return false;
        }

        if (state.anyTokenEmitted.get()) {
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_LLM_STREAM_INTERRUPTED,
                "Fallback language model stream was interrupted before completion",
                RagConstants.PROVIDER_GEMINI,
                true,
                HttpStatus.BAD_GATEWAY.value()
            );
        } else {
            emitFailureAndClose(
                ctx,
                state,
                ChatStreamErrorEvent.CODE_LLM_PROVIDER_UNAVAILABLE,
                "All configured language model providers are temporarily unavailable",
                RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER,
                true,
                HttpStatus.BAD_GATEWAY.value()
            );
        }
        return false;
    }

    private ProviderStreamOutcome streamSingleProvider(
        ChatClient client,
        String providerName,
        Prompt prompt,
        StreamExecutionContext ctx,
        StreamExecutionState state
    ) {
        if (client == null) {
            return ProviderStreamOutcome.error(new IllegalStateException("ChatClient unavailable for provider: " + providerName));
        }
        state.currentProvider.set(providerName);

        Flux<ChatResponse> responseFlux;
        try {
            responseFlux = client.prompt(prompt).stream().chatResponse();
        } catch (RuntimeException ex) {
            if (state.timedOut.get() || System.nanoTime() >= ctx.deadlineNanos()) {
                state.cancelUpstream(true);
                return ProviderStreamOutcome.timedOut();
            }
            if (state.cancelled.get() || Thread.currentThread().isInterrupted()) {
                state.cancelUpstream(false);
                return ProviderStreamOutcome.clientDisconnected();
            }
            return ProviderStreamOutcome.error(ex);
        }
        if (responseFlux == null) {
            return ProviderStreamOutcome.error(new IllegalStateException("Null response stream from provider: " + providerName));
        }

        BlockingQueue<StreamSignal> queue = new LinkedBlockingQueue<>();
        state.activeQueue.set(queue);

        BaseSubscriber<ChatResponse> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(STREAM_PREFETCH_WINDOW);
            }

            @Override
            protected void hookOnNext(ChatResponse chunk) {
                queue.offer(StreamSignal.next(chunk));
            }

            @Override
            protected void hookOnError(Throwable error) {
                queue.offer(StreamSignal.error(error));
            }

            @Override
            protected void hookOnComplete() {
                queue.offer(StreamSignal.complete());
            }
        };
        state.activeSubscription.set(subscriber);
        responseFlux.subscribe(subscriber);

        if (state.cancelled.get()) {
            state.disposeActiveSubscription();
            return state.timedOut.get()
                ? ProviderStreamOutcome.timedOut()
                : ProviderStreamOutcome.clientDisconnected();
        }

        try {
            while (true) {
                if (state.cancelled.get()) {
                    return state.timedOut.get()
                        ? ProviderStreamOutcome.timedOut()
                        : ProviderStreamOutcome.clientDisconnected();
                }

                long remainingNanos = ctx.deadlineNanos() - System.nanoTime();
                if (remainingNanos <= 0L) {
                    state.cancelUpstream(true);
                    return ProviderStreamOutcome.timedOut();
                }

                StreamSignal signal;
                try {
                    signal = queue.poll(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    if (state.timedOut.get()) {
                        return ProviderStreamOutcome.timedOut();
                    }
                    if (state.cancelled.get()) {
                        return ProviderStreamOutcome.clientDisconnected();
                    }
                    Thread.currentThread().interrupt();
                    return ProviderStreamOutcome.clientDisconnected();
                }

                if (signal == null) {
                    state.cancelUpstream(true);
                    return ProviderStreamOutcome.timedOut();
                }

                switch (signal.kind()) {
                    case NEXT -> {
                        ChatResponse chunk = signal.chunk();
                        updateLatestUsage(chunk, state);
                        String delta = extractDeltaText(chunk);
                        if (delta != null && !delta.isEmpty()) {
                            if (!handleDeltaChunk(ctx, state, delta)) {
                                state.cancelUpstream(false);
                                return ProviderStreamOutcome.clientDisconnected();
                            }
                        }
                        subscriber.request(1L);
                    }
                    case COMPLETE -> {
                        if (state.answerBuffer.toString().isBlank()) {
                            return ProviderStreamOutcome.error(
                                new LlmProviderException(providerName, "Blank stream response from provider: " + providerName)
                            );
                        }
                        return ProviderStreamOutcome.completed();
                    }
                    case ERROR -> {
                        if (state.timedOut.get() || System.nanoTime() >= ctx.deadlineNanos()) {
                            state.cancelUpstream(true);
                            return ProviderStreamOutcome.timedOut();
                        }
                        if (state.cancelled.get()) {
                            return ProviderStreamOutcome.clientDisconnected();
                        }
                        return ProviderStreamOutcome.error(signal.error());
                    }
                    case CANCELLED -> {
                        return state.timedOut.get()
                            ? ProviderStreamOutcome.timedOut()
                            : ProviderStreamOutcome.clientDisconnected();
                    }
                }
            }
        } finally {
            state.disposeActiveSubscription();
            state.activeQueue.compareAndSet(queue, null);
        }
    }

    private boolean handleDeltaChunk(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        String delta
    ) {
        if (!state.anyTokenEmitted.get()) {
            if (delta.isBlank()) {
                // Buffer leading whitespace without flipping anyTokenEmitted so blank/whitespace-only streams still fail over
                state.leadingWhitespaceBuffer.append(delta);
                state.answerBuffer.append(delta);
                return true;
            }
            String firstVisibleDelta = state.leadingWhitespaceBuffer.isEmpty()
                ? delta
                : state.leadingWhitespaceBuffer + delta;
            state.leadingWhitespaceBuffer.setLength(0);
            state.answerBuffer.append(delta);
            if (!sendEvent(ctx, state, EVENT_TOKEN, new ChatStreamTokenEvent(firstVisibleDelta))) {
                return false;
            }
            state.anyTokenEmitted.set(true);
            return true;
        }
        state.answerBuffer.append(delta);
        return sendEvent(ctx, state, EVENT_TOKEN, new ChatStreamTokenEvent(delta));
    }

    private void completeAndPersistTurn(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        QueryContextualizerService.ContextualizedQuery contextualized,
        List<CitationDto> retrievedCitations,
        Prompt prompt,
        CachePublishContext publishContext
    ) {
        String trimmedAnswer = state.answerBuffer.toString().trim();
        boolean llmRefused = RagChatService.isSafeRefusalResponse(trimmedAnswer);
        String finalAnswer = llmRefused ? RagConstants.SAFE_REFUSAL_MESSAGE : trimmedAnswer;
        List<CitationDto> finalCitations = llmRefused ? List.of() : retrievedCitations;

        QueryContextualizerService.TokenCounts answerTokens = resolveAnswerTokensWithFallback(
            state,
            prompt,
            trimmedAnswer
        );
        int totalPromptTokens = totalPromptTokens(state, answerTokens);
        int totalCompletionTokens = totalCompletionTokens(state, answerTokens);

        persistTurnAndEmitTerminal(
            ctx,
            state,
            contextualized.rewrittenQuery(),
            finalAnswer,
            finalCitations,
            llmRefused,
            state.currentProvider.get(),
            CacheTier.NONE,
            totalPromptTokens,
            totalCompletionTokens,
            publishContext
        );
    }

    private void persistTurnAndEmitTerminal(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        String rewrittenQuery,
        String finalAnswer,
        List<CitationDto> finalCitations,
        boolean abstained,
        String provider,
        CacheTier cacheTier,
        int totalPromptTokens,
        int totalCompletionTokens,
        CachePublishContext publishContext
    ) {
        if (checkAbortOrTimeout(ctx, state)) {
            return;
        }
        if (!state.claimTerminalForPersistence()) {
            return;
        }

        CacheTier effectiveCacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        long latencyMs = RagChatService.elapsedMillis(ctx.startedAtNanos());
        ChatSessionService.PersistedTurnResult persisted;
        try {
            persisted = ragChatService.persistCompletedTurnOrAuditOnly(
                ctx.turn().chatSessionService(),
                ctx.turn().verifiedActor(),
                ctx.turn().auditDepartmentId(),
                ctx.sessionId(),
                ctx.turn().normalizedQuestion(),
                rewrittenQuery,
                finalAnswer,
                finalCitations,
                abstained,
                provider,
                effectiveCacheTier,
                totalPromptTokens,
                totalCompletionTokens,
                latencyMs,
                ctx.turn().verifiedCaller().ipAddress()
            );
        } catch (ResourceNotFoundException ex) {
            // ChatSessionService.recordCompletedTurn already persisted the 404 FinOps audit before throwing
            sendTerminalErrorAndComplete(
                ctx.emitter(),
                ChatStreamErrorEvent.CODE_RESOURCE_NOT_FOUND,
                "Chat session was deleted before stream completion",
                null,
                false
            );
            return;
        } catch (RuntimeException ex) {
            log.error("Failed to persist completed SSE chat turn", ex);
            ragChatService.recordFailureAuditQuietly(
                ctx.turn().verifiedActor(),
                ctx.turn().auditDepartmentId(),
                ctx.sessionId(),
                ctx.turn().chatSessionService(),
                ctx.turn().verifiedCaller().ipAddress(),
                ctx.turn().normalizedQuestion(),
                totalPromptTokens,
                totalCompletionTokens,
                latencyMs,
                provider,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                effectiveCacheTier
            );
            sendTerminalErrorAndComplete(
                ctx.emitter(),
                ChatStreamErrorEvent.CODE_INTERNAL_ERROR,
                "An unexpected internal error occurred",
                provider,
                false
            );
            return;
        }

        if (publishContext != null
            && publishContext.cacheService() != null
            && effectiveCacheTier == CacheTier.NONE
            && !abstained
            && !finalCitations.isEmpty()) {
            try {
                publishContext.cacheService().publishIfCurrent(
                    ctx.turn().rbacScope(),
                    publishContext.versionBeforeRetrieval(),
                    ctx.turn().normalizedQuestion(),
                    rewrittenQuery,
                    ctx.turn().recentHistory(),
                    publishContext.queryEmbedding(),
                    finalAnswer,
                    finalCitations,
                    false,
                    provider
                );
            } catch (RuntimeException ex) {
                log.warn("Best-effort SSE cache publish failed: {}", ex.getMessage());
            }
        }

        sendTerminalDoneAndComplete(
            ctx.emitter(),
            new ChatStreamDoneEvent(
                persisted != null ? persisted.sessionId() : null,
                persisted != null ? persisted.assistantMessageId() : null,
                abstained,
                provider,
                effectiveCacheTier != CacheTier.NONE,
                effectiveCacheTier,
                ChatTokenUsageDto.of(totalPromptTokens, totalCompletionTokens),
                latencyMs
            )
        );
    }

    private QueryContextualizerService.TokenCounts resolveAnswerTokensWithFallback(
        StreamExecutionState state,
        Prompt prompt,
        String generatedText
    ) {
        int promptTokens = state.latestAnswerPromptTokens.get();
        int completionTokens = state.latestAnswerCompletionTokens.get();
        if (generatedText == null || generatedText.isEmpty()) {
            return new QueryContextualizerService.TokenCounts(promptTokens, completionTokens);
        }
        int resolvedPrompt = promptTokens > 0 ? promptTokens : estimatePromptTokens(prompt);
        int resolvedCompletion = completionTokens > 0
            ? completionTokens
            : OverlappingTokenTextSplitter.countCl100kTokens(generatedText);
        if (promptTokens <= 0 || completionTokens <= 0) {
            log.debug(
                "Provider {} omitted partial/full streaming usage metadata (reported prompt={}, completion={}); "
                    + "resolved via CL100K_BASE (prompt={}, completion={})",
                state.currentProvider.get(),
                promptTokens,
                completionTokens,
                resolvedPrompt,
                resolvedCompletion
            );
        }
        return new QueryContextualizerService.TokenCounts(resolvedPrompt, resolvedCompletion);
    }

    private static int totalPromptTokens(
        StreamExecutionState state,
        QueryContextualizerService.TokenCounts answerTokens
    ) {
        return state.rewritePromptTokens.get()
            + state.priorAttemptPromptTokens.get()
            + answerTokens.promptTokens();
    }

    private static int totalCompletionTokens(
        StreamExecutionState state,
        QueryContextualizerService.TokenCounts answerTokens
    ) {
        return state.rewriteCompletionTokens.get()
            + state.priorAttemptCompletionTokens.get()
            + answerTokens.completionTokens();
    }

    private static int estimatePromptTokens(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return 0;
        }
        int total = 0;
        for (Message message : prompt.getInstructions()) {
            if (message != null && message.getText() != null) {
                total += OverlappingTokenTextSplitter.countCl100kTokens(message.getText());
            }
        }
        return total;
    }

    private boolean checkAbortOrTimeout(StreamExecutionContext ctx, StreamExecutionState state) {
        if (state.timedOut.get() || System.nanoTime() >= ctx.deadlineNanos()) {
            state.cancelUpstream(true);
            handleStreamTimeout(ctx, state);
            return true;
        }
        if (state.cancelled.get() || Thread.currentThread().isInterrupted()) {
            state.cancelUpstream(false);
            handleClientDisconnected(ctx, state);
            return true;
        }
        return false;
    }

    private void handleClientDisconnected(StreamExecutionContext ctx, StreamExecutionState state) {
        if (!state.claimTerminalForPersistence()) {
            return;
        }
        QueryContextualizerService.TokenCounts partialAnswerTokens = resolveAnswerTokensWithFallback(
            state,
            state.activePromptRef.get(),
            state.answerBuffer.toString().trim()
        );
        ragChatService.recordFailureAuditQuietly(
            ctx.turn().verifiedActor(),
            ctx.turn().auditDepartmentId(),
            ctx.sessionId(),
            ctx.turn().chatSessionService(),
            ctx.turn().verifiedCaller().ipAddress(),
            ctx.turn().normalizedQuestion(),
            totalPromptTokens(state, partialAnswerTokens),
            totalCompletionTokens(state, partialAnswerTokens),
            RagChatService.elapsedMillis(ctx.startedAtNanos()),
            state.currentProvider.get(),
            STATUS_CLIENT_CLOSED_REQUEST,
            state.activeCacheTier.get()
        );
        completeQuietly(ctx.emitter());
    }

    private void handleStreamTimeout(StreamExecutionContext ctx, StreamExecutionState state) {
        emitFailureAndClose(
            ctx,
            state,
            ChatStreamErrorEvent.CODE_SSE_STREAM_TIMEOUT,
            "Streaming response timed out before completion",
            state.currentProvider.get(),
            true,
            HttpStatus.GATEWAY_TIMEOUT.value()
        );
    }

    private void emitFailureAndClose(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        String errorCode,
        String safeMessage,
        String provider,
        boolean retryable,
        int auditStatusCode
    ) {
        if (!state.claimTerminalForPersistence()) {
            return;
        }
        QueryContextualizerService.TokenCounts partialAnswerTokens = resolveAnswerTokensWithFallback(
            state,
            state.activePromptRef.get(),
            state.answerBuffer.toString().trim()
        );
        ragChatService.recordFailureAuditQuietly(
            ctx.turn().verifiedActor(),
            ctx.turn().auditDepartmentId(),
            ctx.sessionId(),
            ctx.turn().chatSessionService(),
            ctx.turn().verifiedCaller().ipAddress(),
            ctx.turn().normalizedQuestion(),
            totalPromptTokens(state, partialAnswerTokens),
            totalCompletionTokens(state, partialAnswerTokens),
            RagChatService.elapsedMillis(ctx.startedAtNanos()),
            provider,
            auditStatusCode,
            state.activeCacheTier.get()
        );
        sendTerminalErrorAndComplete(ctx.emitter(), errorCode, safeMessage, provider, retryable);
    }

    private boolean sendEvent(
        StreamExecutionContext ctx,
        StreamExecutionState state,
        String eventName,
        Object payload
    ) {
        if (state.cancelled.get()) {
            return false;
        }
        try {
            ctx.emitter().send(SseEmitter.event()
                .name(eventName)
                .data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException ex) {
            log.debug("Client disconnected while emitting SSE event '{}': {}", eventName, ex.getMessage());
            state.cancelUpstream(false);
            return false;
        }
    }

    private static void sendTerminalDoneAndComplete(SseEmitter emitter, ChatStreamDoneEvent doneEvent) {
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_DONE)
                .data(doneEvent, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Client disconnected before receiving terminal SSE 'done' event: {}", ex.getMessage());
        } finally {
            completeQuietly(emitter);
        }
    }

    private static void sendTerminalErrorAndComplete(
        SseEmitter emitter,
        String errorCode,
        String message,
        String provider,
        boolean retryable
    ) {
        try {
            emitter.send(SseEmitter.event()
                .name(EVENT_ERROR)
                .data(new ChatStreamErrorEvent(errorCode, message, provider, retryable), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            log.debug("Client disconnected before receiving terminal SSE 'error' event: {}", ex.getMessage());
        } finally {
            completeQuietly(emitter);
        }
    }

    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ex) {
            log.debug("SSE emitter already completed: {}", ex.getMessage());
        }
    }

    private static void updateLatestUsage(ChatResponse chunk, StreamExecutionState state) {
        QueryContextualizerService.TokenCounts counts = QueryContextualizerService.extractTokenCounts(chunk);
        if (counts.promptTokens() > 0) {
            state.latestAnswerPromptTokens.set(counts.promptTokens());
        }
        if (counts.completionTokens() > 0) {
            state.latestAnswerCompletionTokens.set(counts.completionTokens());
        }
    }

    private static String extractDeltaText(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        return chunk.getResult().getOutput().getText();
    }

    private static void runWithSecurityContext(SecurityContext snapshot, Runnable action) {
        SecurityContextHolder.setContext(snapshot);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static SecurityContext snapshotSecurityContext() {
        SecurityContext copy = SecurityContextHolder.createEmptyContext();
        SecurityContext current = SecurityContextHolder.getContext();
        if (current != null && current.getAuthentication() != null) {
            copy.setAuthentication(current.getAuthentication());
        }
        return copy;
    }

    private record CachePublishContext(
        RagCacheService cacheService,
        long versionBeforeRetrieval,
        float[] queryEmbedding
    ) {
    }

    private record StreamExecutionContext(
        long startedAtNanos,
        long deadlineNanos,
        RagChatService.PreparedChatTurn turn,
        UUID sessionId,
        SecurityContext securityContextSnapshot,
        String requestBaseUrl,
        SseEmitter emitter,
        RagCacheService.L1LookupResult l1Outcome
    ) {
    }

    private static final class StreamExecutionState {
        private final Object cancelLock = new Object();
        private Thread workerThread;

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final AtomicBoolean terminalHandled = new AtomicBoolean(false);
        final AtomicBoolean anyTokenEmitted = new AtomicBoolean(false);
        final AtomicInteger rewritePromptTokens = new AtomicInteger(0);
        final AtomicInteger rewriteCompletionTokens = new AtomicInteger(0);
        final AtomicInteger priorAttemptPromptTokens = new AtomicInteger(0);
        final AtomicInteger priorAttemptCompletionTokens = new AtomicInteger(0);
        final AtomicInteger latestAnswerPromptTokens = new AtomicInteger(0);
        final AtomicInteger latestAnswerCompletionTokens = new AtomicInteger(0);
        final AtomicReference<CacheTier> activeCacheTier = new AtomicReference<>(CacheTier.NONE);
        final AtomicReference<String> currentProvider = new AtomicReference<>(null);
        final AtomicReference<Prompt> activePromptRef = new AtomicReference<>(null);
        final AtomicReference<Disposable> activeSubscription = new AtomicReference<>(null);
        final AtomicReference<BlockingQueue<StreamSignal>> activeQueue = new AtomicReference<>(null);
        final AtomicReference<ScheduledFuture<?>> timeoutFuture = new AtomicReference<>(null);
        final StringBuilder leadingWhitespaceBuffer = new StringBuilder(32);
        final StringBuilder answerBuffer = new StringBuilder(512);

        void registerWorker(Thread worker) {
            synchronized (cancelLock) {
                if (!cancelled.get() && !terminalHandled.get()) {
                    this.workerThread = worker;
                }
            }
        }

        void resetAnswerAttempt() {
            priorAttemptPromptTokens.addAndGet(latestAnswerPromptTokens.getAndSet(0));
            priorAttemptCompletionTokens.addAndGet(latestAnswerCompletionTokens.getAndSet(0));
            leadingWhitespaceBuffer.setLength(0);
            answerBuffer.setLength(0);
        }

        boolean claimTerminalForPersistence() {
            synchronized (cancelLock) {
                if (!terminalHandled.compareAndSet(false, true)) {
                    return false;
                }
                this.workerThread = null;
            }
            cancelTimeoutTask();
            disposeActiveSubscription();
            // Clear any pending interrupt flag before HikariCP/PostgreSQL JDBC persistence runs on this virtual thread
            Thread.interrupted();
            return true;
        }

        void cancelUpstream(boolean isTimeout) {
            if (isTimeout) {
                timedOut.set(true);
            }
            cancelled.set(true);
            cancelTimeoutTask();
            disposeActiveSubscription();
            BlockingQueue<StreamSignal> queue = activeQueue.get();
            if (queue != null) {
                queue.offer(StreamSignal.cancelled());
            }
            synchronized (cancelLock) {
                if (!terminalHandled.get() && workerThread != null && workerThread != Thread.currentThread()) {
                    workerThread.interrupt();
                }
            }
        }

        void detachWorkerAndCleanup() {
            synchronized (cancelLock) {
                this.workerThread = null;
            }
            cancelTimeoutTask();
            disposeActiveSubscription();
        }

        void cancelTimeoutTask() {
            ScheduledFuture<?> future = timeoutFuture.getAndSet(null);
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }

        void disposeActiveSubscription() {
            Disposable disposable = activeSubscription.getAndSet(null);
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
    }

    private enum SignalKind {
        NEXT,
        COMPLETE,
        ERROR,
        CANCELLED
    }

    private record StreamSignal(SignalKind kind, ChatResponse chunk, Throwable error) {
        static StreamSignal next(ChatResponse chunk) {
            return new StreamSignal(SignalKind.NEXT, chunk, null);
        }

        static StreamSignal complete() {
            return new StreamSignal(SignalKind.COMPLETE, null, null);
        }

        static StreamSignal error(Throwable error) {
            return new StreamSignal(SignalKind.ERROR, null, error);
        }

        static StreamSignal cancelled() {
            return new StreamSignal(SignalKind.CANCELLED, null, null);
        }
    }

    private enum ProviderStreamStatus {
        COMPLETED,
        PROVIDER_ERROR,
        CLIENT_DISCONNECTED,
        TIMED_OUT
    }

    private record ProviderStreamOutcome(ProviderStreamStatus status, Throwable error) {
        static ProviderStreamOutcome completed() {
            return new ProviderStreamOutcome(ProviderStreamStatus.COMPLETED, null);
        }

        static ProviderStreamOutcome error(Throwable error) {
            return new ProviderStreamOutcome(ProviderStreamStatus.PROVIDER_ERROR, error);
        }

        static ProviderStreamOutcome clientDisconnected() {
            return new ProviderStreamOutcome(ProviderStreamStatus.CLIENT_DISCONNECTED, null);
        }

        static ProviderStreamOutcome timedOut() {
            return new ProviderStreamOutcome(ProviderStreamStatus.TIMED_OUT, null);
        }
    }
}
