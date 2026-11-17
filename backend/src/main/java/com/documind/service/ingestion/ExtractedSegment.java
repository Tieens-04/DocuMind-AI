package com.documind.service.ingestion;

/**
 * Represents one extracted logical unit (a PDF page or a structured section) prior to token chunking.
 */
public record ExtractedSegment(
    String content,
    Integer pageNumber,
    String sectionHeading
) {

    public ExtractedSegment {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        if (pageNumber != null && pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1 when present");
        }
        sectionHeading = (sectionHeading == null || sectionHeading.isBlank()) ? null : sectionHeading.trim();
    }
}
