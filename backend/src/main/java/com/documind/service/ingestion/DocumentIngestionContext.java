package com.documind.service.ingestion;

import com.documind.domain.enums.AccessLevel;
import com.documind.domain.event.DocumentUploadedEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * Immutable context passed through document extraction and chunking stages.
 */
public record DocumentIngestionContext(
    UUID documentId,
    UUID departmentId,
    String storageKey,
    String originalName,
    String fileExtension,
    AccessLevel accessLevel,
    int docVersion
) {

    public DocumentIngestionContext {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId must not be null");
        }
        if (departmentId == null) {
            throw new IllegalArgumentException("departmentId must not be null");
        }
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("originalName must not be blank");
        }
        if (fileExtension == null || fileExtension.isBlank()) {
            throw new IllegalArgumentException("fileExtension must not be blank");
        }
        String normalizedExtension = fileExtension.trim().toLowerCase(Locale.ROOT);
        while (normalizedExtension.startsWith(".")) {
            normalizedExtension = normalizedExtension.substring(1);
        }
        if (normalizedExtension.isEmpty()) {
            throw new IllegalArgumentException("fileExtension must not be empty");
        }
        fileExtension = normalizedExtension;
        if (accessLevel == null) {
            throw new IllegalArgumentException("accessLevel must not be null");
        }
        if (docVersion < 1) {
            throw new IllegalArgumentException("docVersion must be >= 1");
        }
    }

    public static DocumentIngestionContext from(DocumentUploadedEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return new DocumentIngestionContext(
            event.documentId(),
            event.departmentId(),
            event.storageKey(),
            event.originalName(),
            event.fileExtension(),
            event.accessLevel(),
            event.version()
        );
    }
}
