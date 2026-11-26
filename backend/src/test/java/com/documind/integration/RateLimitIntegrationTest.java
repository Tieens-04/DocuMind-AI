package com.documind.integration;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.Role;
import com.documind.exception.RateLimitExceededException;
import com.documind.exception.RateLimitStoreUnavailableException;
import com.documind.infrastructure.ratelimit.RateLimitOperation;
import com.documind.infrastructure.ratelimit.RateLimitProperties;
import com.documind.infrastructure.ratelimit.RateLimitService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.audit.AuditLogService;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.documind.support.Week2SseTestClient;
import com.documind.support.Week2SseTestClient.DedicatedSseConnection;
import com.documind.support.Week2SseTestClient.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.BufferedReader;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers verification suite for {@code DA-20} using REAL default Bucket4j quotas
 * ({@code 15/30/60} chat/min, {@code 5/10} upload/min), real Redis 7, real PostgreSQL 16 + pgvector,
 * MinIO, and fail-closed Bearer JWT authentication.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false",
        "documind.security.dev-caller-fallback.enabled=false"
    }
)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class,
    RateLimitIntegrationTest.ControllableRateLimitChatConfig.class
})
class RateLimitIntegrationTest {

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID HR_EMPLOYEE_USER_ID = AbstractIntegrationTest.HR_EMPLOYEE_USER_ID;
    private static final UUID HR_MANAGER_USER_ID = AbstractIntegrationTest.HR_MANAGER_USER_ID;
    private static final UUID IT_MANAGER_USER_ID = AbstractIntegrationTest.IT_MANAGER_USER_ID;

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
    private StorageProperties storageProperties;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ControllableChatModel controllableChatModel;

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
        controllableChatModel.reset();
        auditLogService.awaitPendingWrites(Duration.ofSeconds(5));
        // Ingestion workers are intentionally disabled in this suite, so uploaded rows stay PENDING.
        jdbcTemplate.update("UPDATE documents SET status = 'FAILED' WHERE status IN ('PENDING', 'PROCESSING')");
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
        jdbcTemplate.update(
            "UPDATE users SET role = 'DEPARTMENT_MANAGER', is_active = TRUE WHERE id = ?",
            HR_MANAGER_USER_ID
        );
    }

    @AfterEach
    void releaseAnyHeldStreams() {
        controllableChatModel.releaseAllHolds();
    }

    @Test
    void ac1ViewerEnforces15RequestsAcrossAskAndStreamReturns429WithRetryAfterBeforeSseAndIsolatesOtherUsers() throws Exception {
        String viewerJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String otherViewerJwt = client.login(port, Week2SseTestClient.SALES_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        for (int i = 1; i <= 15; i++) {
            if (i % 2 == 1) {
                HttpResponse<String> resp = client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/ask",
                    Map.of("question", "Out of scope viewer question " + i),
                    viewerJwt
                );
                assertThat(resp.statusCode()).as("Viewer ask #%d should succeed", i).isEqualTo(200);
            } else {
                List<SseFrame> frames = client.collectAllSseFrames(
                    port,
                    Map.of("question", "Out of scope viewer stream " + i),
                    viewerJwt
                );
                assertThat(frames.getLast().event()).isEqualTo("done");
            }
        }

        // 16th via /stream MUST return 429 ProblemDetail + Retry-After synchronously before SSE opens
        HttpResponse<InputStream> stream429 = client.openSseStream(
            port,
            Map.of("question", "Blocked stream 16"),
            viewerJwt
        );
        String stream429Body;
        try (InputStream bodyStream = stream429.body()) {
            stream429Body = new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(stream429.statusCode()).isEqualTo(429);
        assertThat(stream429.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
            .contains(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        int retryAfterStream = Integer.parseInt(stream429.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse("0"));
        assertThat(retryAfterStream).isPositive();
        JsonNode problemStream = objectMapper.readTree(stream429Body);
        assertThat(problemStream.path("errorCode").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(problemStream.path("retryAfterSeconds").asLong()).isPositive();

        // 17th via /ask also returns 429
        HttpResponse<String> ask429 = client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Blocked ask 17"), viewerJwt);
        assertThat(ask429.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(ask429.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse("0"))).isPositive();

        // Different user retains full quota
        HttpResponse<String> otherUserOk = client.sendJson(
            port,
            "POST",
            "/api/v1/chat/ask",
            Map.of("question", "Other viewer question"),
            otherViewerJwt
        );
        assertThat(otherUserOk.statusCode()).isEqualTo(200);

        auditLogService.awaitPendingWrites(Duration.ofSeconds(5));
        List<Map<String, Object>> rateAudits = jdbcTemplate.queryForList(
            """
            SELECT user_id, department_id, action_type, status_code, prompt_tokens, completion_tokens, cache_hit, cache_tier
            FROM audit_logs
            WHERE action_type = 'RATE_LIMITED'
            ORDER BY created_at ASC
            """
        );
        assertThat(rateAudits).hasSize(2);
        assertThat(rateAudits).allSatisfy(row -> {
            assertThat(row.get("user_id")).isEqualTo(HR_EMPLOYEE_USER_ID);
            assertThat(row.get("department_id")).isEqualTo(HR_DEPT_ID);
            assertThat(row.get("status_code")).isEqualTo(429);
            assertThat(row.get("prompt_tokens")).isEqualTo(0);
            assertThat(row.get("completion_tokens")).isEqualTo(0);
            assertThat(row.get("cache_hit")).isEqualTo(false);
            assertThat(row.get("cache_tier")).isEqualTo("NONE");
        });
        Integer chatAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND user_id = ?",
            Integer.class,
            HR_EMPLOYEE_USER_ID
        );
        assertThat(chatAuditCount).isEqualTo(15);
    }

    @Test
    void enforcesRoleChatAndIndependentUploadBoundariesAndViewer403OnUpload() throws Exception {
        String viewerJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String managerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        // Viewer upload returns 403 and never creates upload rate-limit bucket
        HttpResponse<String> viewerUpload = client.sendMultipart(
            port, "POST", "/api/v1/documents/upload", "denied.txt", "text/plain",
            "viewer-denied".getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, null, viewerJwt
        );
        assertThat(viewerUpload.statusCode()).isEqualTo(403);
        assertThat(stringRedisTemplate.hasKey(RateLimitService.KEY_PREFIX + "upload:" + HR_EMPLOYEE_USER_ID)).isFalse();

        // Manager Upload + Replace bucket = 5/min, 6th = 429, Chat bucket still full (30/30)!
        HttpResponse<String> firstManagerUpload = client.sendMultipart(
            port, "POST", "/api/v1/documents/upload", "mgr-0.txt", "text/plain",
            "mgr-upload-0".getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, null, managerJwt
        );
        assertThat(firstManagerUpload.statusCode()).isEqualTo(202);
        UUID managerDocId = objectMapper.readValue(firstManagerUpload.body(), DocumentResponse.class).id();

        for (int i = 1; i < 5; i++) {
            String method = (i % 2 == 1) ? "PUT" : "POST";
            String path = (i % 2 == 1) ? "/api/v1/documents/" + managerDocId : "/api/v1/documents/upload";
            HttpResponse<String> uploadResp = client.sendMultipart(
                port, method, path, "mgr-" + i + ".txt", "text/plain",
                ("mgr-upload-" + i).getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, null, managerJwt
            );
            assertThat(uploadResp.statusCode()).as("Manager upload/replace #%d", i + 1).isEqualTo(202);
        }
        HttpResponse<String> mgrUpload6 = client.sendMultipart(
            port, "POST", "/api/v1/documents/upload", "mgr-6.txt", "text/plain",
            "mgr-upload-6".getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, null, managerJwt
        );
        assertThat(mgrUpload6.statusCode()).isEqualTo(429);

        // Manager Chat bucket is completely independent (30 allowed, 31st -> 429)
        for (int i = 1; i <= 30; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Mgr q " + i), managerJwt).statusCode())
                .as("Manager chat #%d", i)
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Mgr q 31"), managerJwt).statusCode())
            .isEqualTo(429);

        // Admin Upload = 10/min (11th -> 429), Chat = 60/min (61st -> 429)
        for (int i = 1; i <= 10; i++) {
            HttpResponse<String> adminUpload = client.sendMultipart(
                port, "POST", "/api/v1/documents/upload", "adm-" + i + ".txt", "text/plain",
                ("adm-upload-" + i).getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, IT_DEPT_ID, adminJwt
            );
            assertThat(adminUpload.statusCode()).as("Admin upload #%d", i).isEqualTo(202);
        }
        HttpResponse<String> adminUpload11 = client.sendMultipart(
            port, "POST", "/api/v1/documents/upload", "adm-11.txt", "text/plain",
            "adm-upload-11".getBytes(StandardCharsets.UTF_8), AccessLevel.DEPARTMENT, IT_DEPT_ID, adminJwt
        );
        assertThat(adminUpload11.statusCode()).isEqualTo(429);

        for (int i = 1; i <= 60; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Admin q " + i), adminJwt).statusCode())
                .as("Admin chat #%d", i)
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Admin q 61"), adminJwt).statusCode())
            .isEqualTo(429);
    }

    @Test
    void updatesExistingRedisBucketConfigurationOnRolePromotionAndDemotion() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String viewerJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        // Exhaust Viewer (15/15 -> 429)
        for (int i = 1; i <= 15; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Promo q " + i), viewerJwt).statusCode())
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Promo blocked"), viewerJwt).statusCode())
            .isEqualTo(429);

        // Promote Viewer -> DEPARTMENT_MANAGER without flushing Redis
        HttpResponse<String> promoteResp = client.sendJson(
            port,
            "PATCH",
            "/api/v1/admin/users/" + HR_EMPLOYEE_USER_ID,
            Map.of("role", Role.DEPARTMENT_MANAGER.name()),
            adminJwt
        );
        assertThat(promoteResp.statusCode()).isEqualTo(200);

        String promotedJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        for (int i = 16; i <= 30; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Promoted q " + i), promotedJwt).statusCode())
                .as("Promoted request #%d should succeed via ADDITIVE upgrade", i)
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Promoted q 31"), promotedJwt).statusCode())
            .isEqualTo(429);

        // Demote IT Manager (30 -> 15) after already consuming 26 (>= 15): MUST reject immediately with 429 (0 extra 200s)
        String itMgrJwt = client.login(port, Week2SseTestClient.IT_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        for (int i = 1; i <= 26; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Demote warm " + i), itMgrJwt).statusCode())
                .isEqualTo(200);
        }
        HttpResponse<String> demoteResp = client.sendJson(
            port,
            "PATCH",
            "/api/v1/admin/users/" + IT_MANAGER_USER_ID,
            Map.of("role", Role.STANDARD_EMPLOYEE.name()),
            adminJwt
        );
        assertThat(demoteResp.statusCode()).isEqualTo(200);
        String demotedJwt = client.login(port, Week2SseTestClient.IT_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        HttpResponse<String> overQuotaDemoted = client.sendJson(
            port, "POST", "/api/v1/chat/ask", Map.of("question", "Demoted over-quota probe"), demotedJwt
        );
        assertThat(overQuotaDemoted.statusCode()).isEqualTo(429);
        assertThat(Integer.parseInt(overQuotaDemoted.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse("0"))).isPositive();

        // Demote HR Manager (30 -> 15) after consuming 10 (< 15): MUST get exact 5 remaining (10 + 5 = 15), 6th -> 429
        String hrMgrJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        for (int i = 1; i <= 10; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Partial demote warm " + i), hrMgrJwt).statusCode())
                .isEqualTo(200);
        }
        HttpResponse<String> hrDemoteResp = client.sendJson(
            port,
            "PATCH",
            "/api/v1/admin/users/" + HR_MANAGER_USER_ID,
            Map.of("role", Role.STANDARD_EMPLOYEE.name()),
            adminJwt
        );
        assertThat(hrDemoteResp.statusCode()).isEqualTo(200);
        String hrDemotedJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        for (int i = 11; i <= 15; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Partial demoted ok " + i), hrDemotedJwt).statusCode())
                .as("Demoted HR Manager request #%d within 15/min window should succeed", i)
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Partial demoted blocked 16"), hrDemotedJwt).statusCode())
            .isEqualTo(429);
    }

    @Test
    void doesNotConsumeChatQuotaOnPreFlightRbac403OrCrossUserSession404() throws Exception {
        String viewerJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();
        String otherViewerJwt = client.login(port, Week2SseTestClient.SALES_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        ChatAskResponse otherSessionTurn = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Other user session seed"), otherViewerJwt).body(),
            ChatAskResponse.class
        );
        UUID otherUsersSessionId = otherSessionTurn.sessionId();
        String viewerChatKey = RateLimitService.KEY_PREFIX + "chat:" + HR_EMPLOYEE_USER_ID;

        // 403 (non-admin passing departmentId) on /ask and /stream must NOT consume quota
        HttpResponse<String> ask403 = client.sendJson(
            port, "POST", "/api/v1/chat/ask", Map.of("question", "Forbidden dept filter", "departmentId", IT_DEPT_ID.toString()), viewerJwt
        );
        assertThat(ask403.statusCode()).isEqualTo(403);
        HttpResponse<InputStream> stream403 = client.openSseStream(
            port, Map.of("question", "Forbidden stream dept filter", "departmentId", IT_DEPT_ID.toString()), viewerJwt
        );
        try (InputStream ignored = stream403.body()) {
            assertThat(stream403.statusCode()).isEqualTo(403);
        }

        // 404 (cross-user sessionId) on /ask and /stream must NOT consume quota
        HttpResponse<String> ask404 = client.sendJson(
            port, "POST", "/api/v1/chat/ask", Map.of("question", "Foreign session", "sessionId", otherUsersSessionId.toString()), viewerJwt
        );
        assertThat(ask404.statusCode()).isEqualTo(404);
        HttpResponse<InputStream> stream404 = client.openSseStream(
            port, Map.of("question", "Foreign stream session", "sessionId", otherUsersSessionId.toString()), viewerJwt
        );
        try (InputStream ignored = stream404.body()) {
            assertThat(stream404.statusCode()).isEqualTo(404);
        }

        assertThat(stringRedisTemplate.hasKey(viewerChatKey)).isFalse();

        // Viewer still retains all 15 valid chat requests before hitting 429 on #16
        for (int i = 1; i <= 15; i++) {
            assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Valid after pre-flight rejects " + i), viewerJwt).statusCode())
                .as("Valid request #%d should succeed", i)
                .isEqualTo(200);
        }
        assertThat(client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Valid 16 blocked"), viewerJwt).statusCode())
            .isEqualTo(429);
    }

    @Test
    void enforcesExactQuotaAcrossTwoDistributedLimiterInstancesRefillExpirationAndFailClosed503() throws Exception {
        RedisURI redisUri = RedisURI.builder()
            .withHost(RedisTestcontainersConfig.sharedHost())
            .withPort(RedisTestcontainersConfig.sharedMappedPort())
            .withTimeout(Duration.ofMillis(500))
            .build();
        RedisClient clientA = RedisClient.create();
        RedisClient clientB = RedisClient.create();
        try (
            RateLimitService limiterA = new RateLimitService(clientA, redisUri, auditLogService, RateLimitProperties.defaults());
            RateLimitService limiterB = new RateLimitService(clientB, redisUri, auditLogService, RateLimitProperties.defaults())
        ) {
            UUID burstUserId = UUID.randomUUID();
            CallerContext burstCaller = new CallerContext(burstUserId, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.1.0.5");

            int threads = 40;
            CyclicBarrier barrier = new CyclicBarrier(threads);
            AtomicInteger allowed = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>(threads);
                for (int i = 0; i < threads; i++) {
                    final RateLimitService target = (i % 2 == 0) ? limiterA : limiterB;
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try {
                            target.consume(RateLimitOperation.CHAT, burstCaller, HR_DEPT_ID, "/api/v1/chat/ask");
                            allowed.incrementAndGet();
                        } catch (RateLimitExceededException ex) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    f.get(15, TimeUnit.SECONDS);
                }
            }
            assertThat(allowed.get()).isEqualTo(15);
            assertThat(rejected.get()).isEqualTo(25);
        } finally {
            clientA.shutdown();
            clientB.shutdown();
        }

        // Refill + Redis TTL key expiration test using 1s period + 300ms buffer
        RateLimitProperties shortWindowProps = new RateLimitProperties(
            true,
            Duration.ofMillis(300),
            new RateLimitProperties.ChatLimits(Duration.ofSeconds(1), 15, 30, 60),
            RateLimitProperties.UploadLimits.defaults()
        );
        RedisClient ttlClient = RedisClient.create();
        try (RateLimitService shortLimiter = new RateLimitService(ttlClient, redisUri, null, shortWindowProps)) {
            CallerContext refillCaller = new CallerContext(UUID.randomUUID(), HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "10.1.0.6");
            String redisKey = RateLimitService.buildBucketKey(RateLimitOperation.CHAT, refillCaller);
            for (int i = 0; i < 15; i++) {
                shortLimiter.consume(RateLimitOperation.CHAT, refillCaller, HR_DEPT_ID, "/api/v1/chat/ask");
            }
            assertThatThrownBy(() -> shortLimiter.consume(RateLimitOperation.CHAT, refillCaller, HR_DEPT_ID, "/api/v1/chat/ask"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(ex -> assertThat(((RateLimitExceededException) ex).retryAfterSeconds()).isEqualTo(1L));

            Long ttlMs = stringRedisTemplate.getExpire(redisKey, TimeUnit.MILLISECONDS);
            assertThat(ttlMs).isNotNull().isPositive().isLessThanOrEqualTo(1_500L);

            Thread.sleep(1_150L);
            shortLimiter.consume(RateLimitOperation.CHAT, refillCaller, HR_DEPT_ID, "/api/v1/chat/ask");

            Thread.sleep(1_500L);
            assertThat(stringRedisTemplate.hasKey(redisKey)).isFalse();
        } finally {
            ttlClient.shutdown();
        }

        // Fail-closed 503 check against an isolated paused & stopped Redis container
        try (GenericContainer<?> isolatedRedis = new GenericContainer<>(DockerImageName.parse(RedisTestcontainersConfig.REDIS_IMAGE))
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())) {
            isolatedRedis.start();
            RedisURI isolatedUri = RedisURI.builder()
                .withHost(isolatedRedis.getHost())
                .withPort(isolatedRedis.getMappedPort(6379))
                .withTimeout(Duration.ofMillis(300))
                .build();
            RedisClient isolatedClient = RedisClient.create();
            try (RateLimitService failingLimiter = new RateLimitService(isolatedClient, isolatedUri, null, RateLimitProperties.defaults())) {
                CallerContext probeCaller = new CallerContext(UUID.randomUUID(), HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
                failingLimiter.consume(RateLimitOperation.CHAT, probeCaller, HR_DEPT_ID, "/api/v1/chat/ask");

                String containerId = isolatedRedis.getContainerId();
                isolatedRedis.getDockerClient().pauseContainerCmd(containerId).exec();
                try {
                    assertThatThrownBy(() -> failingLimiter.consume(RateLimitOperation.CHAT, probeCaller, HR_DEPT_ID, "/api/v1/chat/ask"))
                        .isInstanceOf(RateLimitStoreUnavailableException.class);
                } finally {
                    isolatedRedis.getDockerClient().unpauseContainerCmd(containerId).exec();
                }

                isolatedRedis.stop();
                assertThatThrownBy(() -> failingLimiter.consume(RateLimitOperation.CHAT, probeCaller, HR_DEPT_ID, "/api/v1/chat/ask"))
                    .isInstanceOf(RateLimitStoreUnavailableException.class);
            } finally {
                isolatedClient.shutdown();
            }
        }
    }

    @Test
    void recordsAccurateAsyncAuditsAcrossTurn1FollowUpL1L2Refusal502DisconnectAndRollsBackDbFailure() throws Exception {
        UUID docId = UUID.randomUUID();
        String leaveText = "Chính sách nghỉ phép năm: nhân viên chính thức được hưởng 14 ngày phép năm.";
        String insuranceText = "Chính sách bảo hiểm sức khỏe PVI Care: hỗ trợ khám sức khỏe định kỳ và điều trị nội trú.";
        String remoteText = "Quy định làm việc từ xa (remote work) và hybrid work: khung giờ phối hợp bắt buộc từ 09:30.";
        byte[] content = String.join("\n", leaveText, insuranceText, remoteText).getBytes(StandardCharsets.UTF_8);
        jdbcTemplate.update(
            """
            INSERT INTO documents (id, department_id, uploaded_by, original_name, storage_key, file_extension, mime_type,
                                   file_size_bytes, file_hash, access_level, status, current_version, chunk_count)
            VALUES (?, ?, ?, 'hr-leave.txt', ?, 'txt', 'text/plain', ?, ?, 'DEPARTMENT', 'COMPLETED', 1, 3)
            """,
            docId,
            HR_DEPT_ID,
            HR_EMPLOYEE_USER_ID,
            "departments/" + HR_DEPT_ID + "/" + docId + "/v1/hr-leave.txt",
            content.length,
            "f".repeat(64)
        );
        vectorStore.add(List.of(
            new Document(
                UUID.randomUUID().toString(),
                leaveText,
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, docId.toString(),
                    ChunkMetadataKeys.FILE_NAME, "hr-leave.txt",
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name(),
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 0,
                    ChunkMetadataKeys.SECTION_HEADING, "Nghỉ phép"
                )
            ),
            new Document(
                UUID.randomUUID().toString(),
                insuranceText,
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, docId.toString(),
                    ChunkMetadataKeys.FILE_NAME, "hr-leave.txt",
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name(),
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 1,
                    ChunkMetadataKeys.SECTION_HEADING, "Bảo hiểm sức khỏe"
                )
            ),
            new Document(
                UUID.randomUUID().toString(),
                remoteText,
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, docId.toString(),
                    ChunkMetadataKeys.FILE_NAME, "hr-leave.txt",
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name(),
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 2,
                    ChunkMetadataKeys.SECTION_HEADING, "Làm việc từ xa"
                )
            )
        ));

        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, Week2SseTestClient.SEED_DEV_PASSWORD).accessToken();

        // 1. Turn 1 -> 200 NONE
        ChatAskResponse t1 = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Chính sách nghỉ phép năm 2026 là bao nhiêu ngày?"), hrEmployeeJwt).body(),
            ChatAskResponse.class
        );
        assertThat(t1.sessionId()).isNotNull();
        assertThat(t1.messageId()).isNotNull();

        // 2. Follow-up in same session -> 200 NONE
        ChatAskResponse t2 = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Còn thử việc thì sao?", "sessionId", t1.sessionId().toString()), hrEmployeeJwt).body(),
            ChatAskResponse.class
        );
        assertThat(t2.sessionId()).isEqualTo(t1.sessionId());

        // 3a. Exact repeat -> L1 cache hit (0 tokens)
        ChatAskResponse l1 = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Chính sách nghỉ phép năm 2026 là bao nhiêu ngày?"), hrEmployeeJwt).body(),
            ChatAskResponse.class
        );
        assertThat(l1.cacheHit()).isTrue();
        assertThat(l1.cacheTier().name()).isEqualTo("L1");

        // 3b. Semantic paraphrase on same concept -> L2 cache hit (0 tokens)
        ChatAskResponse l2 = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Số ngày nghỉ phép năm của nhân viên chính thức?"), hrEmployeeJwt).body(),
            ChatAskResponse.class
        );
        assertThat(l2.cacheHit()).isTrue();
        assertThat(l2.cacheTier().name()).isEqualTo("L2");

        // 4. Out-of-scope refusal -> abstained=true
        ChatAskResponse refusal = objectMapper.readValue(
            client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", "Giá vàng SJC hôm nay?"), hrEmployeeJwt).body(),
            ChatAskResponse.class
        );
        assertThat(refusal.abstained()).isTrue();

        // 5. Provider 502 failure on distinct concept subspace (bypasses Turn 1 L1/L2 cache) -> status 502 audit recorded asynchronously
        controllableChatModel.failNextCall();
        HttpResponse<String> failed502 = client.sendJson(
            port,
            "POST",
            "/api/v1/chat/ask",
            Map.of("question", "Chính sách bảo hiểm sức khỏe PVI Care có quyền lợi gì?"),
            hrEmployeeJwt
        );
        assertThat(failed502.statusCode()).isEqualTo(502);

        // 6. SSE Client disconnect on distinct concept subspace -> 499 audit without session/message rows
        int sessionsBeforeDisconnect = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
        int messagesBeforeDisconnect = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class);
        ControllableStreamHandle abortHandle = controllableChatModel.armControlledStream();
        try (DedicatedSseConnection conn = client.openDedicatedSseStream(
            port,
            Map.of("question", "Quy định làm việc từ xa remote work áp dụng khung giờ phối hợp nào? [CONTROLLED]"),
            hrEmployeeJwt
        ); BufferedReader reader = Week2SseTestClient.openUtf8Reader(conn.response())) {
            assertThat(conn.response().statusCode()).isEqualTo(200);
            SseFrame citations = Week2SseTestClient.readNextFrame(reader);
            assertThat(citations).isNotNull();
            assertThat(citations.event()).isEqualTo("citations");
            assertThat(abortHandle.subscribedLatch().await(5, TimeUnit.SECONDS)).isTrue();
        }
        for (int i = 0; i < 20 && abortHandle.cancelledLatch().getCount() > 0; i++) {
            abortHandle.sink().tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("abort-probe-" + "x".repeat(512))))));
            if (abortHandle.cancelledLatch().await(80, TimeUnit.MILLISECONDS)) {
                break;
            }
        }
        assertThat(abortHandle.cancelledLatch().await(5, TimeUnit.SECONDS)).isTrue();

        awaitChatAuditCount(499, 1, Duration.ofSeconds(5));
        auditLogService.awaitPendingWrites(Duration.ofSeconds(5));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isEqualTo(sessionsBeforeDisconnect);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(messagesBeforeDisconnect);
        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
            """
            SELECT status_code, cache_hit, cache_tier, abstained, prompt_tokens, completion_tokens, latency_ms, user_id, department_id
            FROM audit_logs
            WHERE action_type = 'CHAT_QUERY'
            ORDER BY created_at ASC
            """
        );
        assertThat(audits).hasSize(7);
        assertThat(audits).allSatisfy(row -> {
            assertThat(row.get("user_id")).isEqualTo(HR_EMPLOYEE_USER_ID);
            assertThat(row.get("department_id")).isEqualTo(HR_DEPT_ID);
            assertThat(((Number) row.get("latency_ms")).longValue()).isGreaterThanOrEqualTo(0L);
        });
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("cache_hit")).isEqualTo(true);
            assertThat(row.get("cache_tier")).isEqualTo("L1");
            assertThat(row.get("prompt_tokens")).isEqualTo(0);
            assertThat(row.get("completion_tokens")).isEqualTo(0);
        });
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("cache_hit")).isEqualTo(true);
            assertThat(row.get("cache_tier")).isEqualTo("L2");
            assertThat(row.get("prompt_tokens")).isEqualTo(0);
            assertThat(row.get("completion_tokens")).isEqualTo(0);
        });
        assertThat(audits).anySatisfy(row -> assertThat(row.get("abstained")).isEqualTo(true));
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("status_code")).isEqualTo(502);
            assertThat(row.get("cache_hit")).isEqualTo(false);
            assertThat(row.get("cache_tier")).isEqualTo("NONE");
        });
        assertThat(audits).anySatisfy(row -> {
            assertThat(row.get("status_code")).isEqualTo(499);
            assertThat(row.get("cache_hit")).isEqualTo(false);
            assertThat(row.get("cache_tier")).isEqualTo("NONE");
        });

        // 7a. DB error rollback check (Turn-1 CTE): non-existent user FK fails atomically (zero partial rows)
        int sessionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
        int messagesBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class);
        int auditsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
        UUID nonexistentUser = UUID.randomUUID();
        assertThatThrownBy(() -> AuditLogService.awaitTurnPersistence(auditLogService.recordCompletedTurnAsync(
            new AuditLogService.CompletedChatTurnPayload(
                nonexistentUser,
                HR_DEPT_ID,
                null,
                "Rollback probe",
                "Rollback probe?",
                null,
                "Never persisted",
                List.of(),
                false,
                "groq",
                com.documind.domain.enums.CacheTier.NONE,
                50,
                20,
                10L,
                "127.0.0.1",
                OffsetDateTime.now(ZoneOffset.UTC),
                true
            )
        ))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isEqualTo(sessionsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(messagesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(auditsBefore);

        // 7b. DB error rollback check (Follow-up transaction): non-existent department FK rolls back USER + ASSISTANT messages + audit atomically
        UUID nonexistentDept = UUID.randomUUID();
        assertThatThrownBy(() -> AuditLogService.awaitTurnPersistence(auditLogService.recordCompletedTurnAsync(
            new AuditLogService.CompletedChatTurnPayload(
                HR_EMPLOYEE_USER_ID,
                nonexistentDept,
                t1.sessionId(),
                "Follow-up rollback",
                "Follow-up rollback?",
                "Follow-up rollback?",
                "Never persisted follow-up",
                List.of(),
                false,
                "groq",
                com.documind.domain.enums.CacheTier.NONE,
                60,
                25,
                15L,
                "127.0.0.1",
                OffsetDateTime.now(ZoneOffset.UTC),
                true
            )
        ))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isEqualTo(sessionsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(messagesBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isEqualTo(auditsBefore);
    }

    private void awaitChatAuditCount(int statusCode, int expectedCount, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Integer last = 0;
        while (System.nanoTime() < deadlineNanos) {
            last = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND status_code = ?",
                Integer.class,
                statusCode
            );
            if (last != null && last >= expectedCount) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("Timed out waiting for CHAT_QUERY status_code=" + statusCode
            + " audit count >= " + expectedCount + " (last=" + last + ")");
    }

    record ControllableStreamHandle(
        Sinks.Many<ChatResponse> sink,
        CountDownLatch subscribedLatch,
        CountDownLatch cancelledLatch
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControllableRateLimitChatConfig {

        @Bean
        @Primary
        ControllableChatModel controllableChatModel() {
            return new ControllableChatModel();
        }

        @Bean
        static BeanPostProcessor overrideRateLimitTestChatClient(
            ObjectProvider<ControllableChatModel> modelProvider
        ) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof ChatClient) {
                        return ChatClient.create(modelProvider.getObject());
                    }
                    if (bean instanceof ChatClient.Builder) {
                        return ChatClient.builder(modelProvider.getObject());
                    }
                    return bean;
                }
            };
        }
    }

    static final class ControllableChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private final AtomicBoolean failNext = new AtomicBoolean(false);
        private volatile ControllableStreamHandle pendingStreamHandle;

        void failNextCall() {
            failNext.set(true);
        }

        ControllableStreamHandle armControlledStream() {
            ControllableStreamHandle handle = new ControllableStreamHandle(
                Sinks.many().unicast().onBackpressureBuffer(),
                new CountDownLatch(1),
                new CountDownLatch(1)
            );
            this.pendingStreamHandle = handle;
            return handle;
        }

        void reset() {
            failNext.set(false);
            releaseAllHolds();
        }

        void releaseAllHolds() {
            ControllableStreamHandle handle = pendingStreamHandle;
            if (handle != null) {
                handle.sink().tryEmitComplete();
                pendingStreamHandle = null;
            }
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (failNext.compareAndSet(true, false)) {
                throw new RuntimeException("Simulated 502 provider outage");
            }
            String systemText = prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElse("");
            Matcher matcher = CITATION_ATTR_PATTERN.matcher(systemText);
            String firstCitation = matcher.find() ? matcher.group(1) : "[Nguồn: document]";
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Theo tài liệu nội bộ " + firstCitation + "."))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(120, 40, 160)).build()
            );
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            String userText = prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .reduce("", (a, b) -> b);
            ControllableStreamHandle handle = pendingStreamHandle;
            if (userText.contains("[CONTROLLED]") && handle != null) {
                pendingStreamHandle = null;
                return handle.sink().asFlux()
                    .doOnSubscribe(sub -> handle.subscribedLatch().countDown())
                    .doOnCancel(() -> handle.cancelledLatch().countDown());
            }
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Theo tài liệu ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("nội bộ.")))),
                new ChatResponse(List.of(), ChatResponseMetadata.builder().usage(new DefaultUsage(100, 30, 130)).build())
            );
        }
    }
}
