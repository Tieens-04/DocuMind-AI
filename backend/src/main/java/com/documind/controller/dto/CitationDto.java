package com.documind.controller.dto;

import com.documind.domain.model.CitationPayload;

import java.util.UUID;

/**
 * Structured citation returned in RAG responses, including optional MinIO presigned download/preview link.
 */
public record CitationDto(
    UUID documentId,
    String fileName,
    Integer pageNumber,
    String sectionHeading,
    double similarityScore,
    String excerpt,
    String presignedUrl
) {

    /**
     * Maps this API citation to the persistent JSONB payload stored in {@code chat_messages.citations}
     * and {@code semantic_cache.citations}.
     *
     * @since DA-15
     */
    public CitationPayload toCitationPayload() {
        return new CitationPayload(documentId, fileName, pageNumber, sectionHeading, similarityScore, excerpt);
    }
}
