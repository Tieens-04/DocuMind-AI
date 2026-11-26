package com.documind.repository;

import com.documind.domain.enums.CacheTier;
import com.documind.domain.model.CitationPayload;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * JDBC persistence repository executing single-roundtrip atomic Turn-1 chat session,
 * {@code (USER, ASSISTANT)} message pair, and FinOps {@code audit_logs} CTE inserts.
 */
@Repository
public class ChatTurnPersistenceRepository {

    private final Supplier<JdbcClient> jdbcClientSupplier;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatTurnPersistenceRepository(ObjectProvider<JdbcClient> jdbcClientProvider, ObjectMapper objectMapper) {
        this(
            () -> Objects.requireNonNull(jdbcClientProvider, "jdbcClientProvider must not be null").getObject(),
            objectMapper
        );
    }

    public ChatTurnPersistenceRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(() -> Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"), objectMapper);
    }

    private ChatTurnPersistenceRepository(Supplier<JdbcClient> jdbcClientSupplier, ObjectMapper objectMapper) {
        this.jdbcClientSupplier = Objects.requireNonNull(jdbcClientSupplier, "jdbcClientSupplier must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public PersistedNewSessionTurn insertNewSessionTurnAtomically(NewSessionTurnInsert input) {
        Objects.requireNonNull(input, "input must not be null");

        UUID sessionId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantMessageId = UUID.randomUUID();
        UUID auditId = UUID.randomUUID();
        CacheTier effectiveTier = input.cacheTier() != null ? input.cacheTier() : CacheTier.NONE;
        boolean cacheHit = effectiveTier != CacheTier.NONE;

        String citationsJson;
        try {
            citationsJson = objectMapper.writeValueAsString(input.citations());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize chat turn citations", ex);
        }

        jdbcClientSupplier.get().sql("""
                WITH inserted_session AS (
                    INSERT INTO chat_sessions (id, user_id, title, created_at, updated_at)
                    VALUES (:sessionId, :userId, :title, :userCreatedAt, :assistantCreatedAt)
                    RETURNING id
                ),
                inserted_messages AS (
                    INSERT INTO chat_messages (
                        id, session_id, role, content, rewritten_query, citations,
                        abstained, cache_tier, prompt_tokens, completion_tokens,
                        latency_ms, model_provider, created_at
                    )
                    VALUES
                        (:userMsgId, (SELECT id FROM inserted_session), 'USER', :userQuestion, NULL, '[]'::jsonb,
                         FALSE, 'NONE', 0, 0, NULL, NULL, :userCreatedAt),
                        (:assistantMsgId, (SELECT id FROM inserted_session), 'ASSISTANT', :assistantAnswer, :rewrittenQuery, CAST(:citationsJson AS jsonb),
                         :abstained, :cacheTier, :promptTokens, :completionTokens, :latencyMs, :modelProvider, :assistantCreatedAt)
                    RETURNING id
                ),
                inserted_audit AS (
                    INSERT INTO audit_logs (
                        id, user_id, department_id, session_id, action_type, query_text,
                        abstained, cache_hit, cache_tier, prompt_tokens, completion_tokens,
                        latency_ms, provider, status_code, ip_address, created_at
                    )
                    VALUES (
                        :auditId, :userId, :auditDepartmentId, (SELECT id FROM inserted_session), 'CHAT_QUERY', :userQuestion,
                        :abstained, :cacheHit, :cacheTier, :promptTokens, :completionTokens,
                        :latencyMs, :modelProvider, 200, :ipAddress, :auditCreatedAt
                    )
                    RETURNING id
                )
                SELECT (SELECT id FROM inserted_session)
                """)
            .param("sessionId", sessionId)
            .param("userId", input.userId())
            .param("title", input.title())
            .param("userCreatedAt", input.userCreatedAt())
            .param("assistantCreatedAt", input.assistantCreatedAt())
            .param("userMsgId", userMessageId)
            .param("userQuestion", input.userQuestion())
            .param("assistantMsgId", assistantMessageId)
            .param("assistantAnswer", input.assistantAnswer())
            .param("rewrittenQuery", input.rewrittenQuery(), Types.VARCHAR)
            .param("citationsJson", citationsJson)
            .param("abstained", input.abstained())
            .param("cacheTier", effectiveTier.name())
            .param("promptTokens", Math.max(0, input.promptTokens()))
            .param("completionTokens", Math.max(0, input.completionTokens()))
            .param("latencyMs", Math.max(0L, input.latencyMs()))
            .param("modelProvider", input.modelProvider(), Types.VARCHAR)
            .param("auditId", auditId)
            .param("auditDepartmentId", input.auditDepartmentId(), Types.OTHER)
            .param("cacheHit", cacheHit)
            .param("ipAddress", input.ipAddress(), Types.VARCHAR)
            .param("auditCreatedAt", input.auditCreatedAt())
            .query(UUID.class)
            .single();

        return new PersistedNewSessionTurn(sessionId, assistantMessageId);
    }

    public void insertRateLimitAuditAtomically(
        UUID userId,
        UUID departmentId,
        String detailText,
        long latencyMs,
        String ipAddress,
        OffsetDateTime occurredAt
    ) {
        jdbcClientSupplier.get().sql("""
                INSERT INTO audit_logs (
                    id, user_id, department_id, session_id, action_type, query_text,
                    abstained, cache_hit, cache_tier, prompt_tokens, completion_tokens,
                    latency_ms, provider, status_code, ip_address, created_at
                )
                VALUES (
                    :auditId,
                    (SELECT id FROM users WHERE id = :userId),
                    (SELECT id FROM departments WHERE id = :departmentId),
                    NULL,
                    'RATE_LIMITED',
                    :detailText,
                    FALSE,
                    FALSE,
                    'NONE',
                    0,
                    0,
                    :latencyMs,
                    NULL,
                    429,
                    :ipAddress,
                    :occurredAt
                )
                """)
            .param("auditId", UUID.randomUUID())
            .param("userId", userId, Types.OTHER)
            .param("departmentId", departmentId, Types.OTHER)
            .param("detailText", detailText, Types.VARCHAR)
            .param("latencyMs", Math.max(0L, latencyMs))
            .param("ipAddress", ipAddress, Types.VARCHAR)
            .param("occurredAt", occurredAt)
            .update();
    }

    public record NewSessionTurnInsert(
        UUID userId,
        UUID auditDepartmentId,
        String title,
        String userQuestion,
        String rewrittenQuery,
        String assistantAnswer,
        List<CitationPayload> citations,
        boolean abstained,
        CacheTier cacheTier,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String modelProvider,
        String ipAddress,
        OffsetDateTime userCreatedAt,
        OffsetDateTime assistantCreatedAt,
        OffsetDateTime auditCreatedAt
    ) {
        public NewSessionTurnInsert {
            citations = citations != null ? List.copyOf(citations) : List.of();
            cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        }
    }

    public record PersistedNewSessionTurn(
        UUID sessionId,
        UUID assistantMessageId
    ) {
    }
}
