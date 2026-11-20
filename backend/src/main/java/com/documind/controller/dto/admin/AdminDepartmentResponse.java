package com.documind.controller.dto.admin;

import com.documind.domain.entity.DepartmentEntity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Administrative representation of a department and its semantic-cache version.
 */
public record AdminDepartmentResponse(
    UUID id,
    String code,
    String name,
    String description,
    long cacheVersion,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    public static AdminDepartmentResponse fromEntity(DepartmentEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new AdminDepartmentResponse(
            entity.getId(),
            entity.getCode(),
            entity.getName(),
            entity.getDescription(),
            entity.getCacheVersion() != null ? entity.getCacheVersion() : 1L,
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
