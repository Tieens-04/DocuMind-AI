package com.documind.service.admin;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminAnalyticsSummaryResponse;
import com.documind.controller.dto.admin.AdminAuditLogItemResponse;
import com.documind.controller.dto.admin.AdminDailyTrendPointResponse;
import com.documind.controller.dto.admin.AdminDailyTrendsResponse;
import com.documind.controller.dto.admin.AdminDepartmentTokenUsageReportResponse;
import com.documind.controller.dto.admin.AdminDepartmentTokenUsageResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only administrative FinOps analytics and audit governance service restricted to {@code SYSTEM_ADMIN}.
 */
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminAnalyticsService {

    static final long DEFAULT_LOOKBACK_DAYS = 30L;
    static final long MAX_WINDOW_DAYS = 366L;
    private static final Duration MAX_WINDOW_DURATION = Duration.ofDays(MAX_WINDOW_DAYS);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final int MIN_HTTP_STATUS = 100;
    private static final int MAX_HTTP_STATUS = 599;

    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Autowired
    public AdminAnalyticsService(
        @Lazy UserRepository userRepository,
        @Lazy AuditLogRepository auditLogRepository,
        @Lazy PlatformTransactionManager transactionManager
    ) {
        this(userRepository, auditLogRepository, transactionManager, Clock.systemUTC());
    }

    public AdminAnalyticsService(
        UserRepository userRepository,
        AuditLogRepository auditLogRepository,
        PlatformTransactionManager transactionManager,
        Clock clock
    ) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "auditLogRepository must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public AdminAnalyticsSummaryResponse getSummary(OffsetDateTime from, OffsetDateTime to, UUID actorUserId) {
        TimeWindow window = resolveTimeWindow(from, to);
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            AuditLogRepository.SummaryProjection row = auditLogRepository.aggregateSummary(window.from(), window.to());
            long successfulQueries = row != null ? row.getSuccessfulQueries() : 0L;
            long cacheHits = row != null ? row.getCacheHits() : 0L;
            long promptTokens = row != null ? row.getPromptTokens() : 0L;
            long completionTokens = row != null ? row.getCompletionTokens() : 0L;
            return new AdminAnalyticsSummaryResponse(
                window.from(),
                window.to(),
                successfulQueries,
                cacheHits,
                calculateCacheHitRatioPercent(cacheHits, successfulQueries),
                promptTokens,
                completionTokens,
                promptTokens + completionTokens
            );
        });
    }

    public AdminDepartmentTokenUsageReportResponse getTokenUsageByDepartment(
        OffsetDateTime from,
        OffsetDateTime to,
        UUID actorUserId
    ) {
        TimeWindow window = resolveTimeWindow(from, to);
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            List<AdminDepartmentTokenUsageResponse> items = auditLogRepository
                .aggregateTokenUsageByDepartment(window.from(), window.to())
                .stream()
                .map(row -> new AdminDepartmentTokenUsageResponse(
                    row.getDepartmentId(),
                    row.getDepartmentCode(),
                    row.getDepartmentName(),
                    row.getSuccessfulQueries(),
                    row.getCacheHits(),
                    calculateCacheHitRatioPercent(row.getCacheHits(), row.getSuccessfulQueries()),
                    row.getPromptTokens(),
                    row.getCompletionTokens(),
                    row.getPromptTokens() + row.getCompletionTokens()
                ))
                .toList();
            return new AdminDepartmentTokenUsageReportResponse(window.from(), window.to(), items);
        });
    }

    public AdminDailyTrendsResponse getDailyTrends(OffsetDateTime from, OffsetDateTime to, UUID actorUserId) {
        TimeWindow window = resolveTimeWindow(from, to);
        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            List<AdminDailyTrendPointResponse> items = auditLogRepository
                .aggregateDailyTrends(window.from(), window.to())
                .stream()
                .map(row -> new AdminDailyTrendPointResponse(
                    row.getDate(),
                    row.getSuccessfulQueries(),
                    row.getCacheHits(),
                    calculateCacheHitRatioPercent(row.getCacheHits(), row.getSuccessfulQueries()),
                    row.getPromptTokens(),
                    row.getCompletionTokens(),
                    row.getPromptTokens() + row.getCompletionTokens()
                ))
                .toList();
            return new AdminDailyTrendsResponse(window.from(), window.to(), items);
        });
    }

    public PageResponse<AdminAuditLogItemResponse> listAuditLogs(
        OffsetDateTime from,
        OffsetDateTime to,
        AuditActionType actionType,
        UUID departmentId,
        UUID userId,
        Integer statusCode,
        Boolean cacheHit,
        int page,
        int size,
        UUID actorUserId
    ) {
        AdminServiceSupport.validatePaging(page, size);
        if (statusCode != null && (statusCode < MIN_HTTP_STATUS || statusCode > MAX_HTTP_STATUS)) {
            throw new InvalidAdminRequestException(
                "HTTP status code filter must be between " + MIN_HTTP_STATUS + " and " + MAX_HTTP_STATUS
            );
        }
        TimeWindow window = resolveTimeWindow(from, to);

        return AdminServiceSupport.executeInTransaction(transactionManager, true, () -> {
            AdminServiceSupport.verifyActiveSystemAdmin(userRepository, actorUserId);
            Specification<AuditLogEntity> spec = (root, cq, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), window.from()));
                predicates.add(cb.lessThan(root.get("createdAt"), window.to()));
                if (actionType != null) {
                    predicates.add(cb.equal(root.get("actionType"), actionType));
                }
                if (departmentId != null) {
                    predicates.add(cb.equal(root.get("departmentId"), departmentId));
                }
                if (userId != null) {
                    predicates.add(cb.equal(root.get("userId"), userId));
                }
                if (statusCode != null) {
                    predicates.add(cb.equal(root.get("statusCode"), statusCode));
                }
                if (cacheHit != null) {
                    predicates.add(cb.equal(root.get("cacheHit"), cacheHit));
                }
                return cb.and(predicates.toArray(Predicate[]::new));
            };
            PageRequest pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
            );
            Page<AdminAuditLogItemResponse> mapped = auditLogRepository
                .findAll(spec, pageable)
                .map(AdminAuditLogItemResponse::fromEntity);
            return PageResponse.from(mapped);
        });
    }

    TimeWindow resolveTimeWindow(OffsetDateTime rawFrom, OffsetDateTime rawTo) {
        OffsetDateTime nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime effectiveTo = rawTo != null
            ? rawTo.withOffsetSameInstant(ZoneOffset.UTC)
            : nowUtc;
        OffsetDateTime effectiveFrom = rawFrom != null
            ? rawFrom.withOffsetSameInstant(ZoneOffset.UTC)
            : effectiveTo.minusDays(DEFAULT_LOOKBACK_DAYS);
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw new InvalidAdminRequestException("'from' must be strictly before 'to'");
        }
        if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_WINDOW_DURATION) > 0) {
            throw new InvalidAdminRequestException(
                "Analytics time window must not exceed " + MAX_WINDOW_DAYS + " days"
            );
        }
        return new TimeWindow(effectiveFrom, effectiveTo);
    }

    static BigDecimal calculateCacheHitRatioPercent(long cacheHits, long successfulQueries) {
        if (successfulQueries <= 0L) {
            return ZERO_PERCENT;
        }
        return BigDecimal.valueOf(cacheHits)
            .multiply(HUNDRED)
            .divide(BigDecimal.valueOf(successfulQueries), 2, RoundingMode.HALF_UP);
    }

    record TimeWindow(OffsetDateTime from, OffsetDateTime to) {
    }
}
