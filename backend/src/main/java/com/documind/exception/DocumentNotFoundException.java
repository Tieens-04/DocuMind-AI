package com.documind.exception;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    private final UUID documentId;

    public DocumentNotFoundException(UUID documentId) {
        super("Document not found: " + documentId);
        this.documentId = documentId;
    }

    public UUID documentId() {
        return documentId;
    }
}
