package com.documind.service.audit;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.ChatSessionEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.model.CitationPayload;
import com.documind.exception.ResourceNotFoundException;
import com.documind.infrastructure.config.IngestionAsyncConfig;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.ChatMessageRepository;
import com.documind.repository.ChatSessionRepository;
import com.documind.repository.ChatTurnPersistenceRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.chat.ChatSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AuditLogServiceTest {

    private static final UUID USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ChatSessionRepository chatSessionRepository;
    private ChatMessageRepository chatMessageRepository;
    private UserRepository userRepository;
    private DepartmentRepository departmentRepository;
    private AuditLogRepository auditLogRepository;
    private ChatTurnPersistenceRepository chatTurnPersistenceRepository;
    private TrackingTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        chatSessionRepository = mock(ChatSessionRepository.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        userRepository = mock(UserRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        chatTurnPersistenceRepository = mock(ChatTurnPersistenceRepository.class);
        transactionManager = new TrackingTransactionManager();
        transactionTemplate = new TransactionTemplate(transactionManager);
        auditLogService = new AuditLogService(
            chatSessionRepository,
            chatMessageRepository,
            userRepository,
            departmentRepository,
            auditLogRepository,
            chatTurnPersistenceRepository,
            transactionTemplate,
            new VirtualThreadTaskExecutor(IngestionAsyncConfig.AUDIT_VIRTUAL_THREAD_PREFIX)
        );
    }

    @Test
    void recordCompletedTurnAsyncRunsOnAuditVirtualThreadWithoutSecurityContextUsingTurn1Cte() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("principal", "creds")
        );
        try {
            UUID generatedSessionId = UUID.randomUUID();
            UUID generatedMsgId = UUID.randomUUID();
            AtomicReference<String> workerThreadName = new AtomicReference<>();
            AtomicReference<Object> workerAuth = new AtomicReference<>();
            AtomicBoolean txActiveInsideCte = new AtomicBoolean(false);

            when(chatTurnPersistenceRepository.insertNewSessionTurnAtomically(any()))
                .thenAnswer(inv -> {
                    workerThreadName.set(Thread.currentThread().getName());
                    workerAuth.set(SecurityContextHolder.getContext().getAuthentication());
                    txActiveInsideCte.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new ChatTurnPersistenceRepository.PersistedNewSessionTurn(generatedSessionId, generatedMsgId);
                });

            OffsetDateTime occurredAt = OffsetDateTime.of(2026, 11, 24, 6, 30, 0, 0, ZoneOffset.UTC);
            AuditLogService.CompletedChatTurnPayload payload = new AuditLogService.CompletedChatTurnPayload(
                USER_ID,
                DEPT_ID,
                null,
                "Chính sách nghỉ phép?",
                "Chính sách nghỉ phép?",
                null,
                "14 ngày phép năm.",
                List.of(new CitationPayload(UUID.randomUUID(), 1, "hr.pdf", 1, null, 0.92d, "14 ngày")),
                false,
                "groq",
                CacheTier.NONE,
                120,
                40,
                85L,
                "10.0.0.5",
                occurredAt,
                true
            );

            AuditLogService.PersistedTurnResult result = AuditLogService.awaitTurnPersistence(
                auditLogService.recordCompletedTurnAsync(payload)
            );

            assertThat(result.sessionId()).isEqualTo(generatedSessionId);
            assertThat(result.assistantMessageId()).isEqualTo(generatedMsgId);
            assertThat(workerThreadName.get()).startsWith(IngestionAsyncConfig.AUDIT_VIRTUAL_THREAD_PREFIX);
            assertThat(workerAuth.get()).isNull();
            assertThat(txActiveInsideCte.get()).isFalse();
            assertThat(transactionManager.commitCount.get()).isZero();
            assertThat(transactionManager.rollbackCount.get()).isZero();
            verify(auditLogRepository, never()).save(any());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void recordCompletedTurnAsyncRollsBackTransactionWhenDatabaseWriteFails() {
        UUID existingSessionId = UUID.randomUUID();
        ChatSessionEntity lockedSession = new ChatSessionEntity();
        lockedSession.setId(existingSessionId);
        lockedSession.setTitle("Existing session");
        lockedSession.setCreatedAt(OffsetDateTime.of(2026, 11, 24, 6, 0, 0, 0, ZoneOffset.UTC));
        UserEntity userRef = new UserEntity();
        userRef.setId(USER_ID);
        DepartmentEntity deptRef = new DepartmentEntity();
        deptRef.setId(DEPT_ID);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(departmentRepository.getReferenceById(DEPT_ID)).thenReturn(deptRef);
        when(chatSessionRepository.findByIdAndUserIdForUpdate(existingSessionId, USER_ID))
            .thenReturn(Optional.of(lockedSession));
        when(chatMessageRepository.findFirstBySessionIdOrderByCreatedAtDescIdDesc(existingSessionId))
            .thenReturn(Optional.empty());
        when(chatSessionRepository.save(any())).thenReturn(lockedSession);
        when(chatMessageRepository.save(any()))
            .thenThrow(new DataAccessResourceFailureException("Simulated DB write failure"));

        AuditLogService.CompletedChatTurnPayload followUpPayload = new AuditLogService.CompletedChatTurnPayload(
            USER_ID,
            DEPT_ID,
            existingSessionId,
            "Title",
            "Follow-up question?",
            "Follow-up question?",
            "Answer",
            List.of(),
            false,
            "groq",
            CacheTier.NONE,
            100,
            25,
            50L,
            "127.0.0.1",
            OffsetDateTime.now(ZoneOffset.UTC),
            true
        );

        assertThatThrownBy(() -> AuditLogService.awaitTurnPersistence(
            auditLogService.recordCompletedTurnAsync(followUpPayload)
        )).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(transactionManager.commitCount.get()).isZero();
        assertThat(transactionManager.rollbackCount.get()).isEqualTo(1);

        when(chatTurnPersistenceRepository.insertNewSessionTurnAtomically(any()))
            .thenThrow(new DataAccessResourceFailureException("Simulated Turn-1 CTE DB failure"));
        AuditLogService.CompletedChatTurnPayload turn1Payload = new AuditLogService.CompletedChatTurnPayload(
            USER_ID,
            DEPT_ID,
            null,
            "Title",
            "Question?",
            null,
            "Answer",
            List.of(),
            false,
            "groq",
            CacheTier.NONE,
            100,
            25,
            50L,
            "127.0.0.1",
            OffsetDateTime.now(ZoneOffset.UTC),
            true
        );

        assertThatThrownBy(() -> AuditLogService.awaitTurnPersistence(
            auditLogService.recordCompletedTurnAsync(turn1Payload)
        )).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void recordCompletedTurnAsyncHandlesFollowUpDeletedSessionWithCommitted404AuditAndThrows() {
        UUID deletedSessionId = UUID.randomUUID();
        UserEntity userRef = new UserEntity();
        userRef.setId(USER_ID);
        DepartmentEntity deptRef = new DepartmentEntity();
        deptRef.setId(DEPT_ID);

        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(departmentRepository.getReferenceById(DEPT_ID)).thenReturn(deptRef);
        when(chatSessionRepository.findByIdAndUserIdForUpdate(deletedSessionId, USER_ID))
            .thenReturn(Optional.empty());

        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 11, 24, 7, 0, 0, 0, ZoneOffset.UTC);
        AuditLogService.CompletedChatTurnPayload payload = new AuditLogService.CompletedChatTurnPayload(
            USER_ID,
            DEPT_ID,
            deletedSessionId,
            "Follow up",
            "Còn phụ cấp thì sao?",
            "Phụ cấp công tác là bao nhiêu?",
            "500.000 VND/ngày",
            List.of(),
            false,
            "groq",
            CacheTier.NONE,
            150,
            45,
            140L,
            "10.0.0.8",
            occurredAt,
            true
        );

        assertThatThrownBy(() -> AuditLogService.awaitTurnPersistence(
            auditLogService.recordCompletedTurnAsync(payload)
        )).isInstanceOf(ResourceNotFoundException.class);

        assertThat(transactionManager.commitCount.get()).isEqualTo(1);
        assertThat(transactionManager.rollbackCount.get()).isZero();
        verify(chatMessageRepository, never()).save(any());

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntity saved = auditCaptor.getValue();
        assertThat(saved.getActionType()).isEqualTo(AuditActionType.CHAT_QUERY);
        assertThat(saved.getSessionId()).isNull();
        assertThat(saved.getStatusCode()).isEqualTo(404);
        assertThat(saved.getPromptTokens()).isEqualTo(150);
        assertThat(saved.getCompletionTokens()).isEqualTo(45);
        assertThat(saved.getCreatedAt()).isEqualTo(occurredAt);
    }

    @Test
    void recordCompletedTurnAsyncFollowUpSavesAlternatingMessagesAndSingleCacheHitAudit() {
        UUID sessionId = UUID.randomUUID();
        UserEntity userRef = new UserEntity();
        userRef.setId(USER_ID);
        DepartmentEntity deptRef = new DepartmentEntity();
        deptRef.setId(DEPT_ID);
        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setUser(userRef);
        session.setTitle(ChatSessionService.DEFAULT_SESSION_TITLE);
        session.setCreatedAt(OffsetDateTime.of(2026, 11, 24, 5, 0, 0, 0, ZoneOffset.UTC));

        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(departmentRepository.getReferenceById(DEPT_ID)).thenReturn(deptRef);
        when(chatSessionRepository.findByIdAndUserIdForUpdate(sessionId, USER_ID))
            .thenReturn(Optional.of(session));
        when(chatMessageRepository.findFirstBySessionIdOrderByCreatedAtDescIdDesc(sessionId))
            .thenReturn(Optional.empty());
        when(chatSessionRepository.save(any(ChatSessionEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        List<ChatMessageEntity> savedMessages = new ArrayList<>();
        when(chatMessageRepository.save(any(ChatMessageEntity.class))).thenAnswer(inv -> {
            ChatMessageEntity msg = inv.getArgument(0);
            msg.setId(UUID.randomUUID());
            savedMessages.add(msg);
            return msg;
        });

        OffsetDateTime occurredAt = OffsetDateTime.of(2026, 11, 24, 6, 45, 0, 0, ZoneOffset.UTC);
        AuditLogService.CompletedChatTurnPayload payload = new AuditLogService.CompletedChatTurnPayload(
            USER_ID,
            DEPT_ID,
            sessionId,
            "Phép năm",
            "Phép năm?",
            null,
            "14 ngày.",
            List.of(),
            false,
            "groq",
            CacheTier.L2,
            0,
            0,
            12L,
            "127.0.0.1",
            occurredAt,
            true
        );

        AuditLogService.PersistedTurnResult res = AuditLogService.awaitTurnPersistence(
            auditLogService.recordCompletedTurnAsync(payload)
        );
        assertThat(res.sessionId()).isEqualTo(sessionId);
        assertThat(session.getTitle()).isEqualTo("Phép năm");
        assertThat(savedMessages).hasSize(2);
        assertThat(savedMessages.get(0).getRole()).isEqualTo(MessageRole.USER);
        assertThat(savedMessages.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(savedMessages.get(1).getCacheTier()).isEqualTo(CacheTier.L2);

        ArgumentCaptor<AuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogRepository, times(1)).save(auditCaptor.capture());
        AuditLogEntity audit = auditCaptor.getValue();
        assertThat(audit.isCacheHit()).isTrue();
        assertThat(audit.getCacheTier()).isEqualTo(CacheTier.L2);
        assertThat(audit.getStatusCode()).isEqualTo(200);
        assertThat(audit.getLatencyMs()).isEqualTo(12L);
    }

    @Test
    void recordAuditOnlyFailureAndRateLimitHandleErrorsCleanlyAndAwaitPendingWrites() throws Exception {
        CountDownLatch slowWriteEntered = new CountDownLatch(1);
        CountDownLatch releaseSlowWrite = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            slowWriteEntered.countDown();
            releaseSlowWrite.await(5, TimeUnit.SECONDS);
            return null;
        }).when(chatTurnPersistenceRepository).insertRateLimitAuditAtomically(
            any(), any(), any(), any(Long.class), any(), any()
        );

        CompletableFuture<Void> rateLimitFuture = auditLogService.recordRateLimitRejectionAsync(
            new AuditLogService.RateLimitAuditPayload(
                USER_ID,
                DEPT_ID,
                "RATE_LIMIT:CHAT",
                3L,
                "192.168.1.10",
                OffsetDateTime.now(ZoneOffset.UTC)
            )
        );
        assertThat(slowWriteEntered.await(5, TimeUnit.SECONDS)).isTrue();
        releaseSlowWrite.countDown();
        auditLogService.awaitPendingWrites(Duration.ofSeconds(5));
        assertThat(rateLimitFuture).isDone();

        verify(chatTurnPersistenceRepository).insertRateLimitAuditAtomically(
            org.mockito.ArgumentMatchers.eq(USER_ID),
            org.mockito.ArgumentMatchers.eq(DEPT_ID),
            org.mockito.ArgumentMatchers.eq("RATE_LIMIT:CHAT"),
            org.mockito.ArgumentMatchers.eq(3L),
            org.mockito.ArgumentMatchers.eq("192.168.1.10"),
            any(OffsetDateTime.class)
        );
        verify(auditLogRepository, never()).save(any());

        // Failure audit exception must be caught and logged without crashing caller
        UserEntity userRef = new UserEntity();
        userRef.setId(USER_ID);
        DepartmentEntity deptRef = new DepartmentEntity();
        deptRef.setId(DEPT_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(departmentRepository.getReferenceById(DEPT_ID)).thenReturn(deptRef);
        when(auditLogRepository.save(any(AuditLogEntity.class)))
            .thenThrow(new DataAccessResourceFailureException("DB offline"));
        auditLogService.recordChatFailureAsync(
            new AuditLogService.ChatFailureAuditPayload(
                USER_ID,
                DEPT_ID,
                null,
                "Failing query",
                30,
                10,
                50L,
                "groq",
                502,
                CacheTier.NONE,
                "127.0.0.1",
                OffsetDateTime.now(ZoneOffset.UTC)
            )
        ).join();
    }

    @Test
    void rateLimitFloodShedsExcessFireAndForgetAuditsWithoutStarvingCompletedTurnWrites() throws Exception {
        AuditLogService boundedService = new AuditLogService(
            chatSessionRepository,
            chatMessageRepository,
            userRepository,
            departmentRepository,
            auditLogRepository,
            chatTurnPersistenceRepository,
            transactionTemplate,
            new VirtualThreadTaskExecutor(IngestionAsyncConfig.AUDIT_VIRTUAL_THREAD_PREFIX),
            2
        );

        CountDownLatch twoWritesActive = new CountDownLatch(2);
        CountDownLatch releaseWrites = new CountDownLatch(1);
        AtomicInteger concurrentWrites = new AtomicInteger();
        AtomicInteger maxConcurrentWrites = new AtomicInteger();

        org.mockito.Mockito.doAnswer(inv -> {
            int active = concurrentWrites.incrementAndGet();
            maxConcurrentWrites.accumulateAndGet(active, Math::max);
            twoWritesActive.countDown();
            try {
                releaseWrites.await(5, TimeUnit.SECONDS);
            } finally {
                concurrentWrites.decrementAndGet();
            }
            return null;
        }).when(chatTurnPersistenceRepository).insertRateLimitAuditAtomically(
            any(), any(), any(), any(Long.class), any(), any()
        );

        UUID expectedSessionId = UUID.randomUUID();
        UUID expectedMsgId = UUID.randomUUID();
        when(chatTurnPersistenceRepository.insertNewSessionTurnAtomically(any()))
            .thenReturn(new ChatTurnPersistenceRepository.PersistedNewSessionTurn(expectedSessionId, expectedMsgId));

        AuditLogService.RateLimitAuditPayload ratePayload = new AuditLogService.RateLimitAuditPayload(
            USER_ID,
            DEPT_ID,
            "RATE_LIMIT:CHAT:/api/v1/chat/ask",
            1L,
            "127.0.0.1",
            OffsetDateTime.now(ZoneOffset.UTC)
        );
        boundedService.recordRateLimitRejectionAsync(ratePayload);
        boundedService.recordRateLimitRejectionAsync(ratePayload);
        assertThat(twoWritesActive.await(5, TimeUnit.SECONDS)).isTrue();

        // Next 20 flooded 429 audits must be shed immediately without consuming more DB connections
        for (int i = 0; i < 20; i++) {
            assertThat(boundedService.recordRateLimitRejectionAsync(ratePayload)).isDone();
        }
        assertThat(boundedService.droppedFireAndForgetAuditCount()).isEqualTo(20L);
        assertThat(maxConcurrentWrites.get()).isEqualTo(2);

        // Live completed-turn persistence must remain completely unblocked while 429 permits are saturated
        AuditLogService.PersistedTurnResult liveTurn = AuditLogService.awaitTurnPersistence(
            boundedService.recordCompletedTurnAsync(new AuditLogService.CompletedChatTurnPayload(
                USER_ID,
                DEPT_ID,
                null,
                "Live question",
                "Live question?",
                null,
                "Live answer",
                List.of(),
                false,
                "groq",
                CacheTier.NONE,
                40,
                15,
                25L,
                "127.0.0.1",
                OffsetDateTime.now(ZoneOffset.UTC),
                true
            ))
        );
        assertThat(liveTurn.sessionId()).isEqualTo(expectedSessionId);
        assertThat(liveTurn.assistantMessageId()).isEqualTo(expectedMsgId);

        releaseWrites.countDown();
        boundedService.awaitPendingWrites(Duration.ofSeconds(5));
        verify(chatTurnPersistenceRepository, times(2)).insertRateLimitAuditAtomically(
            any(), any(), any(), any(Long.class), any(), any()
        );
    }

    @Test
    void recordChatFailureLogsWarningInsteadOfPropagatingWhenAsyncSaveFails(CapturedOutput output) {
        UserEntity userRef = new UserEntity();
        userRef.setId(USER_ID);
        DepartmentEntity deptRef = new DepartmentEntity();
        deptRef.setId(DEPT_ID);
        when(userRepository.getReferenceById(USER_ID)).thenReturn(userRef);
        when(departmentRepository.getReferenceById(DEPT_ID)).thenReturn(deptRef);
        when(auditLogRepository.save(any(AuditLogEntity.class)))
            .thenThrow(new IllegalStateException("Audit store failed"));

        auditLogService.recordChatFailureAsync(
            new AuditLogService.ChatFailureAuditPayload(
                USER_ID,
                DEPT_ID,
                null,
                "Disconnected query",
                20,
                5,
                40L,
                "groq",
                499,
                CacheTier.L1,
                "127.0.0.1",
                OffsetDateTime.now(ZoneOffset.UTC)
            )
        ).join();

        assertThat(output.getOut() + output.getErr()).contains("Failed to persist async failure audit log");
    }

    private static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {

        final AtomicInteger commitCount = new AtomicInteger();
        final AtomicInteger rollbackCount = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commitCount.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbackCount.incrementAndGet();
        }
    }
}
