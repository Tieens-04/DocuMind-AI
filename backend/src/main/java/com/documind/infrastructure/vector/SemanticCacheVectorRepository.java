package com.documind.infrastructure.vector;

import com.documind.domain.model.CitationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link JdbcClient} repository for RBAC-scoped L2 semantic cache persistence,
 * cosine-similarity lookup, hit accounting, and HNSW {@code EXPLAIN} verification.
 */
@Repository
public class SemanticCacheVectorRepository {

    private static final TypeReference<List<CitationPayload>> CITATION_LIST_TYPE = new TypeReference<>() {
    };

    private final Supplier<JdbcClient> jdbcClientSupplier;
    private final ObjectMapper objectMapper;

    @Autowired
    public SemanticCacheVectorRepository(ObjectProvider<JdbcClient> jdbcClientProvider, ObjectMapper objectMapper) {
        this(
            () -> Objects.requireNonNull(jdbcClientProvider, "jdbcClientProvider must not be null").getObject(),
            objectMapper
        );
    }

    public SemanticCacheVectorRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(() -> Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"), objectMapper);
    }

    private SemanticCacheVectorRepository(Supplier<JdbcClient> jdbcClientSupplier, ObjectMapper objectMapper) {
        this.jdbcClientSupplier = jdbcClientSupplier;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Persists a new RBAC-scoped semantic cache entry and returns its generated UUID.
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
     * incrementing {@code hit_count} when a match meets {@code minSimilarity}.
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

        Optional<SemanticCacheEntry> match = jdbcClient().sql("""
                SELECT id,
                       rbac_scope,
                       scope_version,
                       query_hash,
                       query_text,
                       response_content,
                       citations::text AS citations_json,
                       model_provider,
                       (1.0 - (query_embedding <=> CAST(:vec AS vector))) AS similarity_score
                FROM semantic_cache
                WHERE rbac_scope = :scope
                  AND scope_version = :version
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                  AND (1.0 - (query_embedding <=> CAST(:vec AS vector))) >= :minSimilarity
                ORDER BY query_embedding <=> CAST(:vec AS vector)
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

        match.ifPresent(entry -> jdbcClient().sql("""
                UPDATE semantic_cache
                SET hit_count = hit_count + 1
                WHERE id = :id
                """)
            .param("id", entry.id())
            .update());

        return match;
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
