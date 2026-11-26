package com.documind.controller;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminAnalyticsSummaryResponse;
import com.documind.controller.dto.admin.AdminAuditLogItemResponse;
import com.documind.controller.dto.admin.AdminDailyTrendsResponse;
import com.documind.controller.dto.admin.AdminDepartmentTokenUsageReportResponse;
import com.documind.domain.enums.AuditActionType;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.service.admin.AdminAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only administrative FinOps analytics and audit governance endpoints (strictly {@code SYSTEM_ADMIN}).
 */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(
    name = "Admin Analytics",
    description = "FinOps summary, per-department token spend, UTC daily trends, and audit log viewer restricted to SYSTEM_ADMIN"
)
public class AdminAnalyticsController {

    private final AdminAnalyticsService adminAnalyticsService;

    public AdminAnalyticsController(AdminAnalyticsService adminAnalyticsService) {
        this.adminAnalyticsService = Objects.requireNonNull(
            adminAnalyticsService,
            "adminAnalyticsService must not be null"
        );
    }

    @GetMapping("/summary")
    @Operation(
        summary = "Get FinOps analytics summary",
        description = "Returns successful query count, L1/L2 cache hit ratio (%), and total prompt/completion/overall tokens over [from, to) UTC"
    )
    public AdminAnalyticsSummaryResponse getSummary(
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminAnalyticsService.getSummary(from, to, CustomUserDetails.requireUserId(principal));
    }

    @GetMapping("/token-by-department")
    @Operation(
        summary = "Get token usage by department",
        description = "Aggregates chat query token consumption and cache hit metrics grouped by audit department (preserving null department attribution)"
    )
    public AdminDepartmentTokenUsageReportResponse getTokenUsageByDepartment(
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminAnalyticsService.getTokenUsageByDepartment(from, to, CustomUserDetails.requireUserId(principal));
    }

    @GetMapping("/daily-trends")
    @Operation(
        summary = "Get daily UTC token and cache trends",
        description = "Returns chronological UTC daily aggregates for successful queries, cache hits, and token consumption"
    )
    public AdminDailyTrendsResponse getDailyTrends(
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminAnalyticsService.getDailyTrends(from, to, CustomUserDetails.requireUserId(principal));
    }

    @GetMapping("/audit-logs")
    @Operation(
        summary = "List governance audit logs",
        description = "Returns paginated audit log events across all action types sorted by created_at DESC, id DESC"
    )
    public PageResponse<AdminAuditLogItemResponse> listAuditLogs(
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @RequestParam(name = "actionType", required = false) AuditActionType actionType,
        @RequestParam(name = "departmentId", required = false) UUID departmentId,
        @RequestParam(name = "userId", required = false) UUID userId,
        @RequestParam(name = "statusCode", required = false) Integer statusCode,
        @RequestParam(name = "cacheHit", required = false) Boolean cacheHit,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return adminAnalyticsService.listAuditLogs(
            from,
            to,
            actionType,
            departmentId,
            userId,
            statusCode,
            cacheHit,
            page,
            size,
            CustomUserDetails.requireUserId(principal)
        );
    }
}
