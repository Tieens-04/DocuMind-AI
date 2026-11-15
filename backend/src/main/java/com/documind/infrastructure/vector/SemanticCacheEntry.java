package com.documind.infrastructure.vector;

import com.documind.domain.model.CitationPayload;

import java.util.List;
import java.util.UUID;

/**
 * Cached semantic RAG response returned from {@code semantic_cache} with RBAC-scoped cosine similarity score.
 */
public record SemanticCacheEntry(
    UUID id,
    String rbacScope,
    long scopeVersion,
    String queryHash,
    String queryText,
    String responseContent,
    List<CitationPayload> citations,
    String modelProvider,
    double similarityScore
) {

    public SemanticCacheEntry {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
