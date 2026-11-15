package com.documind.domain.entity;

import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.MessageRole;
import com.documind.domain.model.CitationPayload;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.Hibernate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Individual chat turn storing message text, rewritten standalone query, JSONB citations, and telemetry.
 */
@Entity
@Table(name = "chat_messages")
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSessionEntity session;

    @Column(name = "session_id", nullable = false, insertable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private MessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "rewritten_query", columnDefinition = "TEXT")
    private String rewrittenQuery;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", columnDefinition = "jsonb", nullable = false)
    private List<CitationPayload> citations = new ArrayList<>();

    @Column(name = "abstained", nullable = false)
    private Boolean abstained = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "cache_tier", nullable = false, length = 16)
    private CacheTier cacheTier = CacheTier.NONE;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onPrePersist() {
        if (citations == null) {
            citations = new ArrayList<>();
        }
        if (abstained == null) {
            abstained = Boolean.FALSE;
        }
        if (cacheTier == null) {
            cacheTier = CacheTier.NONE;
        }
        if (promptTokens == null) {
            promptTokens = 0;
        }
        if (completionTokens == null) {
            completionTokens = 0;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ChatSessionEntity getSession() {
        return session;
    }

    public void setSession(ChatSessionEntity session) {
        this.session = session;
        this.sessionId = session != null ? session.getId() : null;
    }

    public UUID getSessionId() {
        return sessionId != null ? sessionId : (session != null ? session.getId() : null);
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRewrittenQuery() {
        return rewrittenQuery;
    }

    public void setRewrittenQuery(String rewrittenQuery) {
        this.rewrittenQuery = rewrittenQuery;
    }

    public List<CitationPayload> getCitations() {
        return citations;
    }

    public void setCitations(List<CitationPayload> citations) {
        this.citations = citations == null ? new ArrayList<>() : new ArrayList<>(citations);
    }

    public Boolean getAbstained() {
        return abstained;
    }

    public void setAbstained(Boolean abstained) {
        this.abstained = abstained;
    }

    public boolean isAbstained() {
        return Boolean.TRUE.equals(abstained);
    }

    public CacheTier getCacheTier() {
        return cacheTier;
    }

    public void setCacheTier(CacheTier cacheTier) {
        this.cacheTier = cacheTier;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        ChatMessageEntity that = (ChatMessageEntity) o;
        return id != null && Objects.equals(id, that.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "ChatMessageEntity{id=" + id + ", role=" + role + ", cacheTier=" + cacheTier + ", abstained=" + abstained + "}";
    }
}
