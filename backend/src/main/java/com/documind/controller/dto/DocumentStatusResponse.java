package com.documind.controller.dto;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight polling DTO exposing document ingestion status and indexed chunk count.
 */
public record DocumentStatusResponse(
    UUID id,
    DocumentStatus status,
    int chunkCount,
    int currentVersion,
    String errorMessage,
    OffsetDateTime updatedAt
) {

    public static DocumentStatusResponse from(DocumentEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new DocumentStatusResponse(
            entity.getId(),
            entity.getStatus(),
            entity.getChunkCount() != null ? entity.getChunkCount() : 0,
            entity.getCurrentVersion() != null ? entity.getCurrentVersion() : 1,
            entity.getErrorMessage(),
            entity.getUpdatedAt()
        );
    }
}
