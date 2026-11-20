package com.documind.controller.dto;

import com.documind.domain.enums.AccessLevel;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Partial metadata update payload for {@code PATCH /api/v1/documents/{id}}.
 * {@code departmentId} transfer is strictly restricted to {@code SYSTEM_ADMIN}.
 */
public record UpdateDocumentMetadataRequest(
    @Size(max = 255, message = "Document originalName must not exceed 255 characters")
    String originalName,

    AccessLevel accessLevel,

    UUID departmentId
) {
}
