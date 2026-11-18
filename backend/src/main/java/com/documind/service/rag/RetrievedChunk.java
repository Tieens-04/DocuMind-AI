package com.documind.service.rag;

import com.documind.controller.dto.CitationDto;

import java.util.Objects;
import java.util.UUID;

/**
 * Retrieved vector chunk carrying full chunk text for prompt grounding and structured citation metadata for the caller.
 */
public record RetrievedChunk(
    String chunkId,
    UUID documentId,
    int chunkIndex,
    String content,
    CitationDto citation
) {

    public RetrievedChunk {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(citation, "citation must not be null");
        content = content != null ? content : "";
    }
}
