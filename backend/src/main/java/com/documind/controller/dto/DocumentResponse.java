package com.documind.controller.dto;

import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * API representation of persisted document metadata and ingestion status.
 */
public record DocumentResponse(
    UUID id,
    UUID departmentId,
    UUID uploadedBy,
    String originalName,
    String fileExtension,
    String mimeType,
    long fileSizeBytes,
    String fileHash,
    AccessLevel accessLevel,
    DocumentStatus status,
    int currentVersion,
    int chunkCount,
    String errorMessage,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    public static DocumentResponse from(DocumentEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return new DocumentResponse(
            entity.getId(),
            entity.getDepartmentId(),
            entity.getUploadedById(),
            entity.getOriginalName(),
            entity.getFileExtension(),
            entity.getMimeType(),
            entity.getFileSizeBytes() != null ? entity.getFileSizeBytes() : 0L,
            entity.getFileHash(),
            entity.getAccessLevel(),
            entity.getStatus(),
            entity.getCurrentVersion() != null ? entity.getCurrentVersion() : 1,
            entity.getChunkCount() != null ? entity.getChunkCount() : 0,
            entity.getErrorMessage(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
