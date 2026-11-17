package com.documind.exception;

/**
 * Machine-readable error codes classifying deterministic and limit-based document extraction failures.
 */
public enum DocumentExtractionErrorCode {
    EMPTY_DOCUMENT,
    NO_EXTRACTABLE_TEXT,
    ENCRYPTED_DOCUMENT,
    CORRUPT_DOCUMENT,
    EXTRACTION_LIMIT_EXCEEDED,
    UNSUPPORTED_FORMAT
}
