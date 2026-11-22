package com.documind.controller.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Short-lived inline MinIO presigned GET preview URL response for RBAC-verified document viewing,
 * including optional {@code #page=X} anchor for PDF citations.
 */
public record DocumentPreviewUrlResponse(
    UUID documentId,
    String originalName,
    String mimeType,
    String fileExtension,
    int currentVersion,
    Integer pageNumber,
    String previewUrl,
    OffsetDateTime expiresAt
) {
}
