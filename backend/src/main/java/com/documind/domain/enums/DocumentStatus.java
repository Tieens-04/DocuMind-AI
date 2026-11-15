package com.documind.domain.enums;

/**
 * Lifecycle states for asynchronous document ingestion and indexing.
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}
