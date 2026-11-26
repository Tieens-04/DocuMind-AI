package com.documind.controller.dto.admin;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-department FinOps breakdown of successful chat queries, cache hits, and token consumption.
 */
public record AdminDepartmentTokenUsageResponse(
    UUID departmentId,
    String departmentCode,
    String departmentName,
    long successfulQueries,
    long cacheHits,
    BigDecimal cacheHitRatioPercent,
    long promptTokens,
    long completionTokens,
    long totalTokens
) {
}
