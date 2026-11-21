package com.documind.controller.dto;

import java.util.List;
import java.util.UUID;

/**
 * Grounded answer payload returned by {@code POST /api/v1/chat/ask}, including session/message identity,
 * optional standalone rewritten query, citations, abstention flag, provider identifier, and token/latency telemetry.
 */
public record ChatAskResponse(
    UUID sessionId,
    UUID messageId,
    String rewrittenQuery,
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

    public ChatAskResponse(
        String answer,
        boolean abstained,
        List<CitationDto> citations,
        String modelProvider,
        int promptTokens,
        int completionTokens,
        long latencyMs
    ) {
        this(null, null, null, answer, abstained, citations, modelProvider, promptTokens, completionTokens, latencyMs);
    }
}
