package com.documind.controller.dto.admin;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative read projection of an {@link AuditLogEntity} across all governance action types.
 */
public record AdminAuditLogItemResponse(
    UUID id,
    UUID userId,
    String userEmail,
    UUID departmentId,
    String departmentCode,
    String departmentName,
    UUID sessionId,
    AuditActionType actionType,
    String queryText,
    boolean abstained,
    boolean cacheHit,
    CacheTier cacheTier,
    int promptTokens,
    int completionTokens,
    long totalTokens,
    long latencyMs,
    String provider,
    int statusCode,
    String ipAddress,
    OffsetDateTime createdAt
) {

    public static AdminAuditLogItemResponse fromEntity(AuditLogEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        UserEntity user = entity.getUser();
        DepartmentEntity department = entity.getDepartment();
        int prompt = entity.getPromptTokens() != null ? Math.max(0, entity.getPromptTokens()) : 0;
        int completion = entity.getCompletionTokens() != null ? Math.max(0, entity.getCompletionTokens()) : 0;
        long latency = entity.getLatencyMs() != null ? Math.max(0L, entity.getLatencyMs()) : 0L;
        int statusCode = entity.getStatusCode() != null ? entity.getStatusCode() : 200;
        CacheTier tier = entity.getCacheTier() != null ? entity.getCacheTier() : CacheTier.NONE;

        return new AdminAuditLogItemResponse(
            entity.getId(),
            entity.getUserId(),
            user != null ? user.getEmail() : null,
            entity.getDepartmentId(),
            department != null ? department.getCode() : null,
            department != null ? department.getName() : null,
            entity.getSessionId(),
            entity.getActionType(),
            entity.getQueryText(),
            entity.isAbstained(),
            entity.isCacheHit(),
            tier,
            prompt,
            completion,
            (long) prompt + completion,
            latency,
            entity.getProvider(),
            statusCode,
            entity.getIpAddress(),
            entity.getCreatedAt()
        );
    }
}
