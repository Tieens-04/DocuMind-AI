package com.documind.integration;

import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.admin.AdminAnalyticsSummaryResponse;
import com.documind.controller.dto.admin.AdminAuditLogItemResponse;
import com.documind.controller.dto.admin.AdminDailyTrendsResponse;
import com.documind.controller.dto.admin.AdminDepartmentTokenUsageReportResponse;
import com.documind.domain.enums.AuditActionType;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.Week2SseTestClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers verification suite for {@code DA-20} Admin FinOps Analytics
 * against real PostgreSQL 16, Redis 7, and fail-closed Bearer JWT authentication.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false",
        "documind.security.dev-caller-fallback.enabled=false",
        "documind.rate-limit.chat.viewer-limit=500",
        "documind.rate-limit.chat.manager-limit=500",
        "documind.rate-limit.chat.admin-limit=500"
    }
)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
class AdminAnalyticsIntegrationTest {

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID ADMIN_USER_ID = AbstractIntegrationTest.ADMIN_USER_ID;
    private static final UUID HR_MANAGER_USER_ID = AbstractIntegrationTest.HR_MANAGER_USER_ID;
    private static final UUID HR_EMPLOYEE_USER_ID = AbstractIntegrationTest.HR_EMPLOYEE_USER_ID;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private com.documind.infrastructure.storage.StorageProperties storageProperties;

    private Week2SseTestClient client;

    @BeforeAll
    void initClient() {
        client = new Week2SseTestClient(objectMapper);
    }

    @AfterAll
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void resetState() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
    }

    @Test
    void enforcesSystemAdminRbacAcrossAllFourAnalyticsEndpoints() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String managerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String viewerJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        List<String> endpoints = List.of(
            "/api/v1/admin/analytics/summary",
            "/api/v1/admin/analytics/token-by-department",
            "/api/v1/admin/analytics/daily-trends",
            "/api/v1/admin/analytics/audit-logs"
        );

        for (String endpoint : endpoints) {
            assertThat(client.sendJson(port, "GET", endpoint, null, null).statusCode()).isEqualTo(401);
            assertThat(client.sendJson(port, "GET", endpoint, null, viewerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendJson(port, "GET", endpoint, null, managerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendJson(port, "GET", endpoint, null, adminJwt).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void computesExactCacheHitRatioDepartmentTotalsNullGroupCrossMidnightUtcAnd64BitSums() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        // Empty dataset check first
        String windowParams = "from=2026-11-23T00:00:00Z&to=2026-11-25T00:00:00Z";
        HttpResponse<String> emptySummaryRaw = client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?" + windowParams, null, adminJwt);
        assertThat(emptySummaryRaw.statusCode()).isEqualTo(200);
        AdminAnalyticsSummaryResponse emptySummary = objectMapper.readValue(emptySummaryRaw.body(), AdminAnalyticsSummaryResponse.class);
        assertThat(emptySummary.successfulQueries()).isZero();
        assertThat(emptySummary.cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("0.00"));
        assertThat(emptySummary.totalTokens()).isZero();

        AdminDepartmentTokenUsageReportResponse emptyByDept = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/token-by-department?" + windowParams, null, adminJwt).body(),
            AdminDepartmentTokenUsageReportResponse.class
        );
        assertThat(emptyByDept.items()).isEmpty();

        AdminDailyTrendsResponse emptyTrends = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/daily-trends?" + windowParams, null, adminJwt).body(),
            AdminDailyTrendsResponse.class
        );
        assertThat(emptyTrends.items()).isEmpty();

        // Seed 3 successful CHAT_QUERY (1 hit, 1 miss, 1 abstained refusal), 1 failed 502 CHAT_QUERY with tokens,
        // 1 non-chat DOC_UPLOAD, 1 RATE_LIMITED, plus boundary rows outside [from, to)
        insertAudit(UUID.randomUUID(), HR_EMPLOYEE_USER_ID, HR_DEPT_ID, "CHAT_QUERY", "Q1 hit", false, true, "L1", 0, 0, 8L, "groq", 200, "2026-11-23T23:59:59Z");
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, IT_DEPT_ID, "CHAT_QUERY", "Q2 miss +07 crossing", false, false, "NONE", 100, 40, 120L, "groq", 200, "2026-11-24T01:30:00+07:00");
        insertAudit(UUID.randomUUID(), HR_MANAGER_USER_ID, HR_DEPT_ID, "CHAT_QUERY", "Q3 refusal", true, false, "NONE", 20, 5, 30L, "groq", 200, "2026-11-24T00:00:00Z");
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, null, "CHAT_QUERY", "Q4 502 null dept", true, false, "NONE", 60, 15, 210L, "groq", 502, "2026-11-24T12:00:00Z");
        insertAudit(UUID.randomUUID(), HR_MANAGER_USER_ID, HR_DEPT_ID, "DOC_UPLOAD", "policy.pdf", false, false, "NONE", 999, 999, 50L, null, 202, "2026-11-24T13:00:00Z");
        insertAudit(UUID.randomUUID(), HR_EMPLOYEE_USER_ID, HR_DEPT_ID, "RATE_LIMITED", "RATE_LIMIT:CHAT", false, false, "NONE", 0, 0, 2L, null, 429, "2026-11-24T13:05:00Z");
        // Excluded boundary records (before from, and exact to)
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, IT_DEPT_ID, "CHAT_QUERY", "Before window", false, true, "L1", 500, 500, 10L, "groq", 200, "2026-11-22T23:59:59Z");
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, IT_DEPT_ID, "CHAT_QUERY", "At exclusive to", false, true, "L1", 500, 500, 10L, "groq", 200, "2026-11-25T00:00:00Z");

        AdminAnalyticsSummaryResponse summary = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?" + windowParams, null, adminJwt).body(),
            AdminAnalyticsSummaryResponse.class
        );
        assertThat(summary.successfulQueries()).isEqualTo(3L);
        assertThat(summary.cacheHits()).isEqualTo(1L);
        assertThat(summary.cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(summary.promptTokens()).isEqualTo(180L);
        assertThat(summary.completionTokens()).isEqualTo(60L);
        assertThat(summary.totalTokens()).isEqualTo(240L);

        AdminDepartmentTokenUsageReportResponse byDept = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/token-by-department?" + windowParams, null, adminJwt).body(),
            AdminDepartmentTokenUsageReportResponse.class
        );
        assertThat(byDept.items()).hasSize(3);
        assertThat(byDept.items().get(0).departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(byDept.items().get(0).departmentCode()).isEqualTo("IT");
        assertThat(byDept.items().get(0).promptTokens()).isEqualTo(100L);
        assertThat(byDept.items().get(0).completionTokens()).isEqualTo(40L);
        assertThat(byDept.items().get(0).totalTokens()).isEqualTo(140L);
        assertThat(byDept.items().get(1).departmentId()).isNull();
        assertThat(byDept.items().get(1).successfulQueries()).isZero();
        assertThat(byDept.items().get(1).promptTokens()).isEqualTo(60L);
        assertThat(byDept.items().get(1).completionTokens()).isEqualTo(15L);
        assertThat(byDept.items().get(1).totalTokens()).isEqualTo(75L);
        assertThat(byDept.items().get(2).departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(byDept.items().get(2).successfulQueries()).isEqualTo(2L);
        assertThat(byDept.items().get(2).cacheHits()).isEqualTo(1L);
        assertThat(byDept.items().get(2).cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(byDept.items().get(2).promptTokens()).isEqualTo(20L);
        assertThat(byDept.items().get(2).completionTokens()).isEqualTo(5L);
        assertThat(byDept.items().get(2).totalTokens()).isEqualTo(25L);

        AdminDailyTrendsResponse trends = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/daily-trends?" + windowParams, null, adminJwt).body(),
            AdminDailyTrendsResponse.class
        );
        assertThat(trends.items()).hasSize(2);
        assertThat(trends.items().get(0).date()).isEqualTo(LocalDate.of(2026, 11, 23));
        assertThat(trends.items().get(0).successfulQueries()).isEqualTo(2L);
        assertThat(trends.items().get(0).cacheHits()).isEqualTo(1L);
        assertThat(trends.items().get(0).cacheHitRatioPercent()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(trends.items().get(0).totalTokens()).isEqualTo(140L);

        assertThat(trends.items().get(1).date()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(trends.items().get(1).successfulQueries()).isEqualTo(1L);
        assertThat(trends.items().get(1).cacheHits()).isZero();
        assertThat(trends.items().get(1).totalTokens()).isEqualTo(100L);

        // 64-bit SUM (> Integer.MAX_VALUE) across rows
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, IT_DEPT_ID, "CHAT_QUERY", "Large 1", false, false, "NONE", 1_500_000_000, 100_000_000, 50L, "groq", 200, "2026-11-26T01:00:00Z");
        insertAudit(UUID.randomUUID(), ADMIN_USER_ID, IT_DEPT_ID, "CHAT_QUERY", "Large 2", false, false, "NONE", 1_500_000_000, 100_000_000, 50L, "groq", 200, "2026-11-26T02:00:00Z");
        AdminAnalyticsSummaryResponse bigSum = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?from=2026-11-26T00:00:00Z&to=2026-11-27T00:00:00Z", null, adminJwt).body(),
            AdminAnalyticsSummaryResponse.class
        );
        assertThat(bigSum.promptTokens()).isEqualTo(3_000_000_000L);
        assertThat(bigSum.completionTokens()).isEqualTo(200_000_000L);
        assertThat(bigSum.totalTokens()).isEqualTo(3_200_000_000L);
    }

    @Test
    void supportsStableAuditLogPaginationFiltersAndValidatesBadParameters() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        List<UUID> seededIds = new ArrayList<>();
        String sharedTimestamp = "2025-05-10T15:00:00Z";
        for (int i = 0; i < 25; i++) {
            UUID id = UUID.randomUUID();
            seededIds.add(id);
            String action = (i % 5 == 0) ? "RATE_LIMITED" : "CHAT_QUERY";
            int status = (i % 5 == 0) ? 429 : 200;
            insertAudit(id, HR_EMPLOYEE_USER_ID, HR_DEPT_ID, action, "Item " + i, false, false, "NONE", 10, 5, 10L, "groq", status, sharedTimestamp);
        }
        seededIds.sort(Comparator.comparing(UUID::toString).reversed());

        // Default size = 20, deterministic tie-breaking by created_at DESC, id DESC across pages
        PageResponse<AdminAuditLogItemResponse> page0 = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?from=2025-05-10T00:00:00Z&to=2025-05-11T00:00:00Z", null, adminJwt).body(),
            new TypeReference<>() {}
        );
        PageResponse<AdminAuditLogItemResponse> page1 = objectMapper.readValue(
            client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?from=2025-05-10T00:00:00Z&to=2025-05-11T00:00:00Z&page=1", null, adminJwt).body(),
            new TypeReference<>() {}
        );
        assertThat(page0.size()).isEqualTo(20);
        assertThat(page0.totalElements()).isEqualTo(25L);
        assertThat(page0.totalPages()).isEqualTo(2);
        assertThat(page0.items()).hasSize(20);
        assertThat(page1.items()).hasSize(5);

        List<UUID> combinedIds = new ArrayList<>();
        page0.items().forEach(item -> combinedIds.add(item.id()));
        page1.items().forEach(item -> combinedIds.add(item.id()));
        assertThat(combinedIds).containsExactlyElementsOf(seededIds);

        PageResponse<AdminAuditLogItemResponse> filtered = objectMapper.readValue(
            client.sendJson(
                port,
                "GET",
                "/api/v1/admin/analytics/audit-logs?from=2025-05-10T00:00:00Z&to=2025-05-11T00:00:00Z&actionType=RATE_LIMITED&statusCode=429&departmentId=" + HR_DEPT_ID,
                null,
                adminJwt
            ).body(),
            new TypeReference<>() {}
        );
        assertThat(filtered.totalElements()).isEqualTo(5L);
        assertThat(filtered.items()).allSatisfy(item -> {
            assertThat(item.actionType()).isEqualTo(AuditActionType.RATE_LIMITED);
            assertThat(item.statusCode()).isEqualTo(429);
            assertThat(item.departmentCode()).isEqualTo("HR");
            assertThat(item.userEmail()).isEqualTo(Week2SseTestClient.HR_EMPLOYEE_EMAIL);
        });

        // Parameter validation checks -> 400
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?from=2026-11-25T00:00:00Z&to=2026-11-24T00:00:00Z", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?from=2025-01-01T00:00:00Z&to=2026-11-25T00:00:00Z", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/summary?from=not-a-timestamp", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?page=-1", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?size=0", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?size=101", null, adminJwt).statusCode())
            .isEqualTo(400);
        assertThat(client.sendJson(port, "GET", "/api/v1/admin/analytics/audit-logs?statusCode=999", null, adminJwt).statusCode())
            .isEqualTo(400);
    }

    private void insertAudit(
        UUID id,
        UUID userId,
        UUID departmentId,
        String actionType,
        String queryText,
        boolean abstained,
        boolean cacheHit,
        String cacheTier,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String provider,
        int statusCode,
        String createdAtIso
    ) {
        OffsetDateTime ts = OffsetDateTime.parse(createdAtIso).withOffsetSameInstant(ZoneOffset.UTC);
        jdbcTemplate.update(
            """
            INSERT INTO audit_logs (
                id, user_id, department_id, session_id, action_type, query_text,
                abstained, cache_hit, cache_tier, prompt_tokens, completion_tokens,
                latency_ms, provider, status_code, ip_address, created_at
            ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '127.0.0.1', ?)
            """,
            id,
            userId,
            departmentId,
            actionType,
            queryText,
            abstained,
            cacheHit,
            cacheTier,
            promptTokens,
            completionTokens,
            latencyMs,
            provider,
            statusCode,
            Timestamp.from(ts.toInstant())
        );
    }
}
