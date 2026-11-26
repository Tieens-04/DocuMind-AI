package com.documind.service.admin;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminAnalyticsSummaryResponse;
import com.documind.controller.dto.admin.AdminAuditLogItemResponse;
import com.documind.controller.dto.admin.AdminDailyTrendsResponse;
import com.documind.controller.dto.admin.AdminDepartmentTokenUsageReportResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.Role;
import com.documind.exception.InvalidAdminRequestException;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAnalyticsServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant FIXED_NOW = Instant.parse("2026-11-24T12:00:00Z");

    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AdminAnalyticsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new AdminAnalyticsService(
            userRepository,
            auditLogRepository,
            new NoOpTransactionManager(),
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );

        UserEntity activeAdmin = new UserEntity();
        activeAdmin.setId(ADMIN_ID);
        activeAdmin.setRole(Role.SYSTEM_ADMIN);
        activeAdmin.setIsActive(true);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(activeAdmin));
    }

    @Test
    void summaryUsesDefault30DayUtcWindowHandlesZeroAndRoundsHitRatioToTwoDecimals() {
        OffsetDateTime expectedTo = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        OffsetDateTime expectedFrom = expectedTo.minusDays(30);

        AuditLogRepository.SummaryProjection emptyProj = mockSummaryProjection(0L, 0L, 0L, 0L);
        when(auditLogRepository.aggregateSummary(expectedFrom, expectedTo)).thenReturn(emptyProj);

        AdminAnalyticsSummaryResponse empty = service.getSummary(null, null, ADMIN_ID);
        assertThat(empty.from()).isEqualTo(expectedFrom);
        assertThat(empty.to()).isEqualTo(expectedTo);
        assertThat(empty.successfulQueries()).isZero();
        assertThat(empty.cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(empty.totalTokens()).isZero();

        OffsetDateTime customFrom = OffsetDateTime.parse("2026-11-23T07:00:00+07:00");
        OffsetDateTime customTo = OffsetDateTime.parse("2026-11-24T07:00:00+07:00");
        OffsetDateTime utcFrom = OffsetDateTime.parse("2026-11-23T00:00:00Z");
        OffsetDateTime utcTo = OffsetDateTime.parse("2026-11-24T00:00:00Z");
        AuditLogRepository.SummaryProjection populatedProj = mockSummaryProjection(3L, 1L, 1500L, 620L);
        when(auditLogRepository.aggregateSummary(utcFrom, utcTo))
            .thenReturn(populatedProj);

        AdminAnalyticsSummaryResponse populated = service.getSummary(customFrom, customTo, ADMIN_ID);
        assertThat(populated.from()).isEqualTo(utcFrom);
        assertThat(populated.to()).isEqualTo(utcTo);
        assertThat(populated.successfulQueries()).isEqualTo(3L);
        assertThat(populated.cacheHits()).isEqualTo(1L);
        assertThat(populated.cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(populated.promptTokens()).isEqualTo(1500L);
        assertThat(populated.completionTokens()).isEqualTo(620L);
        assertThat(populated.totalTokens()).isEqualTo(2120L);
    }

    @Test
    void tokenByDepartmentAndDailyTrendsMapNullDepartmentAndComputeTotals() {
        AuditLogRepository.DepartmentTokenUsageProjection itProj = mockDeptProjection(IT_DEPT_ID, "IT", "Information Tech", 2L, 1L, 400L, 120L);
        AuditLogRepository.DepartmentTokenUsageProjection nullProj = mockDeptProjection(null, null, null, 1L, 0L, 100L, 30L);
        when(auditLogRepository.aggregateTokenUsageByDepartment(any(), any())).thenReturn(List.of(itProj, nullProj));

        AdminDepartmentTokenUsageReportResponse deptReport = service.getTokenUsageByDepartment(null, null, ADMIN_ID);
        assertThat(deptReport.items()).hasSize(2);
        assertThat(deptReport.items().get(0).cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(deptReport.items().get(0).totalTokens()).isEqualTo(520L);
        assertThat(deptReport.items().get(1).departmentId()).isNull();
        assertThat(deptReport.items().get(1).departmentCode()).isNull();
        assertThat(deptReport.items().get(1).totalTokens()).isEqualTo(130L);

        AuditLogRepository.DailyTrendProjection day1 = mockTrendProjection(LocalDate.of(2026, 11, 23), 3L, 1L, 900L, 300L);
        when(auditLogRepository.aggregateDailyTrends(any(), any())).thenReturn(List.of(day1));

        AdminDailyTrendsResponse trends = service.getDailyTrends(null, null, ADMIN_ID);
        assertThat(trends.items()).hasSize(1);
        assertThat(trends.items().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(trends.items().get(0).cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(trends.items().get(0).totalTokens()).isEqualTo(1200L);
    }

    @Test
    void rejectsInvalidTimeRangesPagingStatusCodesAndNonAdminActors() {
        OffsetDateTime same = OffsetDateTime.parse("2026-11-24T00:00:00Z");
        assertThatThrownBy(() -> service.getSummary(same, same, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.getDailyTrends(same.plusHours(1), same, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.getSummary(same.minusDays(367), same, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);

        assertThatThrownBy(() -> service.listAuditLogs(null, null, null, null, null, null, null, -1, 20, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.listAuditLogs(null, null, null, null, null, null, null, 0, 101, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);
        assertThatThrownBy(() -> service.listAuditLogs(null, null, null, null, null, 999, null, 0, 20, ADMIN_ID))
            .isInstanceOf(InvalidAdminRequestException.class);

        verifyNoInteractions(auditLogRepository);

        UUID managerId = UUID.randomUUID();
        UserEntity manager = new UserEntity();
        manager.setId(managerId);
        manager.setRole(Role.DEPARTMENT_MANAGER);
        manager.setIsActive(true);
        when(userRepository.findById(managerId)).thenReturn(Optional.of(manager));

        assertThatThrownBy(() -> service.getSummary(null, null, managerId))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listAuditLogsMapsEntitiesWithDeterministicSort() {
        DepartmentEntity dept = new DepartmentEntity();
        dept.setId(IT_DEPT_ID);
        dept.setCode("IT");
        dept.setName("Information Tech");

        UserEntity user = new UserEntity();
        user.setId(ADMIN_ID);
        user.setEmail("admin@documind.ai");

        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(UUID.randomUUID());
        entity.setUser(user);
        entity.setDepartment(dept);
        entity.setActionType(AuditActionType.RATE_LIMITED);
        entity.setQueryText("RATE_LIMIT:CHAT:/api/v1/chat/ask");
        entity.setAbstained(false);
        entity.setCacheTier(CacheTier.NONE);
        entity.setPromptTokens(0);
        entity.setCompletionTokens(0);
        entity.setLatencyMs(2L);
        entity.setStatusCode(429);
        entity.setIpAddress("127.0.0.1");
        entity.setCreatedAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));

        when(auditLogRepository.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entity)));

        PageResponse<AdminAuditLogItemResponse> page = service.listAuditLogs(
            null, null, AuditActionType.RATE_LIMITED, IT_DEPT_ID, ADMIN_ID, 429, false, 0, 20, ADMIN_ID
        );

        assertThat(page.items()).hasSize(1);
        AdminAuditLogItemResponse item = page.items().get(0);
        assertThat(item.actionType()).isEqualTo(AuditActionType.RATE_LIMITED);
        assertThat(item.userEmail()).isEqualTo("admin@documind.ai");
        assertThat(item.departmentCode()).isEqualTo("IT");
        assertThat(item.statusCode()).isEqualTo(429);
        assertThat(item.totalTokens()).isZero();
        verify(auditLogRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    private static AuditLogRepository.SummaryProjection mockSummaryProjection(
        long successfulQueries,
        long cacheHits,
        long promptTokens,
        long completionTokens
    ) {
        AuditLogRepository.SummaryProjection proj = mock(AuditLogRepository.SummaryProjection.class);
        when(proj.getSuccessfulQueries()).thenReturn(successfulQueries);
        when(proj.getCacheHits()).thenReturn(cacheHits);
        when(proj.getPromptTokens()).thenReturn(promptTokens);
        when(proj.getCompletionTokens()).thenReturn(completionTokens);
        return proj;
    }

    private static AuditLogRepository.DepartmentTokenUsageProjection mockDeptProjection(
        UUID deptId,
        String deptCode,
        String deptName,
        long successfulQueries,
        long cacheHits,
        long promptTokens,
        long completionTokens
    ) {
        AuditLogRepository.DepartmentTokenUsageProjection proj = mock(AuditLogRepository.DepartmentTokenUsageProjection.class);
        when(proj.getDepartmentId()).thenReturn(deptId);
        when(proj.getDepartmentCode()).thenReturn(deptCode);
        when(proj.getDepartmentName()).thenReturn(deptName);
        when(proj.getSuccessfulQueries()).thenReturn(successfulQueries);
        when(proj.getCacheHits()).thenReturn(cacheHits);
        when(proj.getPromptTokens()).thenReturn(promptTokens);
        when(proj.getCompletionTokens()).thenReturn(completionTokens);
        return proj;
    }

    private static AuditLogRepository.DailyTrendProjection mockTrendProjection(
        LocalDate date,
        long successfulQueries,
        long cacheHits,
        long promptTokens,
        long completionTokens
    ) {
        AuditLogRepository.DailyTrendProjection proj = mock(AuditLogRepository.DailyTrendProjection.class);
        when(proj.getDate()).thenReturn(date);
        when(proj.getSuccessfulQueries()).thenReturn(successfulQueries);
        when(proj.getCacheHits()).thenReturn(cacheHits);
        when(proj.getPromptTokens()).thenReturn(promptTokens);
        when(proj.getCompletionTokens()).thenReturn(completionTokens);
        return proj;
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
