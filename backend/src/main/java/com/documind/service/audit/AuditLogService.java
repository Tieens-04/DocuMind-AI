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
import com.documind.infrastructure.security.ClientIpResolver;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.ChatMessageRepository;
import com.documind.repository.ChatSessionRepository;
import com.documind.repository.ChatTurnPersistenceRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Virtual-Thread asynchronous persistence worker for immutable chat turn commits,
 * FinOps failure/abort audits, and bounded {@code RATE_LIMITED} telemetry without crossing thread
 * boundaries with {@code SecurityContext} or lazy JPA entities.
 */
@Service
public class AuditLogService {

    public static final String DEFAULT_SESSION_TITLE = "Cuộc hội thoại mới";

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final Duration SHUTDOWN_DRAIN_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_CONCURRENT_BACKGROUND_AUDITS = 4;

    private final Supplier<ChatSessionRepository> chatSessionRepositorySupplier;
    private final Supplier<ChatMessageRepository> chatMessageRepositorySupplier;
    private final Supplier<UserRepository> userRepositorySupplier;
    private final Supplier<DepartmentRepository> departmentRepositorySupplier;
    private final Supplier<AuditLogRepository> auditLogRepositorySupplier;
    private final Supplier<ChatTurnPersistenceRepository> chatTurnPersistenceRepositorySupplier;
    private final Supplier<TransactionTemplate> transactionTemplateSupplier;
    private final TaskExecutor auditLogExecutor;
    private final Semaphore fireAndForgetAuditPermits;
    private final AtomicLong droppedFireAndForgetAudits = new AtomicLong();
    private final Set<CompletableFuture<Void>> pendingWrites = ConcurrentHashMap.newKeySet();

    @Autowired
    public AuditLogService(
        ObjectProvider<ChatSessionRepository> chatSessionRepositoryProvider,
        ObjectProvider<ChatMessageRepository> chatMessageRepositoryProvider,
        ObjectProvider<UserRepository> userRepositoryProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<AuditLogRepository> auditLogRepositoryProvider,
        ObjectProvider<ChatTurnPersistenceRepository> chatTurnPersistenceRepositoryProvider,
        ObjectProvider<TransactionTemplate> transactionTemplateProvider,
        @Qualifier(IngestionAsyncConfig.AUDIT_LOG_TASK_EXECUTOR) TaskExecutor auditLogExecutor
    ) {
        this(
            () -> Objects.requireNonNull(chatSessionRepositoryProvider, "chatSessionRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(chatMessageRepositoryProvider, "chatMessageRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(userRepositoryProvider, "userRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null").getObject(),
            () -> Objects.requireNonNull(auditLogRepositoryProvider, "auditLogRepositoryProvider must not be null").getObject(),
            () -> chatTurnPersistenceRepositoryProvider != null ? chatTurnPersistenceRepositoryProvider.getIfAvailable() : null,
            () -> Objects.requireNonNull(transactionTemplateProvider, "transactionTemplateProvider must not be null").getObject(),
            auditLogExecutor,
            DEFAULT_MAX_CONCURRENT_BACKGROUND_AUDITS
        );
    }

    public AuditLogService(
        ChatSessionRepository chatSessionRepository,
        ChatMessageRepository chatMessageRepository,
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        AuditLogRepository auditLogRepository,
        ChatTurnPersistenceRepository chatTurnPersistenceRepository,
        TransactionTemplate transactionTemplate,
        TaskExecutor auditLogExecutor
    ) {
        this(
            chatSessionRepository,
            chatMessageRepository,
            userRepository,
            departmentRepository,
            auditLogRepository,
            chatTurnPersistenceRepository,
            transactionTemplate,
            auditLogExecutor,
            DEFAULT_MAX_CONCURRENT_BACKGROUND_AUDITS
        );
    }

    AuditLogService(
        ChatSessionRepository chatSessionRepository,
        ChatMessageRepository chatMessageRepository,
        UserRepository userRepository,
        DepartmentRepository departmentRepository,
        AuditLogRepository auditLogRepository,
        ChatTurnPersistenceRepository chatTurnPersistenceRepository,
        TransactionTemplate transactionTemplate,
        TaskExecutor auditLogExecutor,
        int maxConcurrentBackgroundAudits
    ) {
        this(
            () -> Objects.requireNonNull(chatSessionRepository, "chatSessionRepository must not be null"),
            () -> Objects.requireNonNull(chatMessageRepository, "chatMessageRepository must not be null"),
            () -> Objects.requireNonNull(userRepository, "userRepository must not be null"),
            () -> Objects.requireNonNull(departmentRepository, "departmentRepository must not be null"),
            () -> Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null"),
            () -> chatTurnPersistenceRepository,
            () -> Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null"),
            auditLogExecutor,
            maxConcurrentBackgroundAudits
        );
    }

    private AuditLogService(
        Supplier<ChatSessionRepository> chatSessionRepositorySupplier,
        Supplier<ChatMessageRepository> chatMessageRepositorySupplier,
        Supplier<UserRepository> userRepositorySupplier,
        Supplier<DepartmentRepository> departmentRepositorySupplier,
        Supplier<AuditLogRepository> auditLogRepositorySupplier,
        Supplier<ChatTurnPersistenceRepository> chatTurnPersistenceRepositorySupplier,
        Supplier<TransactionTemplate> transactionTemplateSupplier,
        TaskExecutor auditLogExecutor,
        int maxConcurrentBackgroundAudits
    ) {
        this.chatSessionRepositorySupplier = chatSessionRepositorySupplier;
        this.chatMessageRepositorySupplier = chatMessageRepositorySupplier;
        this.userRepositorySupplier = userRepositorySupplier;
        this.departmentRepositorySupplier = departmentRepositorySupplier;
        this.auditLogRepositorySupplier = auditLogRepositorySupplier;
        this.chatTurnPersistenceRepositorySupplier = chatTurnPersistenceRepositorySupplier;
        this.transactionTemplateSupplier = transactionTemplateSupplier;
        this.auditLogExecutor = Objects.requireNonNull(auditLogExecutor, "auditLogExecutor must not be null");
        this.fireAndForgetAuditPermits = new Semaphore(Math.max(1, maxConcurrentBackgroundAudits));
    }

    /**
     * Dispatches atomic turn persistence (Turn-1 CTE, locked follow-up turn, or audit-only)
     * onto {@code auditLogExecutor}; callers await the returned future before emitting final IDs.
     */
    public CompletableFuture<PersistedTurnResult> recordCompletedTurnAsync(
        CompletedChatTurnPayload payload
    ) {
        Objects.requireNonNull(payload, "payload must not be null");
        return track(CompletableFuture.supplyAsync(() -> executeCompletedTurn(payload), auditLogExecutor));
    }

    /**
     * Executes atomic turn persistence synchronously on the caller's thread (used by legacy non-Spring
     * test collaborators and deterministic fallback wiring).
     */
    public PersistedTurnResult recordCompletedTurnSync(CompletedChatTurnPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return executeCompletedTurn(payload);
    }

    public static PersistedTurnResult awaitTurnPersistence(
        CompletableFuture<PersistedTurnResult> future
    ) {
        Objects.requireNonNull(future, "future must not be null");
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting chat turn persistence", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Chat turn persistence failed", cause);
        }
    }

    /**
     * Records a non-200 failure or SSE client-abort ({@code 499}/{@code 502}/{@code 504}) audit row
     * asynchronously under bounded concurrency without blocking the caller or starving HikariCP.
     */
    public CompletableFuture<Void> recordChatFailureAsync(ChatFailureAuditPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return submitBoundedFireAndForget("CHAT_FAILURE", () -> executeChatFailureAudit(payload));
    }

    /**
     * Records a non-200 failure audit row synchronously on the caller's thread (legacy test fallback).
     */
    public void recordChatFailureSync(ChatFailureAuditPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        executeChatFailureAudit(payload);
    }

    /**
     * Records a Bucket4j quota rejection under {@code RATE_LIMITED} with status {@code 429} and zero tokens
     * under bounded concurrency so {@code 429} floods cannot exhaust the HikariCP pool.
     */
    public CompletableFuture<Void> recordRateLimitRejectionAsync(RateLimitAuditPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        return submitBoundedFireAndForget("RATE_LIMITED", () -> executeRateLimitAudit(payload));
    }

    public long droppedFireAndForgetAuditCount() {
        return droppedFireAndForgetAudits.get();
    }

    public void awaitPendingWrites(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long deadlineNanos = System.nanoTime() + Math.max(1L, timeout.toNanos());
        while (!pendingWrites.isEmpty()) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                return;
            }
            List<CompletableFuture<Void>> snapshot = List.copyOf(pendingWrites);
            if (snapshot.isEmpty()) {
                return;
            }
            try {
                CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new))
                    .get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException ex) {
                return;
            }
        }
    }

    @PreDestroy
    void drainPendingWritesOnShutdown() {
        awaitPendingWrites(SHUTDOWN_DRAIN_TIMEOUT);
    }

    private CompletableFuture<Void> submitBoundedFireAndForget(String category, Runnable task) {
        if (!fireAndForgetAuditPermits.tryAcquire()) {
            long dropped = droppedFireAndForgetAudits.incrementAndGet();
            if (dropped == 1L || dropped % 100L == 0L) {
                log.warn(
                    "Shedding excess async {} audit write under backpressure (totalDropped={})",
                    category,
                    dropped
                );
            }
            return CompletableFuture.completedFuture(null);
        }
        try {
            return track(CompletableFuture.runAsync(() -> {
                try {
                    task.run();
                } finally {
                    fireAndForgetAuditPermits.release();
                }
            }, auditLogExecutor));
        } catch (RuntimeException ex) {
            fireAndForgetAuditPermits.release();
            throw ex;
        }
    }

    private PersistedTurnResult executeCompletedTurn(CompletedChatTurnPayload payload) {
        OffsetDateTime occurredAt = normalizeTimestamp(payload.occurredAt());
        if (!payload.persistChatTurn() || payload.userId() == null) {
            transactionTemplateSupplier.get().executeWithoutResult(status -> {
                UserEntity userRef = resolveUserReference(payload.userId());
                DepartmentEntity deptRef = payload.auditDepartmentId() != null
                    ? departmentRepositorySupplier.get().getReferenceById(payload.auditDepartmentId())
                    : null;
                auditLogRepositorySupplier.get().save(buildChatAuditLog(
                    userRef,
                    deptRef,
                    null,
                    payload.userQuestion(),
                    payload.abstained(),
                    payload.cacheTier(),
                    payload.promptTokens(),
                    payload.completionTokens(),
                    payload.latencyMs(),
                    payload.modelProvider(),
                    HttpStatus.OK.value(),
                    payload.ipAddress(),
                    occurredAt
                ));
            });
            return null;
        }

        ChatTurnPersistenceRepository turnRepository = chatTurnPersistenceRepositorySupplier.get();
        if (payload.sessionId() == null && turnRepository != null) {
            OffsetDateTime userCreatedAt = occurredAt;
            OffsetDateTime assistantCreatedAt = userCreatedAt.plus(1, ChronoUnit.MICROS);
            String title = (payload.sessionTitle() != null && !payload.sessionTitle().isBlank())
                ? payload.sessionTitle()
                : DEFAULT_SESSION_TITLE;

            // Single data-modifying CTE runs atomically in Postgres without JpaTransactionManager BEGIN/COMMIT overhead
            ChatTurnPersistenceRepository.PersistedNewSessionTurn inserted = turnRepository.insertNewSessionTurnAtomically(
                new ChatTurnPersistenceRepository.NewSessionTurnInsert(
                    payload.userId(),
                    payload.auditDepartmentId(),
                    title,
                    payload.userQuestion(),
                    payload.rewrittenQuery(),
                    payload.assistantAnswer(),
                    payload.citations(),
                    payload.abstained(),
                    payload.cacheTier(),
                    payload.promptTokens(),
                    payload.completionTokens(),
                    payload.latencyMs(),
                    payload.modelProvider(),
                    ClientIpResolver.normalize(payload.ipAddress()),
                    userCreatedAt,
                    assistantCreatedAt,
                    occurredAt
                )
            );
            return new PersistedTurnResult(
                Objects.requireNonNull(inserted, "inserted turn must not be null").sessionId(),
                inserted.assistantMessageId()
            );
        }

        PersistedTurnResult persisted = transactionTemplateSupplier.get().execute(status -> {
            ChatSessionRepository sessionRepo = chatSessionRepositorySupplier.get();
            ChatMessageRepository messageRepo = chatMessageRepositorySupplier.get();
            UserEntity actorRef = resolveUserReference(payload.userId());
            DepartmentEntity deptRef = payload.auditDepartmentId() != null
                ? departmentRepositorySupplier.get().getReferenceById(payload.auditDepartmentId())
                : null;

            ChatSessionEntity session;
            OffsetDateTime userCreatedAt;
            OffsetDateTime assistantCreatedAt;
            if (payload.sessionId() == null) {
                userCreatedAt = occurredAt;
                assistantCreatedAt = userCreatedAt.plus(1, ChronoUnit.MICROS);
                String title = (payload.sessionTitle() != null && !payload.sessionTitle().isBlank())
                    ? payload.sessionTitle()
                    : DEFAULT_SESSION_TITLE;
                session = new ChatSessionEntity();
                session.setUser(actorRef);
                session.setTitle(title);
                session.setCreatedAt(userCreatedAt);
                session.setUpdatedAt(assistantCreatedAt);
                session = sessionRepo.save(session);
            } else {
                Optional<ChatSessionEntity> lockedOpt =
                    sessionRepo.findByIdAndUserIdForUpdate(payload.sessionId(), payload.userId());
                if (lockedOpt.isEmpty()) {
                    // Return null so the 404 FinOps audit commits cleanly before throwing ResourceNotFoundException
                    auditLogRepositorySupplier.get().save(buildChatAuditLog(
                        actorRef,
                        deptRef,
                        null,
                        payload.userQuestion(),
                        payload.abstained(),
                        payload.cacheTier(),
                        payload.promptTokens(),
                        payload.completionTokens(),
                        payload.latencyMs(),
                        payload.modelProvider(),
                        HttpStatus.NOT_FOUND.value(),
                        payload.ipAddress(),
                        occurredAt
                    ));
                    return null;
                }
                session = lockedOpt.get();
                Optional<ChatMessageEntity> latestMsg =
                    messageRepo.findFirstBySessionIdOrderByCreatedAtDescIdDesc(session.getId());
                boolean isFirstTurn = latestMsg.isEmpty();
                OffsetDateTime floor = latestMsg.map(ChatMessageEntity::getCreatedAt).orElse(session.getCreatedAt());
                userCreatedAt = resolveMonotonicTimestamp(floor, occurredAt);
                assistantCreatedAt = resolveMonotonicTimestamp(userCreatedAt, occurredAt);
                if (isFirstTurn
                    && DEFAULT_SESSION_TITLE.equals(session.getTitle())
                    && payload.sessionTitle() != null
                    && !payload.sessionTitle().isBlank()) {
                    session.setTitle(payload.sessionTitle());
                }
                session.setUpdatedAt(assistantCreatedAt);
                session = sessionRepo.save(session);
            }

            ChatMessageEntity userMessage = new ChatMessageEntity();
            userMessage.setSession(session);
            userMessage.setRole(MessageRole.USER);
            userMessage.setContent(payload.userQuestion());
            userMessage.setCitations(List.of());
            userMessage.setAbstained(false);
            userMessage.setCacheTier(CacheTier.NONE);
            userMessage.setPromptTokens(0);
            userMessage.setCompletionTokens(0);
            userMessage.setCreatedAt(userCreatedAt);
            messageRepo.save(userMessage);

            ChatMessageEntity assistantMessage = new ChatMessageEntity();
            assistantMessage.setSession(session);
            assistantMessage.setRole(MessageRole.ASSISTANT);
            assistantMessage.setContent(payload.assistantAnswer());
            assistantMessage.setRewrittenQuery(payload.rewrittenQuery());
            assistantMessage.setCitations(payload.citations());
            assistantMessage.setAbstained(payload.abstained());
            assistantMessage.setCacheTier(payload.cacheTier());
            assistantMessage.setPromptTokens(Math.max(0, payload.promptTokens()));
            assistantMessage.setCompletionTokens(Math.max(0, payload.completionTokens()));
            assistantMessage.setLatencyMs(Math.max(0L, payload.latencyMs()));
            assistantMessage.setModelProvider(payload.modelProvider());
            assistantMessage.setCreatedAt(assistantCreatedAt);
            ChatMessageEntity savedAssistant = messageRepo.save(assistantMessage);

            auditLogRepositorySupplier.get().save(buildChatAuditLog(
                actorRef,
                deptRef,
                session,
                payload.userQuestion(),
                payload.abstained(),
                payload.cacheTier(),
                payload.promptTokens(),
                payload.completionTokens(),
                payload.latencyMs(),
                payload.modelProvider(),
                HttpStatus.OK.value(),
                payload.ipAddress(),
                occurredAt
            ));

            return new PersistedTurnResult(session.getId(), savedAssistant.getId());
        });

        if (persisted == null && payload.sessionId() != null) {
            throw new ResourceNotFoundException("Chat session not found: " + payload.sessionId());
        }
        return persisted;
    }

    private void executeChatFailureAudit(ChatFailureAuditPayload payload) {
        OffsetDateTime occurredAt = normalizeTimestamp(payload.occurredAt());
        try {
            transactionTemplateSupplier.get().executeWithoutResult(status -> {
                ChatSessionEntity session = (payload.sessionId() != null && payload.userId() != null)
                    ? chatSessionRepositorySupplier.get()
                        .findByIdAndUserIdForUpdate(payload.sessionId(), payload.userId())
                        .orElse(null)
                    : null;
                UserEntity userRef = resolveUserReference(payload.userId());
                DepartmentEntity deptRef = payload.auditDepartmentId() != null
                    ? departmentRepositorySupplier.get().getReferenceById(payload.auditDepartmentId())
                    : null;

                auditLogRepositorySupplier.get().save(buildChatAuditLog(
                    userRef,
                    deptRef,
                    session,
                    payload.queryText(),
                    true,
                    payload.cacheTier(),
                    payload.promptTokens(),
                    payload.completionTokens(),
                    payload.latencyMs(),
                    payload.provider(),
                    payload.statusCode(),
                    payload.ipAddress(),
                    occurredAt
                ));
            });
        } catch (RuntimeException ex) {
            log.warn("Failed to persist async failure audit log (status={}): {}", payload.statusCode(), ex.getMessage());
        }
    }

    private void executeRateLimitAudit(RateLimitAuditPayload payload) {
        OffsetDateTime occurredAt = normalizeTimestamp(payload.occurredAt());
        try {
            ChatTurnPersistenceRepository turnRepository = chatTurnPersistenceRepositorySupplier.get();
            if (turnRepository != null) {
                turnRepository.insertRateLimitAuditAtomically(
                    payload.userId(),
                    payload.departmentId(),
                    payload.detailText(),
                    Math.max(0L, payload.latencyMs()),
                    ClientIpResolver.normalize(payload.ipAddress()),
                    occurredAt
                );
                return;
            }
            transactionTemplateSupplier.get().executeWithoutResult(status -> {
                UserRepository userRepo = userRepositorySupplier.get();
                DepartmentRepository deptRepo = departmentRepositorySupplier.get();
                UserEntity userRef = (payload.userId() != null && userRepo.existsById(payload.userId()))
                    ? userRepo.getReferenceById(payload.userId())
                    : null;
                DepartmentEntity deptRef = (payload.departmentId() != null && deptRepo.existsById(payload.departmentId()))
                    ? deptRepo.getReferenceById(payload.departmentId())
                    : null;

                AuditLogEntity auditLog = new AuditLogEntity();
                auditLog.setUser(userRef);
                auditLog.setDepartment(deptRef);
                auditLog.setActionType(AuditActionType.RATE_LIMITED);
                auditLog.setQueryText(payload.detailText());
                auditLog.setAbstained(false);
                auditLog.setCacheTier(CacheTier.NONE);
                auditLog.setPromptTokens(0);
                auditLog.setCompletionTokens(0);
                auditLog.setLatencyMs(Math.max(0L, payload.latencyMs()));
                auditLog.setProvider(null);
                auditLog.setStatusCode(HttpStatus.TOO_MANY_REQUESTS.value());
                auditLog.setIpAddress(ClientIpResolver.normalize(payload.ipAddress()));
                auditLog.setCreatedAt(occurredAt);
                auditLogRepositorySupplier.get().save(auditLog);
            });
        } catch (RuntimeException ex) {
            log.warn("Failed to persist async rate-limit audit log: {}", ex.getMessage());
        }
    }

    private UserEntity resolveUserReference(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserEntity ref = userRepositorySupplier.get().getReferenceById(userId);
        if (ref != null) {
            return ref;
        }
        UserEntity stub = new UserEntity();
        stub.setId(userId);
        return stub;
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        CompletableFuture<Void> completion = future.handle((ignored, error) -> null);
        pendingWrites.add(completion);
        completion.whenComplete((ignored, error) -> pendingWrites.remove(completion));
        return future;
    }

    private static AuditLogEntity buildChatAuditLog(
        UserEntity user,
        DepartmentEntity department,
        ChatSessionEntity session,
        String queryText,
        boolean abstained,
        CacheTier cacheTier,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String provider,
        int statusCode,
        String ipAddress,
        OffsetDateTime occurredAt
    ) {
        AuditLogEntity auditLog = new AuditLogEntity();
        auditLog.setUser(user);
        auditLog.setDepartment(department);
        auditLog.setSession(session);
        auditLog.setActionType(AuditActionType.CHAT_QUERY);
        auditLog.setQueryText(queryText);
        auditLog.setAbstained(abstained);
        auditLog.setCacheTier(cacheTier != null ? cacheTier : CacheTier.NONE);
        auditLog.setPromptTokens(Math.max(0, promptTokens));
        auditLog.setCompletionTokens(Math.max(0, completionTokens));
        auditLog.setLatencyMs(Math.max(0L, latencyMs));
        auditLog.setProvider(provider);
        auditLog.setStatusCode(statusCode);
        auditLog.setIpAddress(ClientIpResolver.normalize(ipAddress));
        auditLog.setCreatedAt(occurredAt);
        return auditLog;
    }

    private static OffsetDateTime normalizeTimestamp(OffsetDateTime timestamp) {
        OffsetDateTime effective = timestamp != null ? timestamp : OffsetDateTime.now(ZoneOffset.UTC);
        return effective.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime resolveMonotonicTimestamp(OffsetDateTime floorExclusive, OffsetDateTime candidate) {
        OffsetDateTime candidateMicros = normalizeTimestamp(candidate);
        if (floorExclusive == null) {
            return candidateMicros;
        }
        OffsetDateTime floorMicros = normalizeTimestamp(floorExclusive);
        return candidateMicros.isAfter(floorMicros) ? candidateMicros : floorMicros.plus(1, ChronoUnit.MICROS);
    }

    public record PersistedTurnResult(
        UUID sessionId,
        UUID assistantMessageId
    ) {
    }

    public record CompletedChatTurnPayload(
        UUID userId,
        UUID auditDepartmentId,
        UUID sessionId,
        String sessionTitle,
        String userQuestion,
        String rewrittenQuery,
        String assistantAnswer,
        List<CitationPayload> citations,
        boolean abstained,
        String modelProvider,
        CacheTier cacheTier,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String ipAddress,
        OffsetDateTime occurredAt,
        boolean persistChatTurn
    ) {
        public CompletedChatTurnPayload {
            citations = citations != null ? List.copyOf(citations) : List.of();
            cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
            occurredAt = occurredAt != null
                ? occurredAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                : OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record ChatFailureAuditPayload(
        UUID userId,
        UUID auditDepartmentId,
        UUID sessionId,
        String queryText,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String provider,
        int statusCode,
        CacheTier cacheTier,
        String ipAddress,
        OffsetDateTime occurredAt
    ) {
        public ChatFailureAuditPayload {
            cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
            occurredAt = occurredAt != null
                ? occurredAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                : OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }

    public record RateLimitAuditPayload(
        UUID userId,
        UUID departmentId,
        String detailText,
        long latencyMs,
        String ipAddress,
        OffsetDateTime occurredAt
    ) {
        public RateLimitAuditPayload {
            occurredAt = occurredAt != null
                ? occurredAt.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
                : OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        }
    }
}
