package com.documind.domain.event;

import com.documind.domain.enums.AccessLevel;

import java.util.UUID;

/**
 * Domain event emitted upon durable metadata persistence for asynchronous document ingestion ({@code DA-9}).
 */
public record DocumentUploadedEvent(
    UUID documentId,
    UUID departmentId,
    String storageKey,
    String originalName,
    String fileExtension,
    AccessLevel accessLevel,
    int version
) {
}
