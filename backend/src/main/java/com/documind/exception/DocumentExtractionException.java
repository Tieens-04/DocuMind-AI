package com.documind.exception;

import java.util.Objects;

/**
 * Thrown when document extraction or chunking fails due to invalid, empty, encrypted, scanned, or oversized input.
 */
public class DocumentExtractionException extends RuntimeException {

    private final DocumentExtractionErrorCode errorCode;

    public DocumentExtractionException(DocumentExtractionErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public DocumentExtractionException(DocumentExtractionErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public DocumentExtractionErrorCode errorCode() {
        return errorCode;
    }
}
