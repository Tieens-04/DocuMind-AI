package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.chat.ChatStreamCitationsEvent;
import com.documind.controller.dto.chat.ChatStreamDoneEvent;
import com.documind.controller.dto.chat.ChatStreamErrorEvent;
import com.documind.controller.dto.chat.ChatStreamTokenEvent;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.enums.Role;
import com.documind.exception.DocumentAccessDeniedException;
import com.documind.exception.InvalidChatRequestException;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagChatStreamServiceTest {

    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private VectorRetrievalService vectorRetrievalService;
    private GroundedSystemPromptService groundedSystemPromptService;
    private RbacVectorFilterFactory rbacVectorFilterFactory;
    private ChatModel groqChatModel;
    private ChatModel geminiChatModel;
    private ChatClient groqChatClient;
    private ChatClient geminiChatClient;
    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private AuditLogRepository auditLogRepository;
    private TransactionTemplate transactionTemplate;
    private ChatSessionService chatSessionService;
    private QueryContextualizerService queryContextualizerService;
    private MockEnvironment environment;
    private RagProperties ragProperties;
    private AiProperties aiProperties;
    private VirtualThreadTaskExecutor sseExecutor;
    private RagChatService ragChatService;
    private final List<RagChatStreamService> createdServices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        environment = new MockEnvironment();
        vectorRetrievalService = mock(VectorRetrievalService.class);
        groundedSystemPromptService = mock(GroundedSystemPromptService.class);
        rbacVectorFilterFactory = new RbacVectorFilterFactory(environment);
        groqChatModel = mock(ChatModel.class);
        geminiChatModel = mock(ChatModel.class);
        groqChatClient = ChatClient.create(groqChatModel);
        geminiChatClient = ChatClient.create(geminiChatModel);
        clearInvocations(groqChatModel, geminiChatModel);
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        chatSessionService = mock(ChatSessionService.class);
        queryContextualizerService = mock(QueryContextualizerService.class);
        ragProperties = RagProperties.defaults();
        aiProperties = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "/chat/completions",
            "gemini-live-test-key",
            "gemini-2.5-flash",
            0.1d
        ));
        sseExecutor = new VirtualThreadTaskExecutor("test-sse-vt-");
        transactionTemplate = new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        });

        when(groundedSystemPromptService.buildSystemMessage(any(), any()))
            .thenReturn(new SystemMessage("Grounded system prompt <context>policy</context>"));

        ragChatService = new RagChatService(
            vectorRetrievalService,
            groundedSystemPromptService,
            rbacVectorFilterFactory,
            groqChatClient,
            providerOf(geminiChatClient),
            providerOf(userRepository),
            providerOf(departmentRepository),
            providerOf(auditLogRepository),
            providerOf(transactionTemplate),
            providerOf(chatSessionService),
            providerOf(queryContextualizerService),
            aiProperties,
            ragProperties,
            environment
        );
    }

    @AfterEach
    void tearDown() {
        for (RagChatStreamService service : createdServices) {
            service.shutdownTimeoutScheduler();
        }
        createdServices.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void streamEmitsCitationsTokensPreservingWhitespaceAndNewlinesAndDoneWithFinalChunkUsage() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        RetrievedChunk hit = sampleChunk();
        when(vectorRetrievalService.retrieve(eq("Chính sách nghỉ phép năm?"), any(RbacVectorScope.class))).thenReturn(List.of(hit));

        UUID sessionId = UUID.randomUUID();
        UUID assistantMsgId = UUID.randomUUID();
        AtomicReference<String> persistedPrincipalName = new AtomicReference<>();
        when(chatSessionService.recordCompletedTurn(any())).thenAnswer(invocation -> {
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                persistedPrincipalName.set(SecurityContextHolder.getContext().getAuthentication().getName());
            }
            return new ChatSessionService.PersistedTurnResult(sessionId, assistantMsgId);
        });

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Theo "),
            textChunk("tài liệu\n\n"),
            textChunk(" "),
            textChunk("- 12 ngày phép [Nguồn: hr-policy.pdf, trang 3]."),
            usageOnlyChunk(140, 32)
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        SseEmitter returned = service.openStream("Chính sách nghỉ phép năm?", null, null, caller);
        // Simulate HTTP request thread finishing and clearing its SecurityContext immediately
        SecurityContextHolder.clearContext();

        assertThat(returned).isSameAs(emitter);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        List<RecordedEvent> events = emitter.events();
        assertThat(events).hasSize(6);
        assertThat(events.get(0).name()).isEqualTo("citations");
        ChatStreamCitationsEvent citationsEvent = (ChatStreamCitationsEvent) events.get(0).payload();
        assertThat(citationsEvent.citations()).containsExactly(hit.citation());
        assertThat(citationsEvent.rewrittenQuery()).isNull();

        assertThat(events.get(1).name()).isEqualTo("token");
        assertThat(((ChatStreamTokenEvent) events.get(1).payload()).delta()).isEqualTo("Theo ");
        assertThat(((ChatStreamTokenEvent) events.get(2).payload()).delta()).isEqualTo("tài liệu\n\n");
        assertThat(((ChatStreamTokenEvent) events.get(3).payload()).delta()).isEqualTo(" ");
        assertThat(((ChatStreamTokenEvent) events.get(4).payload()).delta())
            .isEqualTo("- 12 ngày phép [Nguồn: hr-policy.pdf, trang 3].");

        assertThat(events.get(5).name()).isEqualTo("done");
        ChatStreamDoneEvent done = (ChatStreamDoneEvent) events.get(5).payload();
        assertThat(done.sessionId()).isEqualTo(sessionId);
        assertThat(done.messageId()).isEqualTo(assistantMsgId);
        assertThat(done.abstained()).isFalse();
        assertThat(done.modelProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(done.cacheHit()).isFalse();
        assertThat(done.cacheTier()).isEqualTo(CacheTier.NONE);
        assertThat(done.tokenUsage().promptTokens()).isEqualTo(140);
        assertThat(done.tokenUsage().completionTokens()).isEqualTo(32);
        assertThat(done.tokenUsage().totalTokens()).isEqualTo(172);
        assertThat(done.latencyMs()).isGreaterThanOrEqualTo(0L);

        // Turn 1 must skip query contextualizer
        verifyNoInteractions(queryContextualizerService);
        assertThat(persistedPrincipalName.get()).isEqualTo("user@documind.ai");

        ArgumentCaptor<ChatSessionService.CompletedTurnCommand> cmdCaptor =
            ArgumentCaptor.forClass(ChatSessionService.CompletedTurnCommand.class);
        verify(chatSessionService).recordCompletedTurn(cmdCaptor.capture());
        ChatSessionService.CompletedTurnCommand saved = cmdCaptor.getValue();
        assertThat(saved.assistantAnswer()).isEqualTo("Theo tài liệu\n\n - 12 ngày phép [Nguồn: hr-policy.pdf, trang 3].");
        assertThat(saved.citations()).containsExactly(hit.citation());
        assertThat(saved.promptTokens()).isEqualTo(140);
        assertThat(saved.completionTokens()).isEqualTo(32);
        assertThat(saved.abstained()).isFalse();
    }

    @Test
    void streamTurn2ContextualizesFollowUpAndSumsRewriteAndAnswerTokens() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        UUID existingSessionId = UUID.randomUUID();
        UUID assistantMsgId = UUID.randomUUID();
        ChatMessageEntity prevUser = historyMessage(MessageRole.USER, "Nghỉ phép năm được mấy ngày?", false);
        ChatMessageEntity prevAssistant = historyMessage(MessageRole.ASSISTANT, "12 ngày phép năm.", false);
        when(chatSessionService.loadAuthorizedHistoryForTurn(eq(existingSessionId), eq(HR_USER_ID), any()))
            .thenReturn(List.of(prevUser, prevAssistant));

        String rewritten = "Nhân viên làm đủ 5 năm thâm niên được cộng thêm mấy ngày nghỉ phép năm?";
        when(queryContextualizerService.contextualize(eq("Còn làm đủ 5 năm thì sao?"), any()))
            .thenReturn(new QueryContextualizerService.ContextualizedQuery(
                rewritten, rewritten, 45, 15, RagConstants.PROVIDER_GROQ
            ));

        RetrievedChunk hit = sampleChunk();
        when(vectorRetrievalService.retrieve(eq(rewritten), any(RbacVectorScope.class))).thenReturn(List.of(hit));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(existingSessionId, assistantMsgId));

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Được cộng thêm 1 ngày phép."),
            usageOnlyChunk(110, 20)
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Còn làm đủ 5 năm thì sao?", existingSessionId, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ChatStreamCitationsEvent citations = (ChatStreamCitationsEvent) emitter.events().get(0).payload();
        assertThat(citations.rewrittenQuery()).isEqualTo(rewritten);

        ChatStreamDoneEvent done = (ChatStreamDoneEvent) emitter.events().getLast().payload();
        assertThat(done.tokenUsage().promptTokens()).isEqualTo(155);
        assertThat(done.tokenUsage().completionTokens()).isEqualTo(35);
        assertThat(done.tokenUsage().totalTokens()).isEqualTo(190);
    }

    @Test
    void streamShortCircuitsDeterministicRefusalWhenNoChunksRetrievedWithZeroAnswerLlmCalls() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of());

        UUID sessionId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(sessionId, msgId));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Giá Bitcoin hôm nay?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(groqChatModel, geminiChatModel);

        List<RecordedEvent> events = emitter.events();
        assertThat(events).hasSize(3);
        assertThat(events.get(0).name()).isEqualTo("citations");
        assertThat(((ChatStreamCitationsEvent) events.get(0).payload()).citations()).isEmpty();
        assertThat(events.get(1).name()).isEqualTo("token");
        assertThat(((ChatStreamTokenEvent) events.get(1).payload()).delta())
            .isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(events.get(2).name()).isEqualTo("done");
        ChatStreamDoneEvent done = (ChatStreamDoneEvent) events.get(2).payload();
        assertThat(done.abstained()).isTrue();
        assertThat(done.tokenUsage().totalTokens()).isZero();
    }

    @Test
    void streamClearsPersistedCitationsAndMarksAbstainedOnLlmSoftRefusal() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Không tìm thấy thông tin "),
            textChunk("trong tài liệu nội bộ."),
            usageOnlyChunk(100, 12)
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi gần giống nhưng không có đáp án", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ChatStreamDoneEvent done = (ChatStreamDoneEvent) emitter.events().getLast().payload();
        assertThat(done.abstained()).isTrue();

        ArgumentCaptor<ChatSessionService.CompletedTurnCommand> cmdCaptor =
            ArgumentCaptor.forClass(ChatSessionService.CompletedTurnCommand.class);
        verify(chatSessionService).recordCompletedTurn(cmdCaptor.capture());
        assertThat(cmdCaptor.getValue().abstained()).isTrue();
        assertThat(cmdCaptor.getValue().citations()).isEmpty();
        assertThat(cmdCaptor.getValue().assistantAnswer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
    }

    @Test
    void streamFallsBackToCl100kTokenCountingWhenProviderOmitsUsage() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Nhân viên chính thức "),
            textChunk("được hưởng 12 ngày phép năm.")
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Nghỉ phép năm bao nhiêu ngày?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ChatStreamDoneEvent done = (ChatStreamDoneEvent) emitter.events().getLast().payload();
        assertThat(done.tokenUsage().promptTokens()).isPositive();
        assertThat(done.tokenUsage().completionTokens()).isPositive();
        assertThat(done.tokenUsage().totalTokens())
            .isEqualTo(done.tokenUsage().promptTokens() + done.tokenUsage().completionTokens());
    }

    @Test
    void streamFailsOverFromGroqToGeminiWhenGroqErrorsBeforeFirstToken() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));

        when(groqChatModel.stream(any(Prompt.class)))
            .thenReturn(Flux.error(new RuntimeException("Groq 503 connection refused")));
        when(geminiChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Câu trả lời từ Gemini fallback."),
            usageOnlyChunk(95, 18)
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Chính sách thử việc?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        List<RecordedEvent> events = emitter.events();
        assertThat(events.stream().filter(e -> e.name().equals("citations")).count()).isEqualTo(1);
        assertThat(events.stream().filter(e -> e.name().equals("error")).count()).isZero();
        ChatStreamDoneEvent done = (ChatStreamDoneEvent) events.getLast().payload();
        assertThat(done.modelProvider()).isEqualTo(RagConstants.PROVIDER_GEMINI);
        assertThat(done.tokenUsage().totalTokens()).isEqualTo(113);
    }

    @Test
    void streamEmitsErrorWithoutCallingGeminiWhenGroqFailsMidStreamAfterEmittingTokens() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
            Flux.just(textChunk("Theo quy định "), textChunk("nhân sự, ")),
            Flux.error(new RuntimeException("TCP reset mid-stream"))
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Chính sách thử việc?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(geminiChatModel);
        verify(chatSessionService, never()).recordCompletedTurn(any());
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Chính sách thử việc?"),
            anyInt(), anyInt(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(502), eq("127.0.0.1")
        );

        List<RecordedEvent> events = emitter.events();
        assertThat(events.getLast().name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) events.getLast().payload();
        assertThat(error.errorCode()).isEqualTo("LLM_STREAM_INTERRUPTED");
        assertThat(error.provider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void streamEmitsErrorAndRecords502AuditWhenEmbeddingRetrievalFails() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class)))
            .thenThrow(new TransientAiException("Ollama connection timeout"));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Chính sách thử việc?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        verifyNoInteractions(groqChatModel, geminiChatModel);
        verify(chatSessionService, never()).recordCompletedTurn(any());
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Chính sách thử việc?"),
            eq(0), eq(0), anyLong(), eq(RagConstants.PROVIDER_OLLAMA), eq(502), eq("127.0.0.1")
        );

        assertThat(emitter.events()).hasSize(1);
        assertThat(emitter.events().get(0).name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) emitter.events().get(0).payload();
        assertThat(error.errorCode()).isEqualTo("LLM_PROVIDER_UNAVAILABLE");
        assertThat(error.provider()).isEqualTo(RagConstants.PROVIDER_OLLAMA);
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void streamCancelsUpstreamFluxSkipsChatMessagesAndLogsSingle499AuditOnClientDisconnect() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        CountDownLatch cancelLatch = new CountDownLatch(1);
        Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        Flux<ChatResponse> controlledFlux = sink.asFlux()
            .doOnCancel(() -> {
                upstreamCancelled.set(true);
                cancelLatch.countDown();
            });
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(controlledFlux);

        CountDownLatch auditRecordedLatch = new CountDownLatch(1);
        when(chatSessionService.recordCompletedTurn(any())).thenThrow(new AssertionError("Must not persist partial turn"));
        org.mockito.Mockito.doAnswer(inv -> {
            auditRecordedLatch.countDown();
            return null;
        }).when(chatSessionService).recordFailureAudit(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyInt(), any()
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        emitter.failOnSendNumber(3, new IOException("Broken pipe")); // 1=citations, 2=token1, 3=token2 fails
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Chính sách thử việc?", null, null, caller);

        sink.tryEmitNext(textChunk("Phần 1 "));
        sink.tryEmitNext(textChunk("Phần 2 sẽ kích hoạt disconnect"));

        assertThat(cancelLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(upstreamCancelled.get()).isTrue();

        // Simulate Tomcat firing concurrent onError + onCompletion callbacks after IOException
        emitter.triggerError(new IOException("ClientAbortException"));
        emitter.triggerCompletion();

        assertThat(auditRecordedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        verify(chatSessionService, never()).recordCompletedTurn(any());
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Chính sách thử việc?"),
            anyInt(), anyInt(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(499), eq("127.0.0.1")
        );
    }

    @Test
    void streamEmitsTimeoutErrorAndLogs504AuditWhenStreamExceedsConfiguredTimeout() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        AtomicBoolean upstreamCancelled = new AtomicBoolean(false);
        when(groqChatModel.stream(any(Prompt.class)))
            .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> upstreamCancelled.set(true)));

        RagProperties shortTimeoutProps = new RagProperties(
            RagProperties.RetrievalProperties.defaults(),
            RagProperties.LlmProperties.defaults(),
            RagProperties.MemoryProperties.defaults(),
            new RagProperties.SseProperties(Duration.ofMillis(120))
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(shortTimeoutProps, timeout -> emitter);

        service.openStream("Câu hỏi bị treo LLM", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        assertThat(upstreamCancelled.get()).isTrue();
        verify(chatSessionService, never()).recordCompletedTurn(any());
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi bị treo LLM"),
            anyInt(), anyInt(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(504), eq("127.0.0.1")
        );

        assertThat(emitter.events().getLast().name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) emitter.events().getLast().payload();
        assertThat(error.errorCode()).isEqualTo("SSE_STREAM_TIMEOUT");
        assertThat(error.retryable()).isTrue();
    }

    @Test
    void streamEmitsResourceNotFoundErrorWithoutDoubleAuditWhenSessionDeletedConcurrently() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        UUID sessionId = UUID.randomUUID();
        when(chatSessionService.loadAuthorizedHistoryForTurn(eq(sessionId), eq(HR_USER_ID), any()))
            .thenReturn(List.of());
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Nội dung hợp lệ."),
            usageOnlyChunk(80, 10)
        ));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenThrow(new ResourceNotFoundException("Chat session not found: " + sessionId));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi trong phiên vừa bị xóa", sessionId, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        // ChatSessionService.recordCompletedTurn already saved the 404 FinOps audit before throwing
        verify(chatSessionService, never()).recordFailureAudit(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyInt(), any()
        );

        assertThat(emitter.events().getLast().name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) emitter.events().getLast().payload();
        assertThat(error.errorCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(error.retryable()).isFalse();
    }

    @Test
    void openStreamFailsSynchronouslyBeforeOpeningEmitterOnPreFlightErrors() {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        AtomicBoolean emitterCreated = new AtomicBoolean(false);
        RagChatStreamService service = buildService(ragProperties, timeout -> {
            emitterCreated.set(true);
            return new RecordingSseEmitter(timeout);
        });

        // 403 non-admin passing departmentId
        assertThatThrownBy(() -> service.openStream("Câu hỏi", null, IT_DEPT_ID, caller))
            .isInstanceOf(DocumentAccessDeniedException.class);

        // 400 oversized question
        assertThatThrownBy(() -> service.openStream("a".repeat(4001), null, null, caller))
            .isInstanceOf(InvalidChatRequestException.class);

        // 404 unowned session
        UUID otherSession = UUID.randomUUID();
        when(chatSessionService.loadAuthorizedHistoryForTurn(eq(otherSession), eq(HR_USER_ID), any()))
            .thenThrow(new ResourceNotFoundException("Chat session not found: " + otherSession));
        assertThatThrownBy(() -> service.openStream("Câu hỏi hợp lệ", otherSession, null, caller))
            .isInstanceOf(ResourceNotFoundException.class);

        assertThat(emitterCreated.get()).isFalse();
    }

    @Test
    void openStreamMethodHasPreAuthorizeAnnotation() throws NoSuchMethodException {
        Method method = RagChatStreamService.class.getMethod(
            "openStream", String.class, UUID.class, UUID.class, CallerContext.class
        );
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value())
            .isEqualTo("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')");
    }

    @Test
    void streamEmitsInternalErrorLogs500AuditAndCompletesEmitterWhenPersistenceThrowsRuntimeException() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Nội dung câu trả lời."),
            usageOnlyChunk(90, 15)
        ));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenThrow(new RuntimeException("HikariPool-1 - Connection is not available, request timed out after 5000ms"));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi khi DB quá tải", null, null, caller);
        assertThat(emitter.awaitCompleted(3, TimeUnit.SECONDS)).isTrue();

        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi khi DB quá tải"),
            eq(90), eq(15), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(500), eq("127.0.0.1")
        );
        assertThat(emitter.events().getLast().name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) emitter.events().getLast().payload();
        assertThat(error.errorCode()).isEqualTo(ChatStreamErrorEvent.CODE_INTERNAL_ERROR);
        assertThat(error.message()).doesNotContain("HikariPool");
        assertThat(error.retryable()).isFalse();
    }

    @Test
    void streamClearsVirtualThreadInterruptFlagBeforeRecording499AuditWhenCancelledFromContainerThread() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(groqChatModel.stream(any(Prompt.class)))
            .thenReturn(sink.asFlux().doOnSubscribe(s -> subscribedLatch.countDown()));

        AtomicBoolean interruptedInsideJdbc = new AtomicBoolean(true);
        CountDownLatch auditLatch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            interruptedInsideJdbc.set(Thread.currentThread().isInterrupted());
            auditLatch.countDown();
            return null;
        }).when(chatSessionService).recordFailureAudit(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyInt(), any()
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi bị hủy từ Tomcat thread", null, null, caller);
        assertThat(subscribedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Trigger container disconnect from a separate thread so cancelUpstream calls worker.interrupt()
        Thread containerThread = new Thread(() -> emitter.triggerError(new IOException("ClientAbortException")));
        containerThread.start();
        containerThread.join(3_000L);

        assertThat(auditLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interruptedInsideJdbc.get()).isFalse();
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi bị hủy từ Tomcat thread"),
            anyInt(), anyInt(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(499), eq("127.0.0.1")
        );
    }

    @Test
    void streamRecords499InsteadOf502AndSkipsGeminiWhenDisconnectedDuringVectorRetrieval() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        CountDownLatch enteredRetrieve = new CountDownLatch(1);
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenAnswer(inv -> {
            enteredRetrieve.countDown();
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RestClientException("Ollama HTTP interrupted", new InterruptedIOException("interrupted"));
            }
            return List.of(sampleChunk());
        });

        CountDownLatch auditLatch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            auditLatch.countDown();
            return null;
        }).when(chatSessionService).recordFailureAudit(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyInt(), any()
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi hủy lúc retrieve", null, null, caller);
        assertThat(enteredRetrieve.await(5, TimeUnit.SECONDS)).isTrue();

        Thread containerThread = new Thread(() -> emitter.triggerError(new IOException("ClientAbortException")));
        containerThread.start();
        containerThread.join(3_000L);

        assertThat(auditLatch.await(5, TimeUnit.SECONDS)).isTrue();
        verifyNoInteractions(groqChatModel, geminiChatModel);
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi hủy lúc retrieve"),
            eq(0), eq(0), anyLong(), eq(null), eq(499), eq("127.0.0.1")
        );
        assertThat(emitter.events()).isEmpty();
    }

    @Test
    void streamTimesOutProactivelyDuringBlockingRetrievalAndEmits504() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));

        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenAnswer(inv -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RestClientException("Interrupted by proactive SSE timeout", new InterruptedIOException("timeout"));
            }
            return List.of(sampleChunk());
        });

        RagProperties shortTimeoutProps = new RagProperties(
            RagProperties.RetrievalProperties.defaults(),
            RagProperties.LlmProperties.defaults(),
            RagProperties.MemoryProperties.defaults(),
            new RagProperties.SseProperties(Duration.ofMillis(120))
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(shortTimeoutProps, timeout -> emitter);

        service.openStream("Câu hỏi treo Ollama", null, null, caller);
        assertThat(emitter.awaitCompleted(2, TimeUnit.SECONDS)).isTrue();

        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi treo Ollama"),
            eq(0), eq(0), anyLong(), eq(null), eq(504), eq("127.0.0.1")
        );
        assertThat(emitter.events().getLast().name()).isEqualTo("error");
        ChatStreamErrorEvent error = (ChatStreamErrorEvent) emitter.events().getLast().payload();
        assertThat(error.errorCode()).isEqualTo(ChatStreamErrorEvent.CODE_SSE_STREAM_TIMEOUT);
    }

    @Test
    void streamFailsOverToGeminiWhenGroqEmitsOnlyLeadingWhitespaceBeforeBlankCompletionAndPreservesPrimaryUsage() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));

        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("\n"),
            textChunk("   "),
            usageOnlyChunk(60, 2)
        ));
        when(geminiChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            textChunk("Câu trả lời từ Gemini."),
            usageOnlyChunk(95, 18)
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Chính sách thử việc?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        List<RecordedEvent> events = emitter.events();
        assertThat(events.stream().filter(e -> e.name().equals("error")).count()).isZero();
        List<String> tokens = events.stream()
            .filter(e -> e.name().equals("token"))
            .map(e -> ((ChatStreamTokenEvent) e.payload()).delta())
            .toList();
        assertThat(tokens).containsExactly("Câu trả lời từ Gemini.");

        ChatStreamDoneEvent done = (ChatStreamDoneEvent) events.getLast().payload();
        assertThat(done.modelProvider()).isEqualTo(RagConstants.PROVIDER_GEMINI);
        assertThat(done.tokenUsage().promptTokens()).isEqualTo(155);
        assertThat(done.tokenUsage().completionTokens()).isEqualTo(20);
        assertThat(done.tokenUsage().totalTokens()).isEqualTo(175);
    }

    @Test
    void streamEstimatesMissingCompletionTokensWhenProviderOnlyReportsPromptTokensBeforeInterrupt() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        ChatResponseMetadata promptOnlyMeta = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(110, 0, 110))
            .build();
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.concat(
            Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("Nhân viên chính thức được hưởng 12 ngày phép năm."))),
                promptOnlyMeta
            )),
            Flux.error(new RuntimeException("Mid-stream reset"))
        ));

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Nghỉ phép năm?", null, null, caller);
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ArgumentCaptor<Integer> promptCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> completionCap = ArgumentCaptor.forClass(Integer.class);
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Nghỉ phép năm?"),
            promptCap.capture(), completionCap.capture(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(502), eq("127.0.0.1")
        );
        assertThat(promptCap.getValue()).isEqualTo(110);
        assertThat(completionCap.getValue()).isPositive();
    }

    @Test
    void streamAppliesBoundedBackpressureAgainstSlowReaderInsteadOfDrainingUnboundedFlux() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));

        int totalChunks = 1_000;
        AtomicInteger emittedByModel = new AtomicInteger(0);
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(
            Flux.range(0, totalChunks)
                .map(i -> {
                    emittedByModel.incrementAndGet();
                    return i < totalChunks - 1 ? textChunk("w") : usageOnlyChunk(50, totalChunks - 1);
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.parallel())
        );

        CountDownLatch slowClientEntered = new CountDownLatch(1);
        CountDownLatch slowClientRelease = new CountDownLatch(1);
        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        emitter.blockOnSendNumber(2, slowClientEntered, slowClientRelease); // 1=citations, 2=first token stalls

        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);
        service.openStream("Kiểm tra backpressure slow reader", null, null, caller);

        assertThat(slowClientEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150L);
        // Backpressure bounded by DefaultChatClient flatMap prefetch (256) + BaseSubscriber window (64): never drains all 1,000 chunks
        assertThat(emittedByModel.get()).isLessThan(400);

        slowClientRelease.countDown();
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emittedByModel.get()).isEqualTo(totalChunks);
        assertThat(emitter.events().getLast().name()).isEqualTo("done");
    }

    @Test
    void streamHandlesContainerTimeoutCallbackWithPropagatedSecurityContextAndSingle504Audit() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        CountDownLatch subscribedLatch = new CountDownLatch(1);
        Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        when(groqChatModel.stream(any(Prompt.class)))
            .thenReturn(sink.asFlux().doOnSubscribe(s -> subscribedLatch.countDown()));

        AtomicReference<String> auditPrincipal = new AtomicReference<>();
        CountDownLatch auditLatch = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                auditPrincipal.set(SecurityContextHolder.getContext().getAuthentication().getName());
            }
            auditLatch.countDown();
            return null;
        }).when(chatSessionService).recordFailureAudit(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyInt(), any()
        );

        RecordingSseEmitter emitter = new RecordingSseEmitter(10_000L);
        RagChatStreamService service = buildService(ragProperties, timeout -> emitter);

        service.openStream("Câu hỏi container timeout", null, null, caller);
        SecurityContextHolder.clearContext();
        assertThat(subscribedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Simulate Tomcat AsyncContext timeout callback on a thread with empty SecurityContext
        Thread tomcatTimeoutThread = new Thread(emitter::triggerTimeout);
        tomcatTimeoutThread.start();
        tomcatTimeoutThread.join(3_000L);

        assertThat(auditLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();
        assertThat(auditPrincipal.get()).isEqualTo("user@documind.ai");
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Câu hỏi container timeout"),
            anyInt(), anyInt(), anyLong(), eq(RagConstants.PROVIDER_GROQ), eq(504), eq("127.0.0.1")
        );
    }

    @Test
    void streamEmitsProviderUnavailableWhenBothGroqAndGeminiFailAndDoesNotDoubleCountBufferedUsage() throws Exception {
        CallerContext caller = authenticateUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        UserEntity actor = activeUser(HR_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE);
        when(userRepository.findById(HR_USER_ID)).thenReturn(Optional.of(actor));
        when(vectorRetrievalService.retrieve(any(), any(RbacVectorScope.class))).thenReturn(List.of(sampleChunk()));

        // Case 1: Both Groq and Gemini fail before first token -> groq+gemini LLM_PROVIDER_UNAVAILABLE (502)
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("Groq 503")));
        when(geminiChatModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("Gemini 503")));

        RecordingSseEmitter emitter1 = new RecordingSseEmitter(10_000L);
        RagChatStreamService service1 = buildService(ragProperties, timeout -> emitter1);
        service1.openStream("Cả hai provider đều lỗi?", null, null, caller);
        assertThat(emitter1.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ChatStreamErrorEvent dualErr = (ChatStreamErrorEvent) emitter1.events().getLast().payload();
        assertThat(dualErr.errorCode()).isEqualTo(ChatStreamErrorEvent.CODE_LLM_PROVIDER_UNAVAILABLE);
        assertThat(dualErr.provider()).isEqualTo(RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER);
        verify(chatSessionService, times(1)).recordFailureAudit(
            eq(actor), eq(HR_DEPT_ID), eq(null), eq("Cả hai provider đều lỗi?"),
            eq(0), eq(0), anyLong(), eq(RagConstants.PROVIDER_GROQ_GEMINI_FAILOVER), eq(502), eq("127.0.0.1")
        );

        // Case 2: Groq returns empty Flux -> Gemini succeeds with Spring AI 1.0.3 duplicate buffered usage chunks
        when(chatSessionService.recordCompletedTurn(any()))
            .thenReturn(new ChatSessionService.PersistedTurnResult(UUID.randomUUID(), UUID.randomUUID()));
        when(groqChatModel.stream(any(Prompt.class))).thenReturn(Flux.empty());
        ChatResponseMetadata dupUsage = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(95, 18, 113))
            .build();
        when(geminiChatModel.stream(any(Prompt.class))).thenReturn(Flux.just(
            new ChatResponse(List.of(new Generation(new AssistantMessage("Đáp án Gemini."))), dupUsage),
            new ChatResponse(List.of(), dupUsage)
        ));

        RecordingSseEmitter emitter2 = new RecordingSseEmitter(10_000L);
        RagChatStreamService service2 = buildService(ragProperties, timeout -> emitter2);
        service2.openStream("Groq trả rỗng?", null, null, caller);
        assertThat(emitter2.awaitCompleted(5, TimeUnit.SECONDS)).isTrue();

        ChatStreamDoneEvent done = (ChatStreamDoneEvent) emitter2.events().getLast().payload();
        assertThat(done.modelProvider()).isEqualTo(RagConstants.PROVIDER_GEMINI);
        assertThat(done.tokenUsage().promptTokens()).isEqualTo(95);
        assertThat(done.tokenUsage().completionTokens()).isEqualTo(18);
        assertThat(done.tokenUsage().totalTokens()).isEqualTo(113);
    }

    private RagChatStreamService buildService(
        RagProperties props,
        java.util.function.LongFunction<SseEmitter> emitterFactory
    ) {
        RagChatStreamService service = new RagChatStreamService(
            ragChatService,
            vectorRetrievalService,
            groundedSystemPromptService,
            groqChatClient,
            providerOf(geminiChatClient),
            aiProperties,
            props,
            sseExecutor,
            emitterFactory
        );
        createdServices.add(service);
        return service;
    }

    private static CallerContext authenticateUser(UUID userId, UUID deptId, Role role) {
        CustomUserDetails principal = new CustomUserDetails(
            userId, deptId, "user@documind.ai", "HR Employee", role, "jti-" + userId, Instant.now().plusSeconds(900)
        );
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        return new CallerContext(userId, deptId, role, "127.0.0.1");
    }

    private static UserEntity activeUser(UUID id, UUID deptId, Role role) {
        UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(id);
        when(user.getDepartmentId()).thenReturn(deptId);
        when(user.getRole()).thenReturn(role);
        when(user.isActive()).thenReturn(true);
        return user;
    }

    private static RetrievedChunk sampleChunk() {
        CitationDto citation = new CitationDto(
            DOC_ID,
            "hr-policy.pdf",
            3,
            "Chế độ nghỉ phép năm",
            0.87d,
            "Nhân viên chính thức được hưởng 12 ngày phép năm.",
            "http://localhost:9000/documind-documents/hr-policy.pdf#page=3"
        );
        return new RetrievedChunk(
            "chunk-0",
            DOC_ID,
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            0,
            "Nhân viên chính thức được hưởng 12 ngày phép năm.",
            citation
        );
    }

    private static ChatMessageEntity historyMessage(MessageRole role, String content, boolean abstained) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setRole(role);
        entity.setContent(content);
        entity.setAbstained(abstained);
        return entity;
    }

    private static ChatResponse textChunk(String delta) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(delta))));
    }

    private static ChatResponse usageOnlyChunk(int promptTokens, int completionTokens) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
            .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
            .build();
        return new ChatResponse(List.of(), metadata);
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private record RecordedEvent(String name, Object payload) {
    }

    private static final class RecordingSseEmitter extends SseEmitter {

        private final List<RecordedEvent> recordedEvents = new CopyOnWriteArrayList<>();
        private final CountDownLatch completedLatch = new CountDownLatch(1);
        private final AtomicInteger sendCounter = new AtomicInteger(0);
        private final AtomicReference<Runnable> completionCallback = new AtomicReference<>();
        private final AtomicReference<Runnable> timeoutCallback = new AtomicReference<>();
        private final AtomicReference<Consumer<Throwable>> errorCallback = new AtomicReference<>();
        private volatile int failAtSend = -1;
        private volatile IOException failureToThrow;
        private volatile int blockAtSend = -1;
        private volatile CountDownLatch blockEnteredLatch;
        private volatile CountDownLatch blockReleaseLatch;

        RecordingSseEmitter(Long timeout) {
            super(timeout);
        }

        void failOnSendNumber(int sendNumber, IOException exception) {
            this.failAtSend = sendNumber;
            this.failureToThrow = exception;
        }

        void blockOnSendNumber(int sendNumber, CountDownLatch entered, CountDownLatch release) {
            this.blockAtSend = sendNumber;
            this.blockEnteredLatch = entered;
            this.blockReleaseLatch = release;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            int currentSend = sendCounter.incrementAndGet();
            if (blockAtSend == currentSend && blockEnteredLatch != null && blockReleaseLatch != null) {
                blockEnteredLatch.countDown();
                try {
                    blockReleaseLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while sending SSE", ex);
                }
            }
            if (failAtSend == currentSend && failureToThrow != null) {
                throw failureToThrow;
            }
            Set<ResponseBodyEmitter.DataWithMediaType> parts = builder.build();
            String eventName = null;
            Object payload = null;
            for (ResponseBodyEmitter.DataWithMediaType part : parts) {
                Object data = part.getData();
                if (data instanceof String s && s.startsWith("event:")) {
                    int newline = s.indexOf('\n');
                    eventName = (newline >= 0 ? s.substring("event:".length(), newline) : s.substring("event:".length())).trim();
                } else if (!(data instanceof String s && (s.equals("\n") || s.equals("\n\n") || s.startsWith("data:")))) {
                    payload = data;
                }
            }
            recordedEvents.add(new RecordedEvent(eventName, payload));
        }

        @Override
        public synchronized void complete() {
            completedLatch.countDown();
            Runnable cb = completionCallback.get();
            if (cb != null) {
                cb.run();
            }
        }

        @Override
        public synchronized void onCompletion(Runnable callback) {
            completionCallback.set(callback);
        }

        @Override
        public synchronized void onTimeout(Runnable callback) {
            timeoutCallback.set(callback);
        }

        @Override
        public synchronized void onError(Consumer<Throwable> callback) {
            errorCallback.set(callback);
        }

        void triggerError(Throwable ex) {
            Consumer<Throwable> cb = errorCallback.get();
            if (cb != null) {
                cb.accept(ex);
            }
        }

        void triggerCompletion() {
            Runnable cb = completionCallback.get();
            if (cb != null) {
                cb.run();
            }
        }

        void triggerTimeout() {
            Runnable cb = timeoutCallback.get();
            if (cb != null) {
                cb.run();
            }
        }

        boolean awaitCompleted(long timeout, TimeUnit unit) throws InterruptedException {
            return completedLatch.await(timeout, unit);
        }

        List<RecordedEvent> events() {
            return new ArrayList<>(recordedEvents);
        }
    }
}
