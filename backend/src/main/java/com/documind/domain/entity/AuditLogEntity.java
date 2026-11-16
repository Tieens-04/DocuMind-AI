package com.documind.domain.entity;

import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit and cost-governance event log across RAG queries, document operations, auth, and rate limits.
 */
@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLogEntity implements Persistable<UUID> {

    @Id
    @AssignedOrRandomUuid
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    private UserEntity user;

    @Column(name = "user_id", insertable = false, updatable = false)
    private UUID userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", updatable = false)
    private DepartmentEntity department;

    @Column(name = "department_id", insertable = false, updatable = false)
    private UUID departmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", updatable = false)
    private ChatSessionEntity session;

    @Column(name = "session_id", insertable = false, updatable = false)
    private UUID sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false, length = 40)
    private AuditActionType actionType;

    @Column(name = "query_text", updatable = false, columnDefinition = "TEXT")
    private String queryText;

    @Column(name = "abstained", nullable = false, updatable = false)
    private Boolean abstained = Boolean.FALSE;

    @Column(name = "cache_hit", nullable = false, updatable = false)
    private Boolean cacheHit = Boolean.FALSE;

    @Enumerated(EnumType.STRING)
    @Column(name = "cache_tier", nullable = false, updatable = false, length = 16)
    private CacheTier cacheTier = CacheTier.NONE;

    @Column(name = "prompt_tokens", nullable = false, updatable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false, updatable = false)
    private Integer completionTokens = 0;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private Long latencyMs = 0L;

    @Column(name = "provider", updatable = false, length = 64)
    private String provider;

    @Column(name = "status_code", nullable = false, updatable = false)
    private Integer statusCode = 200;

    @Column(name = "ip_address", updatable = false, length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onPrePersist() {
        if (abstained == null) {
            abstained = Boolean.FALSE;
        }
        if (cacheTier == null) {
            cacheTier = CacheTier.NONE;
        }
        cacheHit = (cacheTier != CacheTier.NONE);
        if (promptTokens == null) {
            promptTokens = 0;
        }
        if (completionTokens == null) {
            completionTokens = 0;
        }
        if (latencyMs == null) {
            latencyMs = 0L;
        }
        if (statusCode == null) {
            statusCode = 200;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UserEntity getUser() {
        return user;
    }

    public void setUser(UserEntity user) {
        this.user = user;
        this.userId = JpaProxyUtils.extractId(user);
    }

    public UUID getUserId() {
        return userId != null ? userId : JpaProxyUtils.extractId(user);
    }

    public DepartmentEntity getDepartment() {
        return department;
    }

    public void setDepartment(DepartmentEntity department) {
        this.department = department;
        this.departmentId = JpaProxyUtils.extractId(department);
    }

    public UUID getDepartmentId() {
        return departmentId != null ? departmentId : JpaProxyUtils.extractId(department);
    }

    public ChatSessionEntity getSession() {
        return session;
    }

    public void setSession(ChatSessionEntity session) {
        this.session = session;
        this.sessionId = JpaProxyUtils.extractId(session);
    }

    public UUID getSessionId() {
        return sessionId != null ? sessionId : JpaProxyUtils.extractId(session);
    }

    public AuditActionType getActionType() {
        return actionType;
    }

    public void setActionType(AuditActionType actionType) {
        this.actionType = actionType;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
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

    public Boolean getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public boolean isCacheHit() {
        return Boolean.TRUE.equals(cacheHit);
    }

    public CacheTier getCacheTier() {
        return cacheTier;
    }

    public void setCacheTier(CacheTier cacheTier) {
        this.cacheTier = cacheTier;
        this.cacheHit = (cacheTier != null && cacheTier != CacheTier.NONE);
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

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
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
        if (o == null || JpaProxyUtils.effectiveClass(this) != JpaProxyUtils.effectiveClass(o)) {
            return false;
        }
        AuditLogEntity that = (AuditLogEntity) o;
        UUID thisId = JpaProxyUtils.extractId(this);
        UUID thatId = JpaProxyUtils.extractId(that);
        return thisId != null && Objects.equals(thisId, thatId);
    }

    @Override
    public int hashCode() {
        return JpaProxyUtils.effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "AuditLogEntity{id=" + id + ", actionType=" + actionType + ", cacheTier=" + cacheTier + ", statusCode=" + statusCode + "}";
    }
}
