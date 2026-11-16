package com.documind.infrastructure.vector;

import com.documind.domain.model.CitationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * {@link JdbcClient} repository for RBAC-scoped L2 semantic cache persistence,
 * cosine-similarity lookup, hit accounting, and HNSW {@code EXPLAIN} verification.
 */
@Repository
public class SemanticCacheVectorRepository {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheVectorRepository.class);
    private static final long ASYNC_HIT_FLUSH_WAIT_MS = 50L;
    private static final int MAX_ASYNC_RETRY_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 100L;
    private static final ExecutorService HIT_FLUSH_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final TypeReference<List<CitationPayload>> CITATION_LIST_TYPE = new TypeReference<>() {
    };

    private final Supplier<JdbcClient> jdbcClientSupplier;
    private final Supplier<PlatformTransactionManager> transactionManagerSupplier;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, Integer> pendingHitDeltas = new ConcurrentHashMap<>();
    private final Semaphore flushPermit = new Semaphore(1);
    private volatile boolean shuttingDown;

    @Autowired
    public SemanticCacheVectorRepository(
        ObjectProvider<JdbcClient> jdbcClientProvider,
        ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
        ObjectMapper objectMapper
    ) {
        this(
            () -> Objects.requireNonNull(jdbcClientProvider, "jdbcClientProvider must not be null").getObject(),
            () -> transactionManagerProvider != null ? transactionManagerProvider.getIfAvailable() : null,
            objectMapper
        );
    }

    public SemanticCacheVectorRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, null, objectMapper);
    }

    public SemanticCacheVectorRepository(
        JdbcClient jdbcClient,
        PlatformTransactionManager transactionManager,
        ObjectMapper objectMapper
    ) {
        this(
            () -> Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"),
            () -> transactionManager,
            objectMapper
        );
    }

    private SemanticCacheVectorRepository(
        Supplier<JdbcClient> jdbcClientSupplier,
        Supplier<PlatformTransactionManager> transactionManagerSupplier,
        ObjectMapper objectMapper
    ) {
        this.jdbcClientSupplier = jdbcClientSupplier;
        this.transactionManagerSupplier = transactionManagerSupplier;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Persists or refreshes an RBAC-scoped semantic cache entry idempotently and returns its UUID.
     */
    public UUID save(
        String rbacScope,
        long scopeVersion,
        String queryHash,
        String queryText,
        float[] queryEmbedding,
        String responseContent,
        List<CitationPayload> citations,
        String modelProvider,
        OffsetDateTime expiresAt
    ) {
        validateScopeAndVersion(rbacScope, scopeVersion);
        if (queryHash == null || queryHash.isBlank()) {
            throw new IllegalArgumentException("queryHash must not be blank");
        }
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(responseContent, "responseContent must not be null");

        String vectorLiteral = PgVectorLiterals.toNonZeroFiniteVectorLiteral(queryEmbedding, "queryEmbedding");
        String citationsJson = serializeCitations(citations);

        return jdbcClient().sql("""
                INSERT INTO semantic_cache (
                    rbac_scope,
                    scope_version,
                    query_hash,
                    query_text,
                    query_embedding,
                    response_content,
                    citations,
                    model_provider,
                    expires_at
                )
                VALUES (
                    :rbacScope,
                    :scopeVersion,
                    :queryHash,
                    :queryText,
                    CAST(:vec AS vector),
                    :responseContent,
                    CAST(:citations AS jsonb),
                    :modelProvider,
                    :expiresAt
                )
                ON CONFLICT (rbac_scope, scope_version, query_hash) DO UPDATE
                SET query_text = EXCLUDED.query_text,
                    query_embedding = EXCLUDED.query_embedding,
                    response_content = EXCLUDED.response_content,
                    citations = EXCLUDED.citations,
                    model_provider = EXCLUDED.model_provider,
                    expires_at = EXCLUDED.expires_at,
                    created_at = CURRENT_TIMESTAMP
                RETURNING id
                """)
            .param("rbacScope", rbacScope)
            .param("scopeVersion", scopeVersion)
            .param("queryHash", queryHash)
            .param("queryText", queryText)
            .param("vec", vectorLiteral)
            .param("responseContent", responseContent)
            .param("citations", citationsJson)
            .param("modelProvider", modelProvider, Types.VARCHAR)
            .param("expiresAt", expiresAt, Types.TIMESTAMP_WITH_TIMEZONE)
            .query(UUID.class)
            .single();
    }

    /**
     * Finds the highest-similarity non-expired entry matching {@code rbacScope} and {@code scopeVersion},
     * enabling {@code hnsw.iterative_scan = 'relaxed_order'} and incrementing {@code hit_count} safely
     * even when invoked from a read-only caller transaction.
     */
    public Optional<SemanticCacheEntry> findBestMatch(
        String rbacScope,
        long scopeVersion,
        float[] queryEmbedding,
        double minSimilarity
    ) {
        validateScopeAndVersion(rbacScope, scopeVersion);
        if (!Double.isFinite(minSimilarity)) {
            throw new IllegalArgumentException("minSimilarity must be finite");
        }
        String vectorLiteral = PgVectorLiterals.toNonZeroFiniteVectorLiteral(queryEmbedding, "queryEmbedding");

        PlatformTransactionManager txManager = transactionManagerSupplier.get();
        Optional<SemanticCacheEntry> match;
        if (txManager != null) {
            TransactionTemplate searchTx = new TransactionTemplate(txManager);
            searchTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                searchTx.setReadOnly(true);
            }
            match = searchTx.execute(status -> {
                // Transaction-local setting ensures PgBouncer transaction-pooling compatibility without leaking outside tx
                jdbcClient().sql("SET LOCAL hnsw.iterative_scan = 'relaxed_order'").update();
                return queryBestMatch(rbacScope, scopeVersion, vectorLiteral, minSimilarity);
            });
        } else {
            match = queryBestMatch(rbacScope, scopeVersion, vectorLiteral, minSimilarity);
        }

        if (match != null && match.isPresent()) {
            UUID matchedId = match.get().id();
            incrementHitCount(matchedId, txManager);
        }

        return match != null ? match : Optional.empty();
    }

    /**
     * Runs PostgreSQL {@code EXPLAIN} on an RBAC-scoped cosine-distance lookup against {@code semantic_cache}.
     */
    public List<String> explainCosineSearch(String rbacScope, long scopeVersion, float[] queryVector, int limit) {
        validateScopeAndVersion(rbacScope, scopeVersion);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        String vectorLiteral = PgVectorLiterals.toNonZeroFiniteVectorLiteral(queryVector, "queryVector");

        return jdbcClient().sql("""
                EXPLAIN
                SELECT id
                FROM semantic_cache
                WHERE rbac_scope = :scope
                  AND scope_version = :version
                ORDER BY query_embedding <=> CAST(:vec AS vector)
                LIMIT :limit
                """)
            .param("scope", rbacScope)
            .param("version", scopeVersion)
            .param("vec", vectorLiteral)
            .param("limit", limit)
            .query(String.class)
            .list();
    }

    private Optional<SemanticCacheEntry> queryBestMatch(
        String rbacScope,
        long scopeVersion,
        String vectorLiteral,
        double minSimilarity
    ) {
        return jdbcClient().sql("""
                WITH relaxed_candidates AS MATERIALIZED (
                    SELECT id,
                           rbac_scope,
                           scope_version,
                           query_hash,
                           query_text,
                           response_content,
                           citations::text AS citations_json,
                           model_provider,
                           (query_embedding <=> CAST(:vec AS vector)) AS cosine_distance
                    FROM semantic_cache
                    WHERE rbac_scope = :scope
                      AND scope_version = :version
                      AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                    ORDER BY query_embedding <=> CAST(:vec AS vector)
                    LIMIT 5
                )
                SELECT id,
                       rbac_scope,
                       scope_version,
                       query_hash,
                       query_text,
                       response_content,
                       citations_json,
                       model_provider,
                       (1.0 - cosine_distance) AS similarity_score
                FROM relaxed_candidates
                WHERE (1.0 - cosine_distance) >= :minSimilarity
                ORDER BY cosine_distance ASC
                LIMIT 1
                """)
            .param("scope", rbacScope)
            .param("version", scopeVersion)
            .param("vec", vectorLiteral)
            .param("minSimilarity", minSimilarity)
            .query((rs, rowNum) -> new SemanticCacheEntry(
                rs.getObject("id", UUID.class),
                rs.getString("rbac_scope"),
                rs.getLong("scope_version"),
                rs.getString("query_hash"),
                rs.getString("query_text"),
                rs.getString("response_content"),
                deserializeCitations(rs.getString("citations_json")),
                rs.getString("model_provider"),
                rs.getDouble("similarity_score")
            ))
            .optional();
    }

    private void incrementHitCount(UUID id, PlatformTransactionManager txManager) {
        if (txManager != null
            && TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            pendingHitDeltas.merge(id, 1, Integer::sum);
            CompletableFuture<Void> flushFuture = CompletableFuture.runAsync(
                () -> drainPendingHitDeltas(txManager, 1),
                HIT_FLUSH_EXECUTOR
            );
            try {
                flushFuture.get(ASYNC_HIT_FLUSH_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Pool is busy while caller holds outer read-only connection; delta remains queued and drains once released
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ex) {
                log.warn("Failed to flush semantic cache hit_count asynchronously: {}", ex.getMessage());
            }
            return;
        }
        executeIncrementHitCount(id, 1);
    }

    @PreDestroy
    void flushOnShutdown() {
        shuttingDown = true;
        flushPendingHitCounts();
    }

    /**
     * Completion barrier that acquires {@code flushPermit} at least once so any in-flight
     * async hit-count commit completes before returning, then drains remaining pending deltas.
     */
    void flushPendingHitCounts() {
        PlatformTransactionManager txManager = transactionManagerSupplier.get();
        do {
            try {
                flushPermit.acquire();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            boolean batchSucceeded = false;
            try {
                if (!hasPendingDeltas()) {
                    return;
                }
                batchSucceeded = drainBatch(txManager);
            } finally {
                flushPermit.release();
            }
            if (!batchSucceeded) {
                return;
            }
        } while (hasPendingDeltas());
    }

    private void drainPendingHitDeltas(PlatformTransactionManager txManager, int attempt) {
        while (hasPendingDeltas()) {
            if (!flushPermit.tryAcquire()) {
                return;
            }
            boolean batchSucceeded = false;
            try {
                batchSucceeded = drainBatch(txManager);
            } finally {
                flushPermit.release();
            }
            if (!batchSucceeded) {
                if (!shuttingDown && attempt < MAX_ASYNC_RETRY_ATTEMPTS) {
                    CompletableFuture.delayedExecutor(
                        RETRY_BASE_DELAY_MS * attempt,
                        TimeUnit.MILLISECONDS,
                        HIT_FLUSH_EXECUTOR
                    ).execute(() -> drainPendingHitDeltas(txManager, attempt + 1));
                }
                return;
            }
        }
    }

    private boolean drainBatch(PlatformTransactionManager txManager) {
        Map<UUID, Integer> snapshot = new LinkedHashMap<>();
        for (UUID entryId : List.copyOf(pendingHitDeltas.keySet())) {
            Integer delta = pendingHitDeltas.remove(entryId);
            if (delta != null && delta > 0) {
                snapshot.put(entryId, delta);
            }
        }
        if (snapshot.isEmpty()) {
            return true;
        }
        if (txManager != null) {
            try {
                TransactionTemplate writeTx = new TransactionTemplate(txManager);
                writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                writeTx.setReadOnly(false);
                writeTx.executeWithoutResult(status -> snapshot.forEach(this::executeIncrementHitCount));
                return true;
            } catch (RuntimeException ex) {
                snapshot.forEach((entryId, delta) -> pendingHitDeltas.merge(entryId, delta, Integer::sum));
                log.warn("Deferred semantic cache hit_count flush failed; deltas retained for retry: {}", ex.getMessage());
                return false;
            }
        }
        boolean allSucceeded = true;
        for (Map.Entry<UUID, Integer> entry : snapshot.entrySet()) {
            try {
                executeIncrementHitCount(entry.getKey(), entry.getValue());
            } catch (RuntimeException ex) {
                pendingHitDeltas.merge(entry.getKey(), entry.getValue(), Integer::sum);
                log.warn("Deferred semantic cache hit_count update failed without txManager; delta retained: {}", ex.getMessage());
                allSucceeded = false;
            }
        }
        return allSucceeded;
    }

    private boolean hasPendingDeltas() {
        return !pendingHitDeltas.isEmpty();
    }

    private void executeIncrementHitCount(UUID id, int delta) {
        jdbcClient().sql("""
                UPDATE semantic_cache
                SET hit_count = hit_count + :delta
                WHERE id = :id
                """)
            .param("delta", delta)
            .param("id", id)
            .update();
    }

    private JdbcClient jdbcClient() {
        return jdbcClientSupplier.get();
    }

    private static void validateScopeAndVersion(String rbacScope, long scopeVersion) {
        if (rbacScope == null || rbacScope.isBlank()) {
            throw new IllegalArgumentException("rbacScope must not be blank");
        }
        if (scopeVersion < 1) {
            throw new IllegalArgumentException("scopeVersion must be >= 1");
        }
    }

    private String serializeCitations(List<CitationPayload> citations) {
        List<CitationPayload> safeCitations = citations == null ? List.of() : citations;
        try {
            return objectMapper.writeValueAsString(safeCitations);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize semantic cache citations to JSON", ex);
        }
    }

    private List<CitationPayload> deserializeCitations(String citationsJson) {
        if (citationsJson == null || citationsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(citationsJson, CITATION_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize semantic cache citations from JSON", ex);
        }
    }
}
