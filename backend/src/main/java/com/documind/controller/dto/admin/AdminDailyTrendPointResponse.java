package com.documind.controller.dto.admin;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Single UTC day bucket of FinOps chat volume, cache hit ratio, and LLM token spend.
 */
public record AdminDailyTrendPointResponse(
    LocalDate date,
    long successfulQueries,
    long cacheHits,
    BigDecimal cacheHitRatioPercent,
    long promptTokens,
    long completionTokens,
    long totalTokens
) {
}
