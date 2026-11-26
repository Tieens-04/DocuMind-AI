package com.documind.repository;

import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.enums.AuditActionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for paginated {@link AuditLogEntity} governance queries
 * and native PostgreSQL FinOps aggregation projections.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {

    Page<AuditLogEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId, Pageable pageable);

    Page<AuditLogEntity> findByActionTypeOrderByCreatedAtDesc(AuditActionType actionType, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"user", "department"})
    Page<AuditLogEntity> findAll(Specification<AuditLogEntity> spec, Pageable pageable);

    @Query(
        value = """
            SELECT
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300) AS "successfulQueries",
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300 AND a.cache_hit = TRUE) AS "cacheHits",
                CAST(COALESCE(SUM(CAST(a.prompt_tokens AS BIGINT)), 0) AS BIGINT) AS "promptTokens",
                CAST(COALESCE(SUM(CAST(a.completion_tokens AS BIGINT)), 0) AS BIGINT) AS "completionTokens"
            FROM audit_logs a
            WHERE a.action_type = 'CHAT_QUERY'
              AND a.created_at >= :from
              AND a.created_at < :to
            """,
        nativeQuery = true
    )
    SummaryProjection aggregateSummary(
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to
    );

    @Query(
        value = """
            SELECT
                a.department_id AS "departmentId",
                d.code AS "departmentCode",
                d.name AS "departmentName",
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300) AS "successfulQueries",
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300 AND a.cache_hit = TRUE) AS "cacheHits",
                CAST(COALESCE(SUM(CAST(a.prompt_tokens AS BIGINT)), 0) AS BIGINT) AS "promptTokens",
                CAST(COALESCE(SUM(CAST(a.completion_tokens AS BIGINT)), 0) AS BIGINT) AS "completionTokens"
            FROM audit_logs a
            LEFT JOIN departments d ON d.id = a.department_id
            WHERE a.action_type = 'CHAT_QUERY'
              AND a.created_at >= :from
              AND a.created_at < :to
            GROUP BY a.department_id, d.code, d.name
            ORDER BY (
                CAST(COALESCE(SUM(CAST(a.prompt_tokens AS BIGINT)), 0) AS BIGINT)
                + CAST(COALESCE(SUM(CAST(a.completion_tokens AS BIGINT)), 0) AS BIGINT)
            ) DESC, a.department_id ASC NULLS LAST
            """,
        nativeQuery = true
    )
    List<DepartmentTokenUsageProjection> aggregateTokenUsageByDepartment(
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to
    );

    @Query(
        value = """
            SELECT
                CAST(a.created_at AT TIME ZONE 'UTC' AS DATE) AS "date",
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300) AS "successfulQueries",
                COUNT(*) FILTER (WHERE a.status_code >= 200 AND a.status_code < 300 AND a.cache_hit = TRUE) AS "cacheHits",
                CAST(COALESCE(SUM(CAST(a.prompt_tokens AS BIGINT)), 0) AS BIGINT) AS "promptTokens",
                CAST(COALESCE(SUM(CAST(a.completion_tokens AS BIGINT)), 0) AS BIGINT) AS "completionTokens"
            FROM audit_logs a
            WHERE a.action_type = 'CHAT_QUERY'
              AND a.created_at >= :from
              AND a.created_at < :to
            GROUP BY CAST(a.created_at AT TIME ZONE 'UTC' AS DATE)
            ORDER BY "date" ASC
            """,
        nativeQuery = true
    )
    List<DailyTrendProjection> aggregateDailyTrends(
        @Param("from") OffsetDateTime from,
        @Param("to") OffsetDateTime to
    );

    interface SummaryProjection {
        long getSuccessfulQueries();

        long getCacheHits();

        long getPromptTokens();

        long getCompletionTokens();
    }

    interface DepartmentTokenUsageProjection {
        UUID getDepartmentId();

        String getDepartmentCode();

        String getDepartmentName();

        long getSuccessfulQueries();

        long getCacheHits();

        long getPromptTokens();

        long getCompletionTokens();
    }

    interface DailyTrendProjection {
        LocalDate getDate();

        long getSuccessfulQueries();

        long getCacheHits();

        long getPromptTokens();

        long getCompletionTokens();
    }
}
