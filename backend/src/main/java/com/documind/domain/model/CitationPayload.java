package com.documind.domain.model;

import java.util.UUID;

/**
 * Structured citation entry serialized into {@code chat_messages.citations} and {@code semantic_cache.citations} JSONB columns.
 */
public record CitationPayload(
    UUID documentId,
    String fileName,
    Integer pageNumber,
    double similarityScore,
    String excerpt
) {
}
