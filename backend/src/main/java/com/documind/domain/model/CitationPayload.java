package com.documind.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Structured citation entry serialized into {@code chat_messages.citations} and {@code semantic_cache.citations} JSONB columns.
 */
public record CitationPayload(
    @JsonProperty("documentId") UUID documentId,
    @JsonProperty("docVersion") Integer docVersion,
    @JsonProperty("fileName") String fileName,
    @JsonProperty("pageNumber") Integer pageNumber,
    @JsonProperty("sectionHeading") String sectionHeading,
    @JsonProperty("similarityScore") double similarityScore,
    @JsonProperty("excerpt") String excerpt
) {

    @JsonCreator
    public CitationPayload(
        @JsonProperty("documentId") UUID documentId,
        @JsonProperty("docVersion") Integer docVersion,
        @JsonProperty("fileName") String fileName,
        @JsonProperty("pageNumber") Integer pageNumber,
        @JsonProperty("sectionHeading") String sectionHeading,
        @JsonProperty("similarityScore") double similarityScore,
        @JsonProperty("excerpt") String excerpt
    ) {
        this.documentId = documentId;
        this.docVersion = docVersion;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.sectionHeading = sectionHeading;
        this.similarityScore = similarityScore;
        this.excerpt = excerpt;
    }

    public CitationPayload(
        UUID documentId,
        String fileName,
        Integer pageNumber,
        String sectionHeading,
        double similarityScore,
        String excerpt
    ) {
        this(documentId, null, fileName, pageNumber, sectionHeading, similarityScore, excerpt);
    }

    public CitationPayload(
        UUID documentId,
        String fileName,
        Integer pageNumber,
        double similarityScore,
        String excerpt
    ) {
        this(documentId, null, fileName, pageNumber, null, similarityScore, excerpt);
    }
}
