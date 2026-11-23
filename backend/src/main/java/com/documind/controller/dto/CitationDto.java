package com.documind.controller.dto;

import com.documind.domain.model.CitationPayload;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Structured citation returned in RAG responses, including source document version and inline preview link.
 */
public record CitationDto(
    @JsonProperty("documentId") UUID documentId,
    @JsonProperty("docVersion") Integer docVersion,
    @JsonProperty("fileName") String fileName,
    @JsonProperty("pageNumber") Integer pageNumber,
    @JsonProperty("sectionHeading") String sectionHeading,
    @JsonProperty("similarityScore") double similarityScore,
    @JsonProperty("excerpt") String excerpt,
    @JsonProperty("presignedUrl") String presignedUrl
) {

    @JsonCreator
    public CitationDto(
        @JsonProperty("documentId") UUID documentId,
        @JsonProperty("docVersion") Integer docVersion,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("pageNumber") Integer pageNumber,
        @JsonProperty("sectionHeading") String sectionHeading,
        @JsonProperty("similarityScore") double similarityScore,
        @JsonProperty("excerpt") String excerpt,
        @JsonProperty("presignedUrl") String presignedUrl
    ) {
        this.documentId = documentId;
        this.docVersion = docVersion;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.sectionHeading = sectionHeading;
        this.similarityScore = similarityScore;
        this.excerpt = excerpt;
        this.presignedUrl = presignedUrl;
    }

    public CitationDto(
        UUID documentId,
        String fileName,
        Integer pageNumber,
        String sectionHeading,
        double similarityScore,
        String excerpt,
        String presignedUrl
    ) {
        this(documentId, null, fileName, pageNumber, sectionHeading, similarityScore, excerpt, presignedUrl);
    }

    /**
     * Maps this API citation to the persistent JSONB payload stored in {@code chat_messages.citations}
     * and {@code semantic_cache.citations}.
     *
     * @since DA-15
     */
    public CitationPayload toCitationPayload() {
        return new CitationPayload(documentId, docVersion, fileName, pageNumber, sectionHeading, similarityScore, excerpt);
    }
}
