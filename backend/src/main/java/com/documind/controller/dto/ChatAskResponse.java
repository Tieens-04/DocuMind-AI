package com.documind.controller.dto;

import com.documind.domain.enums.CacheTier;

import java.util.List;
import java.util.UUID;

/**
 * Grounded answer payload returned by {@code POST /api/v1/chat/ask}, including session/message identity,
 * optional standalone rewritten query, citations, abstention flag, provider identifier, cache telemetry,
 * and token/latency telemetry.
 */
public record ChatAskResponse(
    UUID sessionId,
    UUID messageId,
    String rewrittenQuery,
    String answer,
    boolean abstained,
    List<CitationDto> citations,
    String modelProvider,
    boolean cacheHit,
    CacheTier cacheTier,
    int promptTokens,
    int completionTokens,
    long latencyMs
) {

    public ChatAskResponse {
        citations = citations != null ? List.copyOf(citations) : List.of();
        cacheTier = cacheTier != null ? cacheTier : CacheTier.NONE;
        cacheHit = cacheTier != CacheTier.NONE;
    }

    public ChatAskResponse(
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
        this(
            sessionId,
            messageId,
            rewrittenQuery,
            answer,
            abstained,
            citations,
            modelProvider,
            false,
            CacheTier.NONE,
            promptTokens,
            completionTokens,
            latencyMs
        );
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
        this(
            null,
            null,
            null,
            answer,
            abstained,
            citations,
            modelProvider,
            false,
            CacheTier.NONE,
            promptTokens,
            completionTokens,
            latencyMs
        );
    }
}
