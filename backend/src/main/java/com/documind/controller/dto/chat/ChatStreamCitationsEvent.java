package com.documind.controller.dto.chat;

import com.documind.controller.dto.CitationDto;

import java.util.List;

/**
 * Payload for SSE {@code event: citations}, emitted immediately after RBAC-filtered vector retrieval
 * (target {@code < 500ms}) before LLM token generation begins.
 */
public record ChatStreamCitationsEvent(
    List<CitationDto> citations,
    String rewrittenQuery
) {

    public ChatStreamCitationsEvent {
        citations = citations != null ? List.copyOf(citations) : List.of();
    }
}
