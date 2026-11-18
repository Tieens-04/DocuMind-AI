package com.documind.controller.dto;

import java.util.List;

/**
 * Grounded answer payload returned by {@code POST /api/v1/chat/ask}, including citations,
 * abstention flag, provider identifier, and token/latency telemetry.
 */
public record ChatAskResponse(
    String answer,
    boolean abstained,
    List<CitationDto> citations,
    String modelProvider,
    int promptTokens,
    int completionTokens,
    long latencyMs
) {

    public ChatAskResponse {
        citations = citations != null ? List.copyOf(citations) : List.of();
    }
}
