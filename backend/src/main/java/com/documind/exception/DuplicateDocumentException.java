package com.documind.exception;

import java.util.UUID;

public class DuplicateDocumentException extends RuntimeException {

    private final UUID existingDocumentId;
    private final String fileHash;

    public DuplicateDocumentException(UUID existingDocumentId, String fileHash, String message) {
        super(message);
        this.existingDocumentId = existingDocumentId;
        this.fileHash = fileHash;
    }

    public UUID existingDocumentId() {
        return existingDocumentId;
    }

    public String fileHash() {
        return fileHash;
    }
}
