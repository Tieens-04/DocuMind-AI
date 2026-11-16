package com.documind.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Structured citation entry serialized into {@code chat_messages.citations} and {@code semantic_cache.citations} JSONB columns.
 */
public record CitationPayload(
    @JsonProperty("documentId") UUID documentId,
    @JsonProperty("fileName") String fileName,
    @JsonProperty("pageNumber") Integer pageNumber,
    @JsonProperty("similarityScore") double similarityScore,
    @JsonProperty("excerpt") String excerpt
) {
}
