package com.documind.controller.dto.admin;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * FinOps report grouping chat token consumption and cache hit metrics by audit department.
 */
public record AdminDepartmentTokenUsageReportResponse(
    OffsetDateTime from,
    OffsetDateTime to,
    List<AdminDepartmentTokenUsageResponse> items
) {

    public AdminDepartmentTokenUsageReportResponse {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
