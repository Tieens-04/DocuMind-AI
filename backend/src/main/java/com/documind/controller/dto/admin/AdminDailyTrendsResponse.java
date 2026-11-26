package com.documind.controller.dto.admin;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Chronological UTC daily FinOps metrics across the requested time window.
 */
public record AdminDailyTrendsResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    List<AdminDailyTrendPointResponse> items
) {

    public AdminDailyTrendsResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
