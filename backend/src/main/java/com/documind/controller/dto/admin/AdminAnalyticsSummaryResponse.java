package com.documind.controller.dto.admin;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Aggregated FinOps summary of chat query throughput, cache efficiency, and LLM token spend.
 */
public record AdminAnalyticsSummaryResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    long successfulQueries,
    long cacheHits,
    BigDecimal cacheHitRatioPercent,
    long promptTokens,
    long completionTokens,
    long totalTokens,
    BigDecimal averageLatencyMs
) {
}
