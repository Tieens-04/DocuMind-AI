package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;
import com.documind.domain.entity.ChatMessageEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.model.CitationPayload;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.vector.SemanticCacheEntry;
import com.documind.infrastructure.vector.SemanticCacheVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.auth.RefreshTokenHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Coordinates RBAC-scoped two-tier RAG caching (Redis L1 exact + {@code pgvector} L2 semantic),
 * bounded L2-to-L1 promotion, live citation RBAC/version re-verification with fresh preview URL signing,
 * multi-turn context isolation, and authoritative-DB-backed post-commit monotonic version invalidation.
 */
@Service
public class RagCacheService {

    public static final String L1_KEY_PREFIX = "documind:rag:l1:v1:";
    public static final String L1_CONTEXT_KEY_PREFIX = "documind:rag:l1:ctx:v1:";
    public static final String DEPT_VERSION_KEY_PREFIX = "documind:rag:ver:dept:";
    public static final String GLOBAL_VERSION_KEY = "documind:rag:ver:global";

    private static final Logger log = LoggerFactory.getLogger(RagCacheService.class);
    private static final Pattern WHITESPACE_COLLAPSE = Pattern.compile("[\\s\\u00A0]+");
    private static final String ABSTAINED_MARKER = "[ABSTAINED]";
    private static final DefaultRedisScript<Long> MONOTONIC_MULTI_VERSION_SET_SCRIPT = createMonotonicMultiVersionScript();

    private final Supplier<StringRedisTemplate> redisTemplateSupplier;
    private final Supplier<DepartmentRepository> departmentRepositorySupplier;
    private final Supplier<DocumentRepository> documentRepositorySupplier;
    private final Supplier<SemanticCacheVectorRepository> semanticCacheRepositorySupplier;
    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final DocumentPreviewTokenService previewTokenService;
    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;
    private final Clock clock;

    @Autowired
    public RagCacheService(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        ObjectProvider<DepartmentRepository> departmentRepositoryProvider,
        ObjectProvider<DocumentRepository> documentRepositoryProvider,
        ObjectProvider<SemanticCacheVectorRepository> semanticCacheRepositoryProvider,
        ObjectProvider<EmbeddingModel> embeddingModelProvider,
        DocumentPreviewTokenService previewTokenService,
        ObjectMapper objectMapper,
        RagProperties ragProperties
    ) {
        this(
            Objects.requireNonNull(redisTemplateProvider, "redisTemplateProvider must not be null")::getIfAvailable,
            Objects.requireNonNull(departmentRepositoryProvider, "departmentRepositoryProvider must not be null")::getIfAvailable,
            Objects.requireNonNull(documentRepositoryProvider, "documentRepositoryProvider must not be null")::getIfAvailable,
            Objects.requireNonNull(semanticCacheRepositoryProvider, "semanticCacheRepositoryProvider must not be null")::getIfAvailable,
            Objects.requireNonNull(embeddingModelProvider, "embeddingModelProvider must not be null")::getIfAvailable,
            previewTokenService,
            objectMapper,
            ragProperties,
            Clock.systemUTC()
        );
    }

    public RagCacheService(
        StringRedisTemplate redisTemplate,
        DepartmentRepository departmentRepository,
        DocumentRepository documentRepository,
        SemanticCacheVectorRepository semanticCacheRepository,
        EmbeddingModel embeddingModel,
        DocumentPreviewTokenService previewTokenService,
        ObjectMapper objectMapper,
        RagProperties ragProperties,
        Clock clock
    ) {
        this(
            () -> redisTemplate,
            () -> departmentRepository,
            () -> documentRepository,
            () -> semanticCacheRepository,
            () -> embeddingModel,
            previewTokenService,
            objectMapper,
            ragProperties,
            clock
        );
    }

    private RagCacheService(
        Supplier<StringRedisTemplate> redisTemplateSupplier,
        Supplier<DepartmentRepository> departmentRepositorySupplier,
        Supplier<DocumentRepository> documentRepositorySupplier,
        Supplier<SemanticCacheVectorRepository> semanticCacheRepositorySupplier,
        Supplier<EmbeddingModel> embeddingModelSupplier,
        DocumentPreviewTokenService previewTokenService,
        ObjectMapper objectMapper,
        RagProperties ragProperties,
        Clock clock
    ) {
        this.redisTemplateSupplier = Objects.requireNonNull(redisTemplateSupplier, "redisTemplateSupplier must not be null");
        this.departmentRepositorySupplier = Objects.requireNonNull(departmentRepositorySupplier, "departmentRepositorySupplier must not be null");
        this.documentRepositorySupplier = Objects.requireNonNull(documentRepositorySupplier, "documentRepositorySupplier must not be null");
        this.semanticCacheRepositorySupplier = Objects.requireNonNull(semanticCacheRepositorySupplier, "semanticCacheRepositorySupplier must not be null");
        this.embeddingModelSupplier = Objects.requireNonNull(embeddingModelSupplier, "embeddingModelSupplier must not be null");
        this.previewTokenService = Objects.requireNonNull(previewTokenService, "previewTokenService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.ragProperties = Objects.requireNonNull(ragProperties, "ragProperties must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Resolves the authoritative DB cache version for {@code scope}, checks L1 exact cache in Redis
     * (and L2 semantic cache in {@code pgvector} for standalone turns), verifies live document permissions
     * and versions before replay, and promotes valid L2 hits to L1 bounded by L2 expiry.
     */
    public CacheLookupResult lookup(
        RbacVectorScope scope,
        String normalizedQuestion,
        List<ChatMessageEntity> recentHistory
    ) {
        return lookup(scope, normalizedQuestion, recentHistory, null);
    }

    /**
     * Variant of {@link #lookup(RbacVectorScope, String, List)} accepting an optional authoritative
     * {@code departments.cache_version} already fetched in the caller's request-scoped user query
     * to avoid a redundant DB roundtrip on department-scoped L1 hits.
     */
    public CacheLookupResult lookup(
        RbacVectorScope scope,
        String normalizedQuestion,
        List<ChatMessageEntity> recentHistory,
        Long preloadedCallerDepartmentVersion
    ) {
        L1LookupResult l1 = lookupL1(scope, normalizedQuestion, recentHistory, preloadedCallerDepartmentVersion);
        if (l1.isHit()) {
            return l1.hitResult();
        }
        if (l1.contextual()) {
            return CacheLookupResult.miss(l1.capturedVersion(), null, l1.l1LookupNanos(), 0L, 0L);
        }
        return lookupL2(scope, normalizedQuestion, l1.capturedVersion(), l1.l1LookupNanos());
    }

    /**
     * Performs synchronous L1 Redis exact/contextual lookup with TOCTOU version reconciliation against
     * PostgreSQL when Redis mirrors a higher version than {@code preloadedCallerDepartmentVersion}.
     */
    public L1LookupResult lookupL1(
        RbacVectorScope scope,
        String normalizedQuestion,
        List<ChatMessageEntity> recentHistory,
        Long preloadedCallerDepartmentVersion
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(normalizedQuestion, "normalizedQuestion must not be null");

        boolean contextual = recentHistory != null && !recentHistory.isEmpty();
        if (!ragProperties.cache().enabled() || departmentRepositorySupplier.get() == null) {
            return new L1LookupResult(null, 1L, 0L, contextual);
        }

        long capturedVersion = resolveAuthoritativeVersion(scope, preloadedCallerDepartmentVersion);
        String scopeKey = scope.cacheScopeKey();
        String queryHash = contextual
            ? computeContextualQueryHash(normalizedQuestion, recentHistory)
            : hashStandaloneQuery(normalizedQuestion);
        String l1Key = buildL1Key(contextual, scopeKey, capturedVersion, queryHash);
        String versionKey = resolveVersionKey(scope);

        long l1StartedNanos = System.nanoTime();
        L1FetchResult l1Fetch = fetchL1AndMirror(versionKey, l1Key, capturedVersion);
        if (l1Fetch != null && l1Fetch.mirroredVersion() != null && l1Fetch.mirroredVersion() > capturedVersion) {
            long liveDbVersion = readAuthoritativeVersion(scope);
            if (liveDbVersion != capturedVersion) {
                capturedVersion = liveDbVersion;
                l1Key = buildL1Key(contextual, scopeKey, capturedVersion, queryHash);
                l1Fetch = fetchL1AndMirror(versionKey, l1Key, capturedVersion);
            }
        }
        long l1Nanos = Math.max(0L, System.nanoTime() - l1StartedNanos);

        String rawL1Json = l1Fetch != null ? l1Fetch.rawJson() : null;
        if (rawL1Json != null) {
            Optional<CachedResponsePayload> decoded = deserializePayload(rawL1Json);
            if (decoded.isPresent()) {
                CachedResponsePayload payload = decoded.get();
                Optional<List<CitationDto>> hydrated = hydrateAndVerifyCitations(payload.citations(), scope);
                if (hydrated.isPresent()) {
                    return new L1LookupResult(
                        CacheLookupResult.hit(
                            CacheTier.L1,
                            capturedVersion,
                            payload.answer(),
                            payload.rewrittenQuery(),
                            hydrated.get(),
                            payload.modelProvider(),
                            null,
                            l1Nanos,
                            0L,
                            0L
                        ),
                        capturedVersion,
                        l1Nanos,
                        contextual
                    );
                }
            }
        }
        return new L1LookupResult(null, capturedVersion, l1Nanos, contextual);
    }

    /**
     * Executes standalone L2 {@code pgvector} semantic lookup, re-verifies DB version & live citations
     * before accounting the hit or promoting the answer to L1 bounded by remaining L2 lifetime.
     */
    public CacheLookupResult lookupL2(
        RbacVectorScope scope,
        String normalizedQuestion,
        long capturedVersion,
        long l1Nanos
    ) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(normalizedQuestion, "normalizedQuestion must not be null");

        if (!ragProperties.cache().enabled() || departmentRepositorySupplier.get() == null) {
            return CacheLookupResult.miss(capturedVersion, null, l1Nanos, 0L, 0L);
        }

        SemanticCacheVectorRepository l2Repo = semanticCacheRepositorySupplier.get();
        EmbeddingModel embeddingModel = embeddingModelSupplier.get();
        if (l2Repo == null || embeddingModel == null || Thread.currentThread().isInterrupted()) {
            return CacheLookupResult.miss(capturedVersion, null, l1Nanos, 0L, 0L);
        }

        float[] queryEmbedding;
        long embedStartedNanos = System.nanoTime();
        try {
            queryEmbedding = embeddingModel.embed(normalizedQuestion);
        } catch (RuntimeException ex) {
            if (Thread.currentThread().isInterrupted()) {
                return CacheLookupResult.miss(capturedVersion, null, l1Nanos, Math.max(0L, System.nanoTime() - embedStartedNanos), 0L);
            }
            log.warn("Embedding failed during L2 semantic cache lookup; falling back to RAG retrieval: {}", ex.getMessage());
            return CacheLookupResult.miss(capturedVersion, null, l1Nanos, Math.max(0L, System.nanoTime() - embedStartedNanos), 0L);
        }
        long embeddingNanos = Math.max(0L, System.nanoTime() - embedStartedNanos);
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            return CacheLookupResult.miss(capturedVersion, null, l1Nanos, embeddingNanos, 0L);
        }

        String scopeKey = scope.cacheScopeKey();
        String versionKey = resolveVersionKey(scope);
        double threshold = ragProperties.cache().semanticSimilarityThreshold();
        long l2StartedNanos = System.nanoTime();
        Optional<SemanticCacheEntry> l2Match;
        try {
            l2Match = l2Repo.findBestMatchWithoutIncrement(scopeKey, capturedVersion, queryEmbedding, threshold);
        } catch (RuntimeException ex) {
            log.warn("L2 semantic cache query failed; falling back to RAG retrieval: {}", ex.getMessage());
            return CacheLookupResult.miss(capturedVersion, queryEmbedding, l1Nanos, embeddingNanos, Math.max(0L, System.nanoTime() - l2StartedNanos));
        }
        long l2Nanos = Math.max(0L, System.nanoTime() - l2StartedNanos);

        if (l2Match.isEmpty()) {
            return CacheLookupResult.miss(capturedVersion, queryEmbedding, l1Nanos, embeddingNanos, l2Nanos);
        }

        SemanticCacheEntry entry = l2Match.get();
        if (entry.responseContent() == null || entry.responseContent().isBlank() || entry.citations().isEmpty()) {
            return CacheLookupResult.miss(capturedVersion, queryEmbedding, l1Nanos, embeddingNanos, l2Nanos);
        }

        Duration promotionTtl = computePromotionTtl(entry.expiresAt());
        if (promotionTtl == null) {
            return CacheLookupResult.miss(capturedVersion, queryEmbedding, l1Nanos, embeddingNanos, l2Nanos);
        }

        long latestVersion = readAuthoritativeVersion(scope);
        if (latestVersion != capturedVersion) {
            syncVersionKeyMonotonic(versionKey, latestVersion);
            return CacheLookupResult.miss(latestVersion, queryEmbedding, l1Nanos, embeddingNanos, l2Nanos);
        }

        Optional<List<CitationDto>> hydrated = hydrateAndVerifyCitations(entry.citations(), scope);
        if (hydrated.isEmpty()) {
            return CacheLookupResult.miss(capturedVersion, queryEmbedding, l1Nanos, embeddingNanos, l2Nanos);
        }

        try {
            l2Repo.recordHit(entry.id());
        } catch (RuntimeException ex) {
            log.warn("Failed to record L2 semantic cache hit_count for {}: {}", entry.id(), ex.getMessage());
        }

        String l1Key = buildStandaloneL1Key(scopeKey, capturedVersion, hashStandaloneQuery(normalizedQuestion));
        CachedResponsePayload promotedPayload = new CachedResponsePayload(
            entry.responseContent(),
            null,
            entry.citations(),
            entry.modelProvider()
        );
        writeL1Quietly(l1Key, promotedPayload, promotionTtl);

        return CacheLookupResult.hit(
            CacheTier.L2,
            capturedVersion,
            entry.responseContent(),
            null,
            hydrated.get(),
            entry.modelProvider(),
            queryEmbedding,
            l1Nanos,
            embeddingNanos,
            l2Nanos
        );
    }

    /**
     * Publishes a completed, grounded, cited response into cache pinned strictly to {@code capturedVersion}
     * after verifying the authoritative DB scope version has not advanced during generation.
     */
    public void publishIfCurrent(
        RbacVectorScope scope,
        long capturedVersion,
        String normalizedQuestion,
        String rewrittenQuery,
        List<ChatMessageEntity> recentHistory,
        float[] existingQueryEmbedding,
        String finalAnswer,
        List<CitationDto> finalCitations,
        boolean abstained,
        String modelProvider
    ) {
        if (!ragProperties.cache().enabled()
            || scope == null
            || capturedVersion < 1L
            || abstained
            || normalizedQuestion == null
            || normalizedQuestion.isBlank()
            || finalAnswer == null
            || finalAnswer.isBlank()
            || finalCitations == null
            || finalCitations.isEmpty()
            || departmentRepositorySupplier.get() == null) {
            return;
        }

        try {
            long liveVersion = readAuthoritativeVersion(scope);
            if (liveVersion != capturedVersion) {
                syncVersionKeyMonotonic(resolveVersionKey(scope), liveVersion);
                log.debug(
                    "Skipping stale cache publication for scope={} (capturedVersion={}, liveVersion={})",
                    scope.cacheScopeKey(),
                    capturedVersion,
                    liveVersion
                );
                return;
            }

            List<CitationPayload> citationPayloads = new ArrayList<>(finalCitations.size());
            for (CitationDto dto : finalCitations) {
                if (dto == null || dto.documentId() == null || dto.docVersion() == null || dto.docVersion() < 1) {
                    return;
                }
                citationPayloads.add(dto.toCitationPayload());
            }

            boolean contextual = recentHistory != null && !recentHistory.isEmpty();
            String scopeKey = scope.cacheScopeKey();
            Duration l1Ttl = ragProperties.cache().l1Ttl();

            if (contextual) {
                String ctxHash = computeContextualQueryHash(normalizedQuestion, recentHistory);
                String ctxKey = buildContextualL1Key(scopeKey, capturedVersion, ctxHash);
                writeL1Quietly(ctxKey, new CachedResponsePayload(finalAnswer, rewrittenQuery, citationPayloads, modelProvider), l1Ttl);
                syncVersionKeyMonotonic(resolveVersionKey(scope), capturedVersion);
                return;
            }

            String queryHash = hashStandaloneQuery(normalizedQuestion);
            String l1Key = buildStandaloneL1Key(scopeKey, capturedVersion, queryHash);
            CachedResponsePayload payload = new CachedResponsePayload(finalAnswer, null, citationPayloads, modelProvider);

            SemanticCacheVectorRepository l2Repo = semanticCacheRepositorySupplier.get();
            if (l2Repo != null) {
                float[] vectorToStore = existingQueryEmbedding;
                if (vectorToStore == null || vectorToStore.length == 0) {
                    EmbeddingModel embeddingModel = embeddingModelSupplier.get();
                    if (embeddingModel != null) {
                        try {
                            vectorToStore = embeddingModel.embed(normalizedQuestion);
                        } catch (RuntimeException ex) {
                            log.warn("Skipping L2 cache write because embedding failed: {}", ex.getMessage());
                        }
                    }
                }
                if (vectorToStore != null && vectorToStore.length > 0) {
                    OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                        clock.instant().plus(ragProperties.cache().l2Ttl()),
                        ZoneOffset.UTC
                    );
                    try {
                        l2Repo.save(
                            scopeKey,
                            capturedVersion,
                            queryHash,
                            normalizedQuestion,
                            vectorToStore,
                            finalAnswer,
                            citationPayloads,
                            modelProvider,
                            expiresAt
                        );
                    } catch (RuntimeException ex) {
                        log.warn("Failed to persist L2 semantic cache entry for scope={}: {}", scopeKey, ex.getMessage());
                    }
                }
            }

            writeL1Quietly(l1Key, payload, l1Ttl);
            syncVersionKeyMonotonic(resolveVersionKey(scope), capturedVersion);
        } catch (RuntimeException ex) {
            log.warn("Best-effort RAG cache publish failed for scope={}: {}", scope.cacheScopeKey(), ex.getMessage());
        }
    }

    private long resolveAuthoritativeVersion(RbacVectorScope scope, Long preloadedCallerDepartmentVersion) {
        if (preloadedCallerDepartmentVersion != null
            && preloadedCallerDepartmentVersion >= 1L
            && !isGlobalAdminScope(scope)
            && scope.caller() != null
            && Objects.equals(resolveTargetDepartmentId(scope), scope.caller().departmentId())) {
            return preloadedCallerDepartmentVersion;
        }
        return readAuthoritativeVersion(scope);
    }

    /**
     * Reads the authoritative cache version directly from PostgreSQL ({@code departments.cache_version}
     * for department-scoped callers and {@code SUM(cache_version)} for global {@code SYSTEM_ADMIN}).
     */
    public long readAuthoritativeVersion(RbacVectorScope scope) {
        Objects.requireNonNull(scope, "scope must not be null");
        DepartmentRepository deptRepo = departmentRepositorySupplier.get();
        if (deptRepo == null) {
            return 1L;
        }
        if (isGlobalAdminScope(scope)) {
            return Math.max(1L, deptRepo.sumCacheVersions());
        }
        UUID targetDeptId = resolveTargetDepartmentId(scope);
        if (targetDeptId == null) {
            return 1L;
        }
        return deptRepo.findCacheVersionById(targetDeptId)
            .map(v -> Math.max(1L, v))
            .orElse(1L);
    }

    /**
     * Schedules monotonic Redis mirror sync strictly in {@link TransactionSynchronization#afterCommit()}
     * for the given affected department(s) and the global admin scope. Skips automatically on rollback.
     */
    public void schedulePostCommitSyncForDepartments(Collection<UUID> departmentIds) {
        if (departmentIds == null || departmentIds.isEmpty()) {
            return;
        }
        Set<UUID> uniqueDeptIds = departmentIds.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueDeptIds.isEmpty()) {
            return;
        }
        runAfterCommitOrNow(() -> syncDepartmentsFromDb(uniqueDeptIds));
    }

    /**
     * Schedules monotonic Redis mirror sync strictly in {@link TransactionSynchronization#afterCommit()}
     * for every department and the global admin scope (used when a {@code PUBLIC} document changes).
     */
    public void schedulePostCommitSyncForAllDepartments() {
        runAfterCommitOrNow(this::syncAllDepartmentsFromDb);
    }

    /**
     * Schedules post-commit deletion of a retired department's Redis version key after department deletion.
     */
    public void schedulePostCommitDeleteDepartmentVersionKey(UUID departmentId) {
        if (departmentId == null) {
            return;
        }
        String versionKey = buildDepartmentVersionKey(departmentId);
        runAfterCommitOrNow(() -> deleteKeyQuietly(versionKey));
    }

    /**
     * Atomically advances a Redis version mirror key only if missing or strictly higher than the stored value,
     * preventing delayed/out-of-order callbacks from rolling a version backwards.
     */
    public void syncVersionKeyMonotonic(String redisKey, long incomingVersion) {
        if (redisKey == null || redisKey.isBlank() || incomingVersion < 1L) {
            return;
        }
        syncVersionKeysMonotonic(Map.of(redisKey, incomingVersion));
    }

    /**
     * Atomically advances a batch of Redis version mirror keys in a single Lua script roundtrip,
     * preventing both version regression and connection-pool pinning under multi-department sync.
     */
    public void syncVersionKeysMonotonic(Map<String, Long> keyVersions) {
        if (keyVersions == null || keyVersions.isEmpty()) {
            return;
        }
        StringRedisTemplate redis = redisTemplateSupplier.get();
        if (redis == null) {
            return;
        }
        List<String> keys = new ArrayList<>(keyVersions.size());
        List<String> versions = new ArrayList<>(keyVersions.size());
        for (Map.Entry<String, Long> entry : keyVersions.entrySet()) {
            String key = entry.getKey();
            Long incoming = entry.getValue();
            if (key != null && !key.isBlank() && incoming != null && incoming >= 1L) {
                keys.add(key);
                versions.add(String.valueOf(incoming));
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        long ttlMillis = Math.max(1L, ragProperties.cache().versionTtl().toMillis());
        String[] args = new String[versions.size() + 1];
        args[0] = String.valueOf(ttlMillis);
        for (int i = 0; i < versions.size(); i++) {
            args[i + 1] = versions.get(i);
        }
        try {
            redis.execute(MONOTONIC_MULTI_VERSION_SET_SCRIPT, keys, (Object[]) args);
        } catch (RuntimeException ex) {
            log.warn("Failed to sync {} Redis cache version key(s): {}", keys.size(), ex.getMessage());
        }
    }

    public String computeContextualQueryHash(String normalizedQuestion, List<ChatMessageEntity> recentHistory) {
        String normalizedCurrent = normalizeQueryForCache(normalizedQuestion);
        int windowSize = ragProperties.memory().historyWindowSize();
        int maxChars = ragProperties.memory().maxHistoryMessageChars();
        int startIndex = Math.max(0, recentHistory.size() - windowSize);

        StringBuilder canonical = new StringBuilder(512);
        canonical.append("ctx-v1");
        for (int i = startIndex; i < recentHistory.size(); i++) {
            ChatMessageEntity turn = recentHistory.get(i);
            if (turn == null) {
                continue;
            }
            MessageRole role = turn.getRole() != null ? turn.getRole() : MessageRole.USER;
            boolean abstained = turn.isAbstained();
            String content = (role == MessageRole.ASSISTANT && abstained)
                ? ABSTAINED_MARKER
                : normalizeQueryForCache(QueryContextualizerService.truncateSafe(
                    turn.getContent() != null ? turn.getContent() : "",
                    maxChars
                ));
            canonical.append('\n')
                .append(role.name())
                .append('|')
                .append(abstained ? '1' : '0')
                .append('|')
                .append(content.length())
                .append(':')
                .append(content);
        }
        canonical.append("\nQ|").append(normalizedCurrent.length()).append(':').append(normalizedCurrent);
        return sha256Hex(canonical.toString());
    }

    /**
     * Normalizes a query with Unicode NFC, whitespace collapsing, and root lowercasing while preserving
     * Vietnamese diacritics, numbers, punctuation, and negation words.
     */
    public static String normalizeQueryForCache(String rawQuestion) {
        if (rawQuestion == null) {
            return "";
        }
        String cleaned = rawQuestion
            .replace("\0", "")
            .replace("\uFEFF", "")
            .replace("\u00AD", "");
        if (!Normalizer.isNormalized(cleaned, Normalizer.Form.NFC)) {
            cleaned = Normalizer.normalize(cleaned, Normalizer.Form.NFC);
        }
        return WHITESPACE_COLLAPSE.matcher(cleaned)
            .replaceAll(" ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    public static String hashStandaloneQuery(String rawQuestion) {
        return sha256Hex(normalizeQueryForCache(rawQuestion));
    }

    public static String sha256Hex(String text) {
        return RefreshTokenHasher.sha256Hex(Objects.requireNonNullElse(text, ""));
    }

    private static String buildL1Key(boolean contextual, String scopeKey, long scopeVersion, String hash) {
        return contextual
            ? buildContextualL1Key(scopeKey, scopeVersion, hash)
            : buildStandaloneL1Key(scopeKey, scopeVersion, hash);
    }

    public static String buildStandaloneL1Key(String rbacScopeKey, long scopeVersion, String queryHash) {
        return L1_KEY_PREFIX + rbacScopeKey + ":ver:" + scopeVersion + ":" + queryHash;
    }

    public static String buildContextualL1Key(String rbacScopeKey, long scopeVersion, String contextHash) {
        return L1_CONTEXT_KEY_PREFIX + rbacScopeKey + ":ver:" + scopeVersion + ":" + contextHash;
    }

    public static String buildDepartmentVersionKey(UUID departmentId) {
        return DEPT_VERSION_KEY_PREFIX + Objects.requireNonNull(departmentId, "departmentId must not be null");
    }

    private void syncDepartmentsFromDb(Set<UUID> departmentIds) {
        DepartmentRepository deptRepo = departmentRepositorySupplier.get();
        if (deptRepo == null) {
            return;
        }
        try {
            Map<String, Long> updates = new LinkedHashMap<>();
            if (departmentIds.size() == 1) {
                UUID deptId = departmentIds.iterator().next();
                deptRepo.findCacheVersionById(deptId)
                    .ifPresent(version -> updates.put(buildDepartmentVersionKey(deptId), Math.max(1L, version)));
            } else {
                List<DepartmentRepository.DepartmentCacheVersionProjection> projections =
                    deptRepo.findCacheVersionsByIdIn(departmentIds);
                for (DepartmentRepository.DepartmentCacheVersionProjection projection : projections) {
                    if (projection.getId() != null && projection.getCacheVersion() != null) {
                        updates.put(
                            buildDepartmentVersionKey(projection.getId()),
                            Math.max(1L, projection.getCacheVersion())
                        );
                    }
                }
            }
            updates.put(GLOBAL_VERSION_KEY, Math.max(1L, deptRepo.sumCacheVersions()));
            syncVersionKeysMonotonic(updates);
        } catch (RuntimeException ex) {
            log.warn("Post-commit department cache version sync failed (DB remains authoritative): {}", ex.getMessage());
        }
    }

    private void syncAllDepartmentsFromDb() {
        DepartmentRepository deptRepo = departmentRepositorySupplier.get();
        if (deptRepo == null) {
            return;
        }
        try {
            List<DepartmentRepository.DepartmentCacheVersionProjection> all = deptRepo.findAllCacheVersions();
            Map<String, Long> updates = new LinkedHashMap<>(all.size() + 1);
            long sum = 0L;
            for (DepartmentRepository.DepartmentCacheVersionProjection projection : all) {
                if (projection.getId() != null && projection.getCacheVersion() != null) {
                    long version = Math.max(1L, projection.getCacheVersion());
                    sum += version;
                    updates.put(buildDepartmentVersionKey(projection.getId()), version);
                }
            }
            updates.put(GLOBAL_VERSION_KEY, Math.max(1L, sum));
            syncVersionKeysMonotonic(updates);
        } catch (RuntimeException ex) {
            log.warn("Post-commit global cache version sync failed (DB remains authoritative): {}", ex.getMessage());
        }
    }

    private void deleteKeyQuietly(String key) {
        StringRedisTemplate redis = redisTemplateSupplier.get();
        if (redis == null || key == null || key.isBlank()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete retired Redis cache version key {}: {}", key, ex.getMessage());
        }
    }

    private void runAfterCommitOrNow(Runnable syncAction) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    syncAction.run();
                }
            });
            return;
        }
        syncAction.run();
    }

    private L1FetchResult fetchL1AndMirror(String versionKey, String l1Key, long authoritativeVersion) {
        StringRedisTemplate redis = redisTemplateSupplier.get();
        if (redis == null) {
            return null;
        }
        try {
            List<String> values = redis.opsForValue().multiGet(List.of(versionKey, l1Key));
            if (values == null || values.size() < 2) {
                syncVersionKeyMonotonic(versionKey, authoritativeVersion);
                return null;
            }
            Long mirroredVersion = parseOptionalLong(values.get(0));
            if (mirroredVersion == null || mirroredVersion < authoritativeVersion) {
                syncVersionKeyMonotonic(versionKey, authoritativeVersion);
            }
            return new L1FetchResult(values.get(1), mirroredVersion);
        } catch (RuntimeException ex) {
            log.warn("Redis L1 cache lookup failed; falling back to L2/RAG: {}", ex.getMessage());
            return null;
        }
    }

    private void writeL1Quietly(String l1Key, CachedResponsePayload payload, Duration ttl) {
        StringRedisTemplate redis = redisTemplateSupplier.get();
        if (redis == null || ttl == null || ttl.isNegative() || ttl.toMillis() <= 0L) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(l1Key, json, ttl);
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn("Failed to write Redis L1 cache key {}: {}", l1Key, ex.getMessage());
        }
    }

    private Optional<CachedResponsePayload> deserializePayload(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Optional.empty();
        }
        try {
            CachedResponsePayload payload = objectMapper.readValue(rawJson, CachedResponsePayload.class);
            if (payload == null
                || payload.answer() == null
                || payload.answer().isBlank()
                || payload.citations().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Discarding corrupt L1 cache JSON entry: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<List<CitationDto>> hydrateAndVerifyCitations(
        List<CitationPayload> storedCitations,
        RbacVectorScope scope
    ) {
        if (storedCitations == null || storedCitations.isEmpty()) {
            return Optional.empty();
        }
        DocumentRepository docRepo = documentRepositorySupplier.get();
        if (docRepo == null) {
            return Optional.empty();
        }

        Set<UUID> docIds = new LinkedHashSet<>();
        for (CitationPayload payload : storedCitations) {
            if (payload == null
                || payload.documentId() == null
                || payload.docVersion() == null
                || payload.docVersion() < 1) {
                return Optional.empty();
            }
            docIds.add(payload.documentId());
        }

        Map<UUID, DocumentEntity> parentsById = docRepo.findAllById(docIds).stream()
            .collect(Collectors.toMap(DocumentEntity::getId, Function.identity(), (a, b) -> a));

        Map<UUID, Optional<String>> presignedUrlMemo = new HashMap<>();
        List<CitationDto> hydrated = new ArrayList<>(storedCitations.size());
        for (CitationPayload payload : storedCitations) {
            DocumentEntity parent = parentsById.get(payload.documentId());
            if (!isLiveCitationAuthorized(parent, payload, scope)) {
                return Optional.empty();
            }
            String baseUrl = presignedUrlMemo.computeIfAbsent(
                parent.getId(),
                id -> DocumentPreviewTokenService.tryIssuePreviewBaseUrl(
                    previewTokenService,
                    parent.getId(),
                    parent.getCurrentVersion(),
                    scope.caller(),
                    log
                )
            ).orElse(null);
            Integer effectivePage = parent.isPdf() ? payload.pageNumber() : null;
            String citationUrl = MinioStorageService.appendPageFragment(baseUrl, effectivePage);
            hydrated.add(new CitationDto(
                parent.getId(),
                parent.getCurrentVersion(),
                parent.getOriginalName(),
                effectivePage,
                payload.sectionHeading(),
                payload.similarityScore(),
                payload.excerpt(),
                citationUrl
            ));
        }
        return Optional.of(List.copyOf(hydrated));
    }

    private static boolean isLiveCitationAuthorized(
        DocumentEntity parent,
        CitationPayload payload,
        RbacVectorScope scope
    ) {
        return parent != null
            && parent.getStatus() == DocumentStatus.COMPLETED
            && Objects.equals(parent.getCurrentVersion(), payload.docVersion())
            && Objects.equals(parent.getOriginalName(), payload.fileName())
            && scope.allows(parent.getDepartmentId(), parent.getAccessLevel());
    }

    private Duration computePromotionTtl(OffsetDateTime l2ExpiresAt) {
        Duration l1Ttl = ragProperties.cache().l1Ttl();
        if (l2ExpiresAt == null) {
            return l1Ttl;
        }
        Duration remaining = Duration.between(clock.instant(), l2ExpiresAt.toInstant());
        if (remaining.isNegative() || remaining.toMillis() <= 0L) {
            return null;
        }
        return remaining.compareTo(l1Ttl) < 0 ? remaining : l1Ttl;
    }

    private static boolean isGlobalAdminScope(RbacVectorScope scope) {
        return scope.caller().isSystemAdmin() && scope.filterDepartmentId() == null;
    }

    private static UUID resolveTargetDepartmentId(RbacVectorScope scope) {
        return scope.filterDepartmentId() != null
            ? scope.filterDepartmentId()
            : scope.caller().departmentId();
    }

    private static String resolveVersionKey(RbacVectorScope scope) {
        if (isGlobalAdminScope(scope)) {
            return GLOBAL_VERSION_KEY;
        }
        return buildDepartmentVersionKey(resolveTargetDepartmentId(scope));
    }

    private static Long parseOptionalLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static DefaultRedisScript<Long> createMonotonicMultiVersionScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
            local ttlMs = tonumber(ARGV[1])
            if ttlMs == nil or ttlMs <= 0 then
                return 0
            end
            local updated = 0
            for i = 1, #KEYS do
                local incoming = tonumber(ARGV[i + 1])
                if incoming ~= nil and incoming >= 1 then
                    local current = tonumber(redis.call('GET', KEYS[i]))
                    if current == nil or incoming > current then
                        redis.call('SET', KEYS[i], tostring(incoming), 'PX', ttlMs)
                        updated = updated + 1
                    end
                end
            end
            return updated
            """);
        return script;
    }

    private record L1FetchResult(
        String rawJson,
        Long mirroredVersion
    ) {
    }

    public record L1LookupResult(
        CacheLookupResult hitResult,
        long capturedVersion,
        long l1LookupNanos,
        boolean contextual
    ) {
        public boolean isHit() {
            return hitResult != null && hitResult.isHit();
        }
    }

    public record CachedResponsePayload(
        String answer,
        String rewrittenQuery,
        List<CitationPayload> citations,
        String modelProvider
    ) {
        public CachedResponsePayload {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    public record CacheLookupResult(
        CacheTier cacheTier,
        long capturedVersion,
        String answer,
        String rewrittenQuery,
        List<CitationDto> citations,
        String modelProvider,
        float[] queryEmbedding,
        long l1LookupNanos,
        long embeddingNanos,
        long l2LookupNanos
    ) {
        public CacheLookupResult {
            cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
            citations = citations != null ? List.copyOf(citations) : List.of();
        }

        public boolean isHit() {
            return cacheTier != CacheTier.NONE;
        }

        public static CacheLookupResult hit(
            CacheTier tier,
            long capturedVersion,
            String answer,
            String rewrittenQuery,
            List<CitationDto> citations,
            String modelProvider,
            float[] queryEmbedding,
            long l1LookupNanos,
            long embeddingNanos,
            long l2LookupNanos
        ) {
            return new CacheLookupResult(
                tier,
                capturedVersion,
                answer,
                rewrittenQuery,
                citations,
                modelProvider,
                queryEmbedding,
                l1LookupNanos,
                embeddingNanos,
                l2LookupNanos
            );
        }

        public static CacheLookupResult miss(
            long capturedVersion,
            float[] queryEmbedding,
            long l1LookupNanos,
            long embeddingNanos,
            long l2LookupNanos
        ) {
            return new CacheLookupResult(
                CacheTier.NONE,
                capturedVersion,
                null,
                null,
                List.of(),
                null,
                queryEmbedding,
                l1LookupNanos,
                embeddingNanos,
                l2LookupNanos
            );
        }
    }
}
