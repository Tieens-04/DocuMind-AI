package com.documind.integration;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.ai.AiProperties;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.security.DocumentPreviewTokenService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.infrastructure.vector.SemanticCacheVectorRepository;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.document.CallerContext;
import com.documind.service.rag.GroundedSystemPromptService;
import com.documind.service.rag.QueryContextualizerService;
import com.documind.service.rag.RagCacheService;
import com.documind.service.rag.RagChatService;
import com.documind.service.rag.RagConstants;
import com.documind.service.rag.RagProperties;
import com.documind.service.rag.RbacVectorFilterFactory;
import com.documind.service.rag.RbacVectorScope;
import com.documind.service.rag.VectorRetrievalService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.documind.support.Week2SseTestClient;
import com.documind.support.Week2SseTestClient.SseFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers verification suite for {@code DA-19} (Two-Tier RBAC-Scoped RAG Cache:
 * Redis L1 Exact + PostgreSQL {@code pgvector} L2 Semantic + Authoritative Post-Commit Invalidation)
 * against real PostgreSQL 16 ({@code pgvector}), Redis 7, MinIO, real Bearer JWT auth
 * ({@code dev-caller-fallback.enabled=false}), and real HTTP/1.1 SSE + REST endpoints.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true",
        "documind.security.dev-caller-fallback.enabled=false"
    }
)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    ScopedRagCacheIntegrationTest.ControlledCacheTestConfig.class
})
class ScopedRagCacheIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ScopedRagCacheIntegrationTest.class);
    private static final String SEED_DEV_PASSWORD = Week2SseTestClient.SEED_DEV_PASSWORD;

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID SALES_DEPT_ID = AbstractIntegrationTest.SALES_DEPT_ID;
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
    private StorageProperties storageProperties;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private SemanticCacheVectorRepository semanticCacheVectorRepository;

    @Autowired
    private DocumentPreviewTokenService previewTokenService;

    @Autowired
    private RbacVectorFilterFactory rbacVectorFilterFactory;

    @Autowired
    private RagProperties ragProperties;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private VectorRetrievalService vectorRetrievalService;

    @Autowired
    private GroundedSystemPromptService groundedSystemPromptService;

    @Autowired
    private ChatClient groqChatClient;

    @Autowired
    @Qualifier("geminiChatClient")
    private ObjectProvider<ChatClient> geminiChatClientProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private QueryContextualizerService queryContextualizerService;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private Environment environment;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ControlledSemanticEmbeddingModel embeddingModel;

    @Autowired
    private ControllableCacheTestChatModel chatModel;

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
    void resetAllState() throws InterruptedException {
        chatModel.reset();
        embeddingModel.reset();
        jdbcTemplate.update("DELETE FROM departments WHERE code = 'TEMP_MONOTONIC'");
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                AbstractIntegrationTest.cleanSharedInfrastructure(
                    jdbcTemplate,
                    stringRedisTemplate,
                    s3Client,
                    storageProperties.bucket(),
                    null,
                    Duration.ofSeconds(15)
                );
                break;
            } catch (RedisConnectionFailureException ex) {
                if (attempt == 2) {
                    throw ex;
                }
                Thread.sleep(250L);
            }
        }
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
    }

    @AfterEach
    void releaseAnyHeldLlm() {
        chatModel.releaseAllHolds();
    }

    @Test
    void l1ExactAndNormalizedCacheHitServesZeroTokensFreshPreviewUrlsNewMessageIdsAndMeetsSseSla() throws Exception {
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        byte[] pdfBytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of("Mục lục chính sách nhân sự 2026."),
            List.of("Chính sách nghỉ phép năm: nhân viên chính thức được hưởng 14 ngày phép năm có lương.")
        ));
        DocumentResponse doc = client.uploadAndAwaitCompleted(
            port,
            "hr-annual-leave-2026.pdf",
            "application/pdf",
            pdfBytes,
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );

        String baseQuestion = "Chính sách nghỉ phép năm 2026 là bao nhiêu ngày?";
        chatModel.resetCounts();
        embeddingModel.resetCounts();

        // 1. Cold MISS over SSE populates L1 + L2
        List<SseFrame> seedFrames = client.collectAllSseFrames(port, Map.of("question", baseQuestion), hrEmployeeJwt);
        JsonNode seedDone = objectMapper.readTree(seedFrames.getLast().data());
        assertThat(seedDone.path("abstained").asBoolean()).isFalse();
        assertThat(seedDone.path("cacheHit").asBoolean()).isFalse();
        assertThat(seedDone.path("cacheTier").asText()).isEqualTo("NONE");
        assertThat(seedDone.path("tokenUsage").path("promptTokens").asInt()).isPositive();
        assertThat(chatModel.answerCallCount()).isEqualTo(1);
        assertThat(embeddingModel.embedCount()).isGreaterThanOrEqualTo(1);
        UUID seedMessageId = UUID.fromString(seedDone.path("messageId").asText());
        String seedAnswer = assembleTokens(seedFrames);
        assertThat(seedAnswer).contains("hr-annual-leave-2026.pdf");

        // 2. Exact repeat over SSE -> L1 HIT with 0 embed/rewrite/answer calls, 0 tokens, new messageId, fresh fetchable preview URL
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> l1Frames = client.collectAllSseFrames(port, Map.of("question", baseQuestion), hrEmployeeJwt);
        JsonNode l1Done = objectMapper.readTree(l1Frames.getLast().data());
        JsonNode l1Citations = objectMapper.readTree(l1Frames.getFirst().data()).path("citations");

        assertThat(l1Done.path("abstained").asBoolean()).isFalse();
        assertThat(l1Done.path("cacheHit").asBoolean()).isTrue();
        assertThat(l1Done.path("cacheTier").asText()).isEqualTo("L1");
        assertThat(l1Done.path("tokenUsage").path("promptTokens").asInt()).isZero();
        assertThat(l1Done.path("tokenUsage").path("completionTokens").asInt()).isZero();
        assertThat(l1Done.path("tokenUsage").path("totalTokens").asInt()).isZero();
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(chatModel.rewriteCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
        assertThat(assembleTokens(l1Frames)).isEqualTo(seedAnswer);

        UUID l1MessageId = UUID.fromString(l1Done.path("messageId").asText());
        assertThat(l1MessageId).isNotEqualTo(seedMessageId);
        assertThat(l1Citations).isNotEmpty();
        assertThat(l1Citations.get(0).path("documentId").asText()).isEqualTo(doc.id().toString());
        assertThat(l1Citations.get(0).path("fileName").asText()).isEqualTo("hr-annual-leave-2026.pdf");
        String previewUrl = l1Citations.get(0).path("presignedUrl").asText();
        assertThat(previewUrl).contains("/api/v1/documents/" + doc.id() + "/content?token=");
        assertThat(client.fetchRawBytes(URI.create(Week2SseTestClient.stripFragment(previewUrl))).statusCode())
            .isEqualTo(200);

        // Verify chat_messages.cache_tier and exact 1 CHAT_QUERY audit row for the L1 hit turn
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cache_tier FROM chat_messages WHERE id = ?",
            String.class,
            l1MessageId
        )).isEqualTo("L1");

        List<Map<String, Object>> l1Audits = jdbcTemplate.queryForList(
            "SELECT cache_hit, cache_tier, prompt_tokens, completion_tokens, status_code FROM audit_logs WHERE action_type = 'CHAT_QUERY' ORDER BY created_at ASC"
        );
        assertThat(l1Audits).hasSize(2);
        assertThat(l1Audits.get(0).get("cache_hit")).isEqualTo(false);
        assertThat(l1Audits.get(0).get("cache_tier")).isEqualTo("NONE");
        assertThat(l1Audits.get(1).get("cache_hit")).isEqualTo(true);
        assertThat(l1Audits.get(1).get("cache_tier")).isEqualTo("L1");
        assertThat(l1Audits.get(1).get("prompt_tokens")).isEqualTo(0);
        assertThat(l1Audits.get(1).get("completion_tokens")).isEqualTo(0);

        // 3. NFD + uppercase + extra whitespace variant over sync POST /api/v1/chat/ask -> ALSO hits L1!
        String nfdVariant = Normalizer.normalize("   CHÍNH   SÁCH nghỉ phép năm 2026 là bao nhiêu ngày?  ", Normalizer.Form.NFD);
        HttpResponse<String> askHitRaw = client.sendJson(
            port,
            "POST",
            "/api/v1/chat/ask",
            Map.of("question", nfdVariant),
            hrEmployeeJwt
        );
        assertThat(askHitRaw.statusCode()).isEqualTo(200);
        ChatAskResponse askHit = objectMapper.readValue(askHitRaw.body(), ChatAskResponse.class);
        assertThat(askHit.cacheHit()).isTrue();
        assertThat(askHit.cacheTier()).isEqualTo(CacheTier.L1);
        assertThat(askHit.promptTokens()).isZero();
        assertThat(askHit.completionTokens()).isZero();
        assertThat(askHit.messageId()).isNotEqualTo(l1MessageId).isNotEqualTo(seedMessageId);
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();

        // 4. Warm SLA Measurement: 12 warm-up + 20 timed SSE L1 requests & 20 timed L1 lookups
        int warmupCount = 12;
        int sampleCount = 20;
        for (int i = 0; i < warmupCount; i++) {
            client.collectAllSseFrames(port, Map.of("question", baseQuestion), hrEmployeeJwt);
        }

        List<Double> sseL1Ms = new ArrayList<>(sampleCount);
        List<Double> l1PrePersistMs = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            long start = System.nanoTime();
            List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", baseQuestion), hrEmployeeJwt);
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0d;
            JsonNode done = objectMapper.readTree(frames.getLast().data());
            assertThat(done.path("cacheTier").asText()).isEqualTo("L1");
            sseL1Ms.add(elapsedMs);
            l1PrePersistMs.add(done.path("latencyMs").asDouble());
        }

        RbacVectorScope hrEmpScope = resolveHrEmployeeScope();
        List<Double> l1LookupMs = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            RagCacheService.CacheLookupResult res = ragCacheService.lookup(hrEmpScope, baseQuestion, List.of());
            assertThat(res.cacheTier()).isEqualTo(CacheTier.L1);
            l1LookupMs.add(res.l1LookupNanos() / 1_000_000.0d);
        }

        LatencySummary l1LookupStats = LatencySummary.of(l1LookupMs, 15.0d);
        LatencySummary l1PrePersistStats = LatencySummary.of(l1PrePersistMs, 50.0d);
        LatencySummary sseL1Stats = LatencySummary.of(sseL1Ms, 50.0d);
        log.info(
            "DA19_SLA_L1 workload=SSE_L1_EXACT_FIXTURE warmup={} samples={} l1LookupMs=[p50={}, p95={}, max={}, over15ms={}] prePersistMs=[p50={}, p95={}] sseE2eMs=[p50={}, p95={}, max={}, over50ms={}]",
            warmupCount,
            sampleCount,
            fmt(l1LookupStats.p50()),
            fmt(l1LookupStats.p95()),
            fmt(l1LookupStats.max()),
            l1LookupStats.overThreshold(),
            fmt(l1PrePersistStats.p50()),
            fmt(l1PrePersistStats.p95()),
            fmt(sseL1Stats.p50()),
            fmt(sseL1Stats.p95()),
            fmt(sseL1Stats.max()),
            sseL1Stats.overThreshold()
        );
        assertThat(l1LookupStats.p95()).isLessThan(15.0d);
        assertThat(l1PrePersistStats.p95()).isLessThan(50.0d);
        assertThat(sseL1Stats.p95()).isLessThan(50.0d);
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
    }

    @Test
    void l2SemanticHitEnforcesThresholdBoundaryBoundedL1PromotionAndMeetsSla() throws Exception {
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        byte[] docBytes = "Quy định nghỉ phép năm: nhân viên chính thức có 14 ngày phép năm hưởng nguyên lương."
            .getBytes(StandardCharsets.UTF_8);
        client.uploadAndAwaitCompleted(
            port,
            "hr-leave-semantic.txt",
            "text/plain",
            docBytes,
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );

        String baseQuery = "Chính sách nghỉ phép năm cho nhân viên chính thức là gì?";
        String belowThresholdQuery = "Phép năm nhân viên chính thức có điểm nào khác hợp đồng cộng tác viên?";
        String atThresholdQuery = "Nhân viên chính thức hàng năm được nghỉ bao nhiêu ngày phép có lương?";
        String boundedPromotionQuery = "Số ngày nghỉ phép năm hưởng nguyên lương của nhân viên chính thức?";

        // Exact mathematical cosines relative to unitAxis(0) using distinct orthogonal secondary axes
        embeddingModel.registerVector(baseQuery, ControlledSemanticEmbeddingModel.unitAxis(0));
        embeddingModel.registerVector(belowThresholdQuery, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 20, 0.935d));
        embeddingModel.registerVector(atThresholdQuery, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 21, 0.941d));
        embeddingModel.registerVector(boundedPromotionQuery, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 22, 0.955d));

        // 1. Seed baseQuery -> MISS populates L1 + L2
        List<SseFrame> seedFrames = client.collectAllSseFrames(port, Map.of("question", baseQuery), hrEmployeeJwt);
        assertThat(objectMapper.readTree(seedFrames.getLast().data()).path("cacheTier").asText()).isEqualTo("NONE");
        assertThat(chatModel.answerCallCount()).isEqualTo(1);
        UUID seededCacheId = jdbcTemplate.queryForObject(
            "SELECT id FROM semantic_cache WHERE query_text = ?",
            UUID.class,
            baseQuery
        );
        assertThat(seededCacheId).isNotNull();

        // 2. Below-threshold (cos = 0.935 < 0.94) -> MISS (invokes chat LLM)
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> belowFrames = client.collectAllSseFrames(port, Map.of("question", belowThresholdQuery), hrEmployeeJwt);
        JsonNode belowDone = objectMapper.readTree(belowFrames.getLast().data());
        assertThat(belowDone.path("cacheHit").asBoolean()).isFalse();
        assertThat(belowDone.path("cacheTier").asText()).isEqualTo("NONE");
        assertThat(chatModel.answerCallCount()).isEqualTo(1);

        // 3. At-threshold (cos = 0.941 >= 0.94) -> L2 HIT (0 chat calls, 0 tokens, hit_count incremented)
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> atThresholdFrames = client.collectAllSseFrames(port, Map.of("question", atThresholdQuery), hrEmployeeJwt);
        JsonNode atThresholdDone = objectMapper.readTree(atThresholdFrames.getLast().data());
        assertThat(atThresholdDone.path("cacheHit").asBoolean()).isTrue();
        assertThat(atThresholdDone.path("cacheTier").asText()).isEqualTo("L2");
        assertThat(atThresholdDone.path("tokenUsage").path("totalTokens").asInt()).isZero();
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(chatModel.rewriteCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT hit_count FROM semantic_cache WHERE id = ?",
            Integer.class,
            seededCacheId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT cache_tier FROM chat_messages WHERE id = ?",
            String.class,
            UUID.fromString(atThresholdDone.path("messageId").asText())
        )).isEqualTo("L2");

        // 4. Bounded L2->L1 promotion: shrink L2 expires_at to 90 seconds from now, trigger L2 hit, verify Redis L1 TTL <= 90s!
        jdbcTemplate.update(
            "UPDATE semantic_cache SET expires_at = NOW() + INTERVAL '90 seconds' WHERE id = ?",
            seededCacheId
        );
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> promotedFrames = client.collectAllSseFrames(port, Map.of("question", boundedPromotionQuery), hrEmployeeJwt);
        JsonNode promotedDone = objectMapper.readTree(promotedFrames.getLast().data());
        assertThat(promotedDone.path("cacheTier").asText()).isEqualTo("L2");
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isEqualTo(1);

        RbacVectorScope hrEmpScope = resolveHrEmployeeScope();
        long currentVersion = ragCacheService.readAuthoritativeVersion(hrEmpScope);
        String promotedL1Key = RagCacheService.buildStandaloneL1Key(
            hrEmpScope.cacheScopeKey(),
            currentVersion,
            RagCacheService.hashStandaloneQuery(boundedPromotionQuery)
        );
        Long promotedTtlSeconds = stringRedisTemplate.getExpire(promotedL1Key, TimeUnit.SECONDS);
        assertThat(promotedTtlSeconds)
            .as("Promoted L1 TTL must be bounded by remaining L2 expiry (<= 90s), not 1800s default")
            .isNotNull()
            .isPositive()
            .isLessThanOrEqualTo(90L);

        // Next repeat of the exact paraphrase immediately hits promoted L1 with 0 embedding calls!
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> secondParaphraseFrames = client.collectAllSseFrames(port, Map.of("question", boundedPromotionQuery), hrEmployeeJwt);
        JsonNode secondParaphraseDone = objectMapper.readTree(secondParaphraseFrames.getLast().data());
        assertThat(secondParaphraseDone.path("cacheHit").asBoolean()).isTrue();
        assertThat(secondParaphraseDone.path("cacheTier").asText()).isEqualTo("L1");
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();

        // 5. Warm L2 SLA Measurement (5 warm-up + 15 timed unique paraphrases at cos=0.960)
        jdbcTemplate.update(
            "UPDATE semantic_cache SET expires_at = NOW() + INTERVAL '24 hours' WHERE id = ?",
            seededCacheId
        );
        int warmupCount = 5;
        int sampleCount = 15;
        for (int i = 0; i < warmupCount; i++) {
            String q = "Paraphrase warmup phép năm #" + i;
            embeddingModel.registerVector(q, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 30 + i, 0.960d));
            List<SseFrame> warmFrames = client.collectAllSseFrames(port, Map.of("question", q), hrEmployeeJwt);
            assertThat(objectMapper.readTree(warmFrames.getLast().data()).path("cacheTier").asText()).isEqualTo("L2");
        }

        List<Double> sseL2E2eMs = new ArrayList<>(sampleCount);
        List<Double> l2PrePersistMs = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            String q = "Paraphrase đo độ trễ SSE L2 phép năm #" + i;
            embeddingModel.registerVector(q, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 40 + i, 0.960d));
            long start = System.nanoTime();
            List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", q), hrEmployeeJwt);
            double elapsedMs = (System.nanoTime() - start) / 1_000_000.0d;
            JsonNode done = objectMapper.readTree(frames.getLast().data());
            assertThat(done.path("cacheTier").asText()).isEqualTo("L2");
            assertThat(done.path("tokenUsage").path("totalTokens").asInt()).isZero();
            sseL2E2eMs.add(elapsedMs);
            l2PrePersistMs.add(done.path("latencyMs").asDouble());
        }

        List<Double> embedLookupMs = new ArrayList<>(sampleCount);
        List<Double> l2QueryMs = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            String q = "Paraphrase đo độ trễ lookup L2 phép năm #" + i;
            embeddingModel.registerVector(q, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 60 + i, 0.960d));
            RagCacheService.CacheLookupResult res = ragCacheService.lookup(hrEmpScope, q, List.of());
            assertThat(res.cacheTier()).isEqualTo(CacheTier.L2);
            embedLookupMs.add(res.embeddingNanos() / 1_000_000.0d);
            l2QueryMs.add(res.l2LookupNanos() / 1_000_000.0d);
        }

        LatencySummary embedStats = LatencySummary.of(embedLookupMs, 10.0d);
        LatencySummary l2QueryStats = LatencySummary.of(l2QueryMs, 70.0d);
        LatencySummary l2PrePersistStats = LatencySummary.of(l2PrePersistMs, 80.0d);
        LatencySummary sseL2Stats = LatencySummary.of(sseL2E2eMs, 80.0d);
        log.info(
            "DA19_SLA_L2 workload=SSE_L2_SEMANTIC_FIXTURE warmup={} samples={} embedMs=[p50={}, p95={}] l2QueryMs=[p50={}, p95={}, max={}, over70ms={}] prePersistMs=[p50={}, p95={}] sseE2eMs=[p50={}, p95={}, max={}, over80ms={}]",
            warmupCount,
            sampleCount,
            fmt(embedStats.p50()),
            fmt(embedStats.p95()),
            fmt(l2QueryStats.p50()),
            fmt(l2QueryStats.p95()),
            fmt(l2QueryStats.max()),
            l2QueryStats.overThreshold(),
            fmt(l2PrePersistStats.p50()),
            fmt(l2PrePersistStats.p95()),
            fmt(sseL2Stats.p50()),
            fmt(sseL2Stats.p95()),
            fmt(sseL2Stats.max()),
            sseL2Stats.overThreshold()
        );
        assertThat(l2QueryStats.p95()).isLessThan(70.0d);
        assertThat(l2PrePersistStats.p95()).isLessThan(80.0d);
        assertThat(sseL2Stats.p95()).isLessThan(80.0d);
    }

    @Test
    void instantPostCommitInvalidationCoversAllDocumentMutationPathsWhilePreservingUnaffectedScopes() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String itManagerJwt = client.login(port, Week2SseTestClient.IT_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesManagerJwt = client.login(port, Week2SseTestClient.SALES_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesEmployeeJwt = client.login(port, Week2SseTestClient.SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        DocumentResponse hrDoc = client.uploadAndAwaitCompleted(
            port,
            "hr-leave-v1.txt",
            "text/plain",
            "Quy định nghỉ phép năm v1: nhân viên HR được hưởng 14 ngày phép năm.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );
        DocumentResponse salesDoc = client.uploadAndAwaitCompleted(
            port,
            "sales-commission-v1.txt",
            "text/plain",
            "Chính sách hoa hồng kinh doanh: nhân viên sales nhận 8 phần trăm doanh số.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            SALES_DEPT_ID,
            salesManagerJwt
        );
        DocumentResponse publicDoc = client.uploadAndAwaitCompleted(
            port,
            "company-hours-v1.txt",
            "text/plain",
            "Nội quy chung toàn công ty: giờ làm việc từ 08:30 đến 17:30.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.PUBLIC,
            IT_DEPT_ID,
            itManagerJwt
        );

        String hrQuestion = "Chính sách nghỉ phép năm có bao nhiêu ngày?";
        String salesQuestion = "Chính sách hoa hồng doanh số kinh doanh bao nhiêu phần trăm?";
        String publicQuestion = "Nội quy chung giờ làm việc toàn công ty bắt đầu lúc mấy giờ?";

        // Warm L1+L2 for HR, SALES, and Global Admin
        warmAndVerifyL1Hit(hrEmployeeJwt, hrQuestion, "hr-leave-v1.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");
        warmAndVerifyL1Hit(adminJwt, hrQuestion, "hr-leave-v1.txt");

        // 0b. Post-warm POST /api/v1/documents/upload (HR DEPARTMENT) -> invalidates HR & ADMIN:GLOBAL immediately; SALES still hits L1!
        client.uploadAndAwaitCompleted(
            port,
            "hr-lunch-allowance-v1.txt",
            "text/plain",
            "Chính sách phụ cấp ăn trưa nhân viên HR: 40 nghìn đồng mỗi ngày.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );
        List<SseFrame> hrAfterPostUpload = client.collectAllSseFrames(port, Map.of("question", hrQuestion), hrEmployeeJwt);
        assertThat(objectMapper.readTree(hrAfterPostUpload.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        List<SseFrame> adminAfterPostUpload = client.collectAllSseFrames(port, Map.of("question", hrQuestion), adminJwt);
        assertThat(objectMapper.readTree(adminAfterPostUpload.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertStillHitsL1(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // 1. Metadata Rename (PATCH originalName on HR doc) -> HR & Admin miss with new name; SALES still hits L1!
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("originalName", "hr-leave-renamed.txt"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);

        chatModel.resetCounts();
        List<SseFrame> hrAfterRename = client.collectAllSseFrames(port, Map.of("question", hrQuestion), hrEmployeeJwt);
        assertThat(objectMapper.readTree(hrAfterRename.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(hrAfterRename.getFirst().data())
            .contains("hr-leave-renamed.txt")
            .doesNotContain("hr-leave-v1.txt");
        assertThat(assembleTokens(hrAfterRename)).contains("hr-leave-renamed.txt").doesNotContain("hr-leave-v1.txt");

        List<SseFrame> adminAfterRename = client.collectAllSseFrames(port, Map.of("question", hrQuestion), adminJwt);
        assertThat(objectMapper.readTree(adminAfterRename.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(adminAfterRename.getFirst().data()).contains("hr-leave-renamed.txt");

        assertStillHitsL1(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // 2. Access Level Change (DEPARTMENT -> CONFIDENTIAL) -> HR Employee immediately refused; SALES still hits L1!
        warmAndVerifyL1Hit(hrEmployeeJwt, hrQuestion, "hr-leave-renamed.txt");
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("accessLevel", "CONFIDENTIAL"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);

        chatModel.resetCounts();
        List<SseFrame> hrBlocked = client.collectAllSseFrames(port, Map.of("question", hrQuestion), hrEmployeeJwt);
        client.assertDeterministicSseRefusal(hrBlocked, "14 ngày", "hr-leave-renamed.txt", true);
        assertThat(objectMapper.readTree(hrBlocked.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(chatModel.answerCallCount()).isZero();
        assertStillHitsL1(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // Restore HR doc to DEPARTMENT for transfer test
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("accessLevel", "DEPARTMENT"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);
        warmAndVerifyL1Hit(hrEmployeeJwt, hrQuestion, "hr-leave-renamed.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // 3. Department Transfer (HR -> IT) -> HR immediately refused, IT sees doc, SALES still hits L1!
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("departmentId", IT_DEPT_ID.toString()),
            adminJwt
        ).statusCode()).isEqualTo(200);

        chatModel.resetCounts();
        List<SseFrame> hrAfterTransfer = client.collectAllSseFrames(port, Map.of("question", hrQuestion), hrEmployeeJwt);
        client.assertDeterministicSseRefusal(hrAfterTransfer, "14 ngày", "hr-leave-renamed.txt", true);
        assertThat(chatModel.answerCallCount()).isZero();

        List<SseFrame> itAfterTransfer = client.collectAllSseFrames(port, Map.of("question", hrQuestion), itManagerJwt);
        assertThat(objectMapper.readTree(itAfterTransfer.getLast().data()).path("abstained").asBoolean()).isFalse();
        assertThat(objectMapper.readTree(itAfterTransfer.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertStillHitsL1(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // 4. PUBLIC change (edit PUBLIC doc) -> invalidates ALL departments including IT & SALES!
        warmAndVerifyL1Hit(itManagerJwt, hrQuestion, "hr-leave-renamed.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, publicQuestion, "company-hours-v1.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + publicDoc.id(),
            Map.of("originalName", "company-hours-v2.txt"),
            itManagerJwt
        ).statusCode()).isEqualTo(200);

        List<SseFrame> salesPublicAfterPatch = client.collectAllSseFrames(port, Map.of("question", publicQuestion), salesEmployeeJwt);
        assertThat(objectMapper.readTree(salesPublicAfterPatch.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(salesPublicAfterPatch.getFirst().data())
            .contains("company-hours-v2.txt")
            .doesNotContain("company-hours-v1.txt");

        List<SseFrame> salesOwnAfterPublicPatch = client.collectAllSseFrames(port, Map.of("question", salesQuestion), salesEmployeeJwt);
        assertThat(objectMapper.readTree(salesOwnAfterPublicPatch.getLast().data()).path("cacheHit").asBoolean()).isFalse();

        // 5. PUT Reindex + Async Ingestion markCompleted -> IT invalidates & reads v2; SALES still hits L1!
        warmAndVerifyL1Hit(itManagerJwt, hrQuestion, "hr-leave-renamed.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        HttpResponse<String> putResp = client.sendMultipart(
            port,
            "PUT",
            "/api/v1/documents/" + hrDoc.id(),
            "it-leave-v2.txt",
            "text/plain",
            "Quy định nghỉ phép năm phiên bản mới v2: được hưởng 18 ngày phép năm.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            null,
            itManagerJwt
        );
        assertThat(putResp.statusCode()).isEqualTo(202);
        DocumentStatusResponse reindexDone = client.awaitTerminalDocumentStatus(port, hrDoc.id(), itManagerJwt, Duration.ofSeconds(20));
        assertThat(reindexDone.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(reindexDone.currentVersion()).isEqualTo(2);

        List<SseFrame> itAfterReindex = client.collectAllSseFrames(port, Map.of("question", hrQuestion), itManagerJwt);
        assertThat(objectMapper.readTree(itAfterReindex.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(itAfterReindex.getFirst().data())
            .contains("it-leave-v2.txt")
            .contains("18 ngày")
            .doesNotContain("hr-leave-renamed.txt");
        assertStillHitsL1(salesEmployeeJwt, salesQuestion, "sales-commission-v1.txt");

        // 6. Hard Delete (DELETE IT doc) -> IT & Admin immediately refuse with zero stale filename; SALES still hits L1!
        warmAndVerifyL1Hit(itManagerJwt, hrQuestion, "it-leave-v2.txt");
        warmAndVerifyL1Hit(adminJwt, hrQuestion, "it-leave-v2.txt");
        assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + hrDoc.id(), null, itManagerJwt).statusCode())
            .isEqualTo(204);

        chatModel.resetCounts();
        List<SseFrame> itAfterDelete = client.collectAllSseFrames(port, Map.of("question", hrQuestion), itManagerJwt);
        client.assertDeterministicSseRefusal(itAfterDelete, "18 ngày", "it-leave-v2.txt", true);
        assertThat(objectMapper.readTree(itAfterDelete.getLast().data()).path("cacheHit").asBoolean()).isFalse();

        List<SseFrame> adminAfterDelete = client.collectAllSseFrames(port, Map.of("question", hrQuestion), adminJwt);
        client.assertDeterministicSseRefusal(adminAfterDelete, "18 ngày", "it-leave-v2.txt", true);
        assertThat(chatModel.answerCallCount()).isZero();

        assertStillHitsL1(salesEmployeeJwt, salesQuestion, salesDoc.originalName());

        // 7. Hard Delete of PUBLIC doc -> immediately refuses publicQuestion AND invalidates ALL departments including SALES!
        warmAndVerifyL1Hit(salesEmployeeJwt, publicQuestion, "company-hours-v2.txt");
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, salesDoc.originalName());
        assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + publicDoc.id(), null, itManagerJwt).statusCode())
            .isEqualTo(204);

        chatModel.resetCounts();
        List<SseFrame> salesPublicAfterDelete = client.collectAllSseFrames(port, Map.of("question", publicQuestion), salesEmployeeJwt);
        client.assertDeterministicSseRefusal(salesPublicAfterDelete, "08:30", "company-hours-v2.txt", true);
        assertThat(objectMapper.readTree(salesPublicAfterDelete.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(chatModel.answerCallCount()).isZero();

        List<SseFrame> salesOwnAfterPublicDelete = client.collectAllSseFrames(port, Map.of("question", salesQuestion), salesEmployeeJwt);
        assertThat(objectMapper.readTree(salesOwnAfterPublicDelete.getLast().data()).path("cacheHit").asBoolean()).isFalse();

        // 8. Empty department deletion keeps ADMIN:GLOBAL SUM(cache_version) strictly monotonic (never resurrects stale global cache)
        HttpResponse<String> createTempDeptResp = client.sendJson(
            port,
            "POST",
            "/api/v1/admin/departments",
            Map.of("code", "TEMP_MONOTONIC", "name", "Temporary Monotonic Dept"),
            adminJwt
        );
        assertThat(createTempDeptResp.statusCode()).isEqualTo(201);
        UUID tempDeptId = UUID.fromString(objectMapper.readTree(createTempDeptResp.body()).path("id").asText());

        warmAndVerifyL1Hit(adminJwt, salesQuestion, salesDoc.originalName());
        client.uploadAndAwaitCompleted(
            port,
            "sales-year-end-bonus.txt",
            "text/plain",
            "Chính sách thưởng dự án cuối năm phòng kinh doanh.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            SALES_DEPT_ID,
            salesManagerJwt
        );
        long globalSumBeforeDeptDelete = departmentRepository.sumCacheVersions();

        assertThat(client.sendJson(port, "DELETE", "/api/v1/admin/departments/" + tempDeptId, null, adminJwt).statusCode())
            .isEqualTo(204);
        long globalSumAfterDeptDelete = departmentRepository.sumCacheVersions();
        assertThat(globalSumAfterDeptDelete).isGreaterThan(globalSumBeforeDeptDelete);
        assertThat(stringRedisTemplate.opsForValue().get(RagCacheService.GLOBAL_VERSION_KEY))
            .isEqualTo(String.valueOf(globalSumAfterDeptDelete));
        assertThat(stringRedisTemplate.opsForValue().get(RagCacheService.buildDepartmentVersionKey(tempDeptId)))
            .isNull();

        List<SseFrame> adminAfterDeptDelete = client.collectAllSseFrames(port, Map.of("question", salesQuestion), adminJwt);
        assertThat(objectMapper.readTree(adminAfterDeptDelete.getLast().data()).path("cacheHit").asBoolean()).isFalse();
    }

    @Test
    void rbacAndMultiTurnIsolationPreventCrossScopeAndCrossTopicCacheBleed() throws Exception {
        String adminJwt = client.login(port, Week2SseTestClient.ADMIN_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesManagerJwt = client.login(port, Week2SseTestClient.SALES_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesEmployeeJwt = client.login(port, Week2SseTestClient.SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        client.uploadAndAwaitCompleted(
            port,
            "hr-confidential-comp.txt",
            "text/plain",
            "Bảng lương mật VIP_COMP_SECRET: thưởng lãnh đạo HR là 500 triệu đồng.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.CONFIDENTIAL,
            HR_DEPT_ID,
            hrManagerJwt
        );
        client.uploadAndAwaitCompleted(
            port,
            "hr-leave-policy.txt",
            "text/plain",
            "Chính sách nghỉ phép năm: điều kiện áp dụng là hoàn thành thời gian thử việc, hưởng 14 ngày phép.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );
        client.uploadAndAwaitCompleted(
            port,
            "hr-probation-policy.txt",
            "text/plain",
            "Chính sách thử việc: thời gian thử việc là 60 ngày, hưởng 85 phần trăm lương chính thức.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );
        client.uploadAndAwaitCompleted(
            port,
            "sales-commission.txt",
            "text/plain",
            "Chính sách hoa hồng kinh doanh Sales: thưởng doanh số quý 10 phần trăm.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            SALES_DEPT_ID,
            salesManagerJwt
        );

        // 1. Manager vs Employee within SAME department on CONFIDENTIAL doc
        String compQuestion = "Bảng lương mật và mức thưởng lãnh đạo HR là bao nhiêu?";
        warmAndVerifyL1Hit(hrManagerJwt, compQuestion, "hr-confidential-comp.txt");

        chatModel.resetCounts();
        List<SseFrame> empCompFrames = client.collectAllSseFrames(port, Map.of("question", compQuestion), hrEmployeeJwt);
        client.assertDeterministicSseRefusal(empCompFrames, "VIP_COMP_SECRET", "hr-confidential-comp.txt", true);
        assertThat(objectMapper.readTree(empCompFrames.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(chatModel.answerCallCount()).isZero();

        // 2. Cross-department isolation (HR vs SALES)
        String salesQuestion = "Chính sách hoa hồng doanh số kinh doanh là bao nhiêu?";
        warmAndVerifyL1Hit(salesEmployeeJwt, salesQuestion, "sales-commission.txt");

        chatModel.resetCounts();
        List<SseFrame> hrAskingSales = client.collectAllSseFrames(port, Map.of("question", salesQuestion), hrEmployeeJwt);
        client.assertDeterministicSseRefusal(hrAskingSales, "10 phần trăm", "sales-commission.txt", true);
        assertThat(objectMapper.readTree(hrAskingSales.getLast().data()).path("cacheHit").asBoolean()).isFalse();

        // 3. Global Admin vs Admin filtered to HR (ADMIN:GLOBAL vs ADMIN:DEPT:<HR>)
        warmAndVerifyL1Hit(adminJwt, salesQuestion, "sales-commission.txt");
        chatModel.resetCounts();
        List<SseFrame> adminFilteredToHr = client.collectAllSseFrames(
            port,
            Map.of("question", salesQuestion, "departmentId", HR_DEPT_ID.toString()),
            adminJwt
        );
        client.assertDeterministicSseRefusal(adminFilteredToHr, "10 phần trăm", "sales-commission.txt", true);
        assertThat(objectMapper.readTree(adminFilteredToHr.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(chatModel.answerCallCount()).isZero();

        // 4. Multi-turn follow-up isolation: identical follow-up question after two different topics!
        List<SseFrame> leaveTurn1 = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách nghỉ phép năm quy định thế nào?"),
            hrEmployeeJwt
        );
        UUID leaveSessionId = UUID.fromString(objectMapper.readTree(leaveTurn1.getLast().data()).path("sessionId").asText());

        String ambiguousFollowUp = "Điều kiện áp dụng cụ thể là gì?";
        List<SseFrame> leaveFollowUp = client.collectAllSseFrames(
            port,
            Map.of("question", ambiguousFollowUp, "sessionId", leaveSessionId.toString()),
            hrEmployeeJwt
        );
        assertThat(objectMapper.readTree(leaveFollowUp.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(leaveFollowUp.getFirst().data()).contains("hr-leave-policy.txt");

        // Second session on a DIFFERENT topic (Probation) asking the EXACT SAME follow-up MUST NOT hit leaveSessionId's cache!
        List<SseFrame> probationTurn1 = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách thử việc kéo dài bao lâu?"),
            hrEmployeeJwt
        );
        UUID probationSessionId = UUID.fromString(objectMapper.readTree(probationTurn1.getLast().data()).path("sessionId").asText());

        chatModel.resetCounts();
        List<SseFrame> probationFollowUp = client.collectAllSseFrames(
            port,
            Map.of("question", ambiguousFollowUp, "sessionId", probationSessionId.toString()),
            hrEmployeeJwt
        );
        JsonNode probationFollowUpDone = objectMapper.readTree(probationFollowUp.getLast().data());
        assertThat(probationFollowUpDone.path("cacheHit").asBoolean()).isFalse();
        assertThat(chatModel.rewriteCallCount()).isEqualTo(1);
        assertThat(chatModel.answerCallCount()).isEqualTo(1);
        assertThat(probationFollowUp.getFirst().data())
            .contains("hr-probation-policy.txt")
            .doesNotContain("hr-leave-policy.txt");

        // Contextual follow-ups NEVER write into shared L2 semantic_cache
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM semantic_cache WHERE query_text = ?",
            Integer.class,
            ambiguousFollowUp
        )).isZero();

        // Identical conversation history + identical follow-up hits isolated contextual L1 (ctx) with 0 rewrite/answer calls!
        List<SseFrame> leaveTurn1Clone = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách nghỉ phép năm quy định thế nào?"),
            hrEmployeeJwt
        );
        UUID leaveSessionCloneId = UUID.fromString(objectMapper.readTree(leaveTurn1Clone.getLast().data()).path("sessionId").asText());

        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> contextualL1Hit = client.collectAllSseFrames(
            port,
            Map.of("question", ambiguousFollowUp, "sessionId", leaveSessionCloneId.toString()),
            hrEmployeeJwt
        );
        JsonNode ctxHitDone = objectMapper.readTree(contextualL1Hit.getLast().data());
        assertThat(ctxHitDone.path("cacheHit").asBoolean()).isTrue();
        assertThat(ctxHitDone.path("cacheTier").asText()).isEqualTo("L1");
        assertThat(chatModel.rewriteCallCount()).isZero();
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
        assertThat(contextualL1Hit.getFirst().data()).contains("hr-leave-policy.txt");

        // 5. Foreign sessionId fails with 404 BEFORE any cache lookup or LLM invocation
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        HttpResponse<String> foreignStream = client.sendJson(
            port,
            "POST",
            "/api/v1/chat/stream",
            Map.of("question", "Chính sách nghỉ phép năm quy định thế nào?", "sessionId", leaveSessionId.toString()),
            salesEmployeeJwt
        );
        assertThat(foreignStream.statusCode()).isEqualTo(404);
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
    }

    @Test
    void resilienceCoversRollbackMissingOrStaleRedisVersionOutOfOrderSyncInFlightRaceAndRedisOutage() throws Exception {
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        DocumentResponse hrDoc = client.uploadAndAwaitCompleted(
            port,
            "hr-race-v1.txt",
            "text/plain",
            "Quy định nghỉ phép năm: nhân viên chính thức được 14 ngày phép.".getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            HR_DEPT_ID,
            hrManagerJwt
        );
        String question = "Chính sách nghỉ phép năm cho nhân viên chính thức?";
        warmAndVerifyL1Hit(hrEmployeeJwt, question, "hr-race-v1.txt");

        String hrVerKey = RagCacheService.DEPT_VERSION_KEY_PREFIX + HR_DEPT_ID;
        long versionBeforeRollback = departmentRepository.findCacheVersionById(HR_DEPT_ID).orElseThrow();

        // 1. Transaction rollback keeps old DB version, never touches Redis, and preserves valid L1/L2 hits
        transactionTemplate.executeWithoutResult(status -> {
            departmentRepository.incrementCacheVersion(HR_DEPT_ID);
            ragCacheService.schedulePostCommitSyncForDepartments(List.of(HR_DEPT_ID));
            status.setRollbackOnly();
        });
        assertThat(departmentRepository.findCacheVersionById(HR_DEPT_ID).orElseThrow()).isEqualTo(versionBeforeRollback);
        assertThat(stringRedisTemplate.opsForValue().get(hrVerKey)).isEqualTo(String.valueOf(versionBeforeRollback));
        assertStillHitsL1(hrEmployeeJwt, question, "hr-race-v1.txt");

        // 2. Missing AND stale Redis version key after real commit -> authoritative DB read prevents stale replay & self-heals Redis!
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("originalName", "hr-race-v2.txt"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);
        long committedVersion = departmentRepository.findCacheVersionById(HR_DEPT_ID).orElseThrow();
        assertThat(committedVersion).isGreaterThan(versionBeforeRollback);

        // (a) Simulate missing Redis version key (eviction / restart)
        stringRedisTemplate.delete(hrVerKey);
        List<SseFrame> afterMissingKey = client.collectAllSseFrames(port, Map.of("question", question), hrEmployeeJwt);
        assertThat(objectMapper.readTree(afterMissingKey.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(afterMissingKey.getFirst().data()).contains("hr-race-v2.txt").doesNotContain("hr-race-v1.txt");
        assertThat(stringRedisTemplate.opsForValue().get(hrVerKey)).isEqualTo(String.valueOf(committedVersion));

        // (b) Simulate stale/delayed Redis version key rolled back manually to old version -> DB still wins & heals Redis!
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("originalName", "hr-race-v3.txt"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);
        long v3 = departmentRepository.findCacheVersionById(HR_DEPT_ID).orElseThrow();
        stringRedisTemplate.opsForValue().set(hrVerKey, String.valueOf(versionBeforeRollback));

        List<SseFrame> afterStaleKey = client.collectAllSseFrames(port, Map.of("question", question), hrEmployeeJwt);
        assertThat(objectMapper.readTree(afterStaleKey.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(afterStaleKey.getFirst().data()).contains("hr-race-v3.txt").doesNotContain("hr-race-v2.txt");
        assertThat(stringRedisTemplate.opsForValue().get(hrVerKey)).isEqualTo(String.valueOf(v3));

        // 3. Out-of-order monotonic version callbacks never regress Redis version
        ragCacheService.syncVersionKeyMonotonic(hrVerKey, v3 + 5L);
        ragCacheService.syncVersionKeyMonotonic(hrVerKey, v3 + 2L);
        assertThat(stringRedisTemplate.opsForValue().get(hrVerKey)).isEqualTo(String.valueOf(v3 + 5L));

        // Restore hrVerKey to real DB v3 before in-flight race test
        stringRedisTemplate.opsForValue().set(hrVerKey, String.valueOf(v3));

        // 4. In-flight MISS racing a document commit MUST NOT resurrect stale v3 content into v4 cache!
        String raceQuestion = "Quy định nghỉ phép năm hiện hành có mấy ngày?";
        // cos=0.90 against axis 0: passes retrieval (>= 0.72) while missing L2 semantic cache (< 0.94)
        embeddingModel.registerVector(raceQuestion, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 88, 0.90d));
        AnswerHoldControl hold = chatModel.armAnswerHold();
        CompletableFuture<List<SseFrame>> inFlightMiss = CompletableFuture.supplyAsync(() -> {
            try {
                return client.collectAllSseFrames(port, Map.of("question", raceQuestion), hrEmployeeJwt);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(hold.enteredLatch().await(10, TimeUnit.SECONDS)).isTrue();
        // While in-flight MISS is paused inside LLM (after capturing v3), commit a real document rename (v3 -> v4)
        assertThat(client.sendJson(
            port,
            "PATCH",
            "/api/v1/documents/" + hrDoc.id(),
            Map.of("originalName", "hr-race-v4-fresh.txt"),
            hrManagerJwt
        ).statusCode()).isEqualTo(200);
        long v4 = departmentRepository.findCacheVersionById(HR_DEPT_ID).orElseThrow();
        assertThat(v4).isGreaterThan(v3);

        hold.releaseLatch().countDown();
        List<SseFrame> inFlightResult = inFlightMiss.get(15, TimeUnit.SECONDS);
        assertThat(inFlightResult.getLast().event()).isEqualTo("done");

        // Zero L1 keys and zero L2 semantic_cache rows exist for v4 containing stale v3 content!
        RbacVectorScope hrEmpScope = resolveHrEmployeeScope();
        String v4L1Key = RagCacheService.buildStandaloneL1Key(
            hrEmpScope.cacheScopeKey(),
            v4,
            RagCacheService.hashStandaloneQuery(raceQuestion)
        );
        assertThat(stringRedisTemplate.opsForValue().get(v4L1Key)).isNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM semantic_cache WHERE rbac_scope = ? AND scope_version = ?",
            Integer.class,
            hrEmpScope.cacheScopeKey(),
            v4
        )).isZero();

        // Next request immediately sees fresh v4 filename and NEVER resurrects v3!
        List<SseFrame> postRaceFrames = client.collectAllSseFrames(port, Map.of("question", raceQuestion), hrEmployeeJwt);
        assertThat(objectMapper.readTree(postRaceFrames.getLast().data()).path("cacheHit").asBoolean()).isFalse();
        assertThat(postRaceFrames.getFirst().data())
            .contains("hr-race-v4-fresh.txt")
            .doesNotContain("hr-race-v3.txt");

        // 5. Real Redis outage (closed loopback port via LettuceConnectionFactory, 0 Mockito mocks) degrades gracefully to L2!
        int closedRedisPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedRedisPort = socket.getLocalPort();
        }
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration("127.0.0.1", closedRedisPort),
            LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(150))
                .shutdownTimeout(Duration.ZERO)
                .build()
        );
        brokenFactory.afterPropertiesSet();
        try {
            StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);
            brokenRedis.afterPropertiesSet();
            RagCacheService degradedCacheService = new RagCacheService(
                brokenRedis,
                departmentRepository,
                documentRepository,
                semanticCacheVectorRepository,
                embeddingModel,
                previewTokenService,
                objectMapper,
                ragProperties,
                Clock.systemUTC()
            );
            RagChatService degradedChatService = new RagChatService(
                vectorRetrievalService,
                groundedSystemPromptService,
                rbacVectorFilterFactory,
                groqChatClient,
                geminiChatClientProvider,
                providerOf(userRepository),
                providerOf(departmentRepository),
                providerOf(auditLogRepository),
                providerOf(transactionTemplate),
                providerOf(chatSessionService),
                providerOf(queryContextualizerService),
                providerOf(degradedCacheService),
                aiProperties,
                ragProperties,
                environment
            );

            chatModel.resetCounts();
            embeddingModel.resetCounts();
            var previousContext = SecurityContextHolder.getContext();
            try {
                CustomUserDetails hrPrincipal = new CustomUserDetails(
                    HR_EMPLOYEE_USER_ID,
                    HR_DEPT_ID,
                    Week2SseTestClient.HR_EMPLOYEE_EMAIL,
                    "HR Employee",
                    Role.STANDARD_EMPLOYEE,
                    "outage-jti-" + UUID.randomUUID(),
                    java.time.Instant.now().plusSeconds(900)
                );
                var ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(new UsernamePasswordAuthenticationToken(hrPrincipal, null, hrPrincipal.getAuthorities()));
                SecurityContextHolder.setContext(ctx);

                ChatAskResponse l2FallbackResponse = degradedChatService.ask(
                    raceQuestion,
                    null,
                    null,
                    hrEmpScope.caller()
                );
                assertThat(l2FallbackResponse.cacheHit()).isTrue();
                assertThat(l2FallbackResponse.cacheTier()).isEqualTo(CacheTier.L2);
                assertThat(l2FallbackResponse.promptTokens()).isZero();
                assertThat(l2FallbackResponse.citations()).isNotEmpty();
                assertThat(l2FallbackResponse.citations().getFirst().fileName()).isEqualTo("hr-race-v4-fresh.txt");
                assertThat(chatModel.answerCallCount()).isZero();

                // Fresh MISS under Redis outage still succeeds via RAG and writes L2 semantic_cache without throwing
                String outageMissQuestion = "Chính sách nghỉ phép năm bổ sung khi Redis sự cố?";
                embeddingModel.registerVector(outageMissQuestion, ControlledSemanticEmbeddingModel.unitVectorAtCosine(0, 89, 0.90d));
                ChatAskResponse missDuringOutage = degradedChatService.ask(
                    outageMissQuestion,
                    null,
                    null,
                    hrEmpScope.caller()
                );
                assertThat(missDuringOutage.abstained()).isFalse();
                assertThat(missDuringOutage.cacheHit()).isFalse();
                assertThat(chatModel.answerCallCount()).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM semantic_cache WHERE rbac_scope = ? AND scope_version = ? AND query_text = ?",
                    Integer.class,
                    hrEmpScope.cacheScopeKey(),
                    v4,
                    outageMissQuestion
                )).isEqualTo(1);
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        } finally {
            brokenFactory.destroy();
        }
    }

    @Test
    void repeatedQueryKpiWorkloadExceedsCacheHitRatioGateWithZeroCrossScopeBleed() throws Exception {
        String hrManagerJwt = client.login(port, Week2SseTestClient.HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String hrEmployeeJwt = client.login(port, Week2SseTestClient.HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesManagerJwt = client.login(port, Week2SseTestClient.SALES_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesEmployeeJwt = client.login(port, Week2SseTestClient.SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        // Seed 4 orthogonal HR topics + 2 orthogonal SALES topics
        seedKpiDoc(hrManagerJwt, HR_DEPT_ID, "kpi-hr-topic1.txt", "KPI_HR_TOPIC_1 quy định nghỉ phép năm 14 ngày.", 101);
        seedKpiDoc(hrManagerJwt, HR_DEPT_ID, "kpi-hr-topic2.txt", "KPI_HR_TOPIC_2 quy định bảo hiểm sức khỏe nhân viên.", 102);
        seedKpiDoc(hrManagerJwt, HR_DEPT_ID, "kpi-hr-topic3.txt", "KPI_HR_TOPIC_3 quy định công tác phí nội địa.", 103);
        seedKpiDoc(hrManagerJwt, HR_DEPT_ID, "kpi-hr-topic4.txt", "KPI_HR_TOPIC_4 quy định đào tạo và phát triển.", 104);
        seedKpiDoc(salesManagerJwt, SALES_DEPT_ID, "kpi-sales-topic1.txt", "KPI_SALES_TOPIC_1 quy định hoa hồng quý 10 phần trăm.", 105);
        seedKpiDoc(salesManagerJwt, SALES_DEPT_ID, "kpi-sales-topic2.txt", "KPI_SALES_TOPIC_2 quy định chiết khấu khách hàng lớn.", 106);

        String hrQ1 = "KPI_HR_TOPIC_1 số ngày nghỉ phép?";
        String hrQ2 = "KPI_HR_TOPIC_2 gói bảo hiểm nhân viên?";
        String hrQ3 = "KPI_HR_TOPIC_3 định mức công tác phí?";
        String hrQ4 = "KPI_HR_TOPIC_4 ngân sách đào tạo?";
        String hrQ1Para = "Paraphrase KPI_HR_TOPIC_1 chế độ phép năm?";
        String hrQ2Para = "Paraphrase KPI_HR_TOPIC_2 quyền lợi bảo hiểm?";
        String hrQ3Para = "Paraphrase KPI_HR_TOPIC_3 phụ cấp công tác?";
        String salesQ1 = "KPI_SALES_TOPIC_1 mức hoa hồng quý?";
        String salesQ2 = "KPI_SALES_TOPIC_2 mức chiết khấu đại lý?";

        embeddingModel.registerVector(hrQ1, ControlledSemanticEmbeddingModel.unitAxis(101));
        embeddingModel.registerVector(hrQ2, ControlledSemanticEmbeddingModel.unitAxis(102));
        embeddingModel.registerVector(hrQ3, ControlledSemanticEmbeddingModel.unitAxis(103));
        embeddingModel.registerVector(hrQ4, ControlledSemanticEmbeddingModel.unitAxis(104));
        embeddingModel.registerVector(hrQ1Para, ControlledSemanticEmbeddingModel.unitVectorAtCosine(101, 201, 0.960d));
        embeddingModel.registerVector(hrQ2Para, ControlledSemanticEmbeddingModel.unitVectorAtCosine(102, 202, 0.960d));
        embeddingModel.registerVector(hrQ3Para, ControlledSemanticEmbeddingModel.unitVectorAtCosine(103, 203, 0.960d));
        embeddingModel.registerVector(salesQ1, ControlledSemanticEmbeddingModel.unitAxis(105));
        embeddingModel.registerVector(salesQ2, ControlledSemanticEmbeddingModel.unitAxis(106));

        record KpiStep(String jwt, String question, boolean crossScopeProbe) {
        }

        List<KpiStep> workload = List.of(
            // 4 unique HR misses
            new KpiStep(hrEmployeeJwt, hrQ1, false),
            new KpiStep(hrEmployeeJwt, hrQ2, false),
            new KpiStep(hrEmployeeJwt, hrQ3, false),
            new KpiStep(hrEmployeeJwt, hrQ4, false),
            // 4 exact HR repeats -> L1 hits
            new KpiStep(hrEmployeeJwt, hrQ1, false),
            new KpiStep(hrEmployeeJwt, hrQ2, false),
            new KpiStep(hrEmployeeJwt, hrQ3, false),
            new KpiStep(hrEmployeeJwt, hrQ4, false),
            // 3 semantic paraphrases -> L2 hits (promoted to L1)
            new KpiStep(hrEmployeeJwt, hrQ1Para, false),
            new KpiStep(hrEmployeeJwt, hrQ2Para, false),
            new KpiStep(hrEmployeeJwt, hrQ3Para, false),
            // 1 repeat of promoted paraphrase -> L1 hit
            new KpiStep(hrEmployeeJwt, hrQ1Para, false),
            // 2 unique SALES misses + 1 SALES repeat -> L1 hit
            new KpiStep(salesEmployeeJwt, salesQ1, false),
            new KpiStep(salesEmployeeJwt, salesQ2, false),
            new KpiStep(salesEmployeeJwt, salesQ1, false),
            // 1 cross-scope probe: SALES asks HR's exact cached question -> MUST NOT hit cache!
            new KpiStep(salesEmployeeJwt, hrQ1, true)
        );

        int authorizedTotal = 0;
        int l1Hits = 0;
        int l2Hits = 0;
        int crossScopeCacheHits = 0;

        for (KpiStep step : workload) {
            List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", step.question()), step.jwt());
            JsonNode done = objectMapper.readTree(frames.getLast().data());
            boolean hit = done.path("cacheHit").asBoolean();
            String tier = done.path("cacheTier").asText();
            if (step.crossScopeProbe()) {
                assertThat(done.path("abstained").asBoolean()).isTrue();
                if (hit) {
                    crossScopeCacheHits++;
                }
            } else {
                assertThat(done.path("abstained").asBoolean()).isFalse();
                authorizedTotal++;
                if ("L1".equals(tier)) {
                    l1Hits++;
                } else if ("L2".equals(tier)) {
                    l2Hits++;
                }
            }
        }

        double hitRatio = (double) (l1Hits + l2Hits) / authorizedTotal;
        log.info(
            "DA19_KPI_WORKLOAD authorizedTotal={} l1Hits={} l2Hits={} hitRatio={}% crossScopeCacheHits={}",
            authorizedTotal,
            l1Hits,
            l2Hits,
            String.format(Locale.ROOT, "%.1f", hitRatio * 100.0d),
            crossScopeCacheHits
        );

        assertThat(crossScopeCacheHits).isZero();
        assertThat(l1Hits).isEqualTo(6);
        assertThat(l2Hits).isEqualTo(3);
        assertThat(hitRatio).isGreaterThanOrEqualTo(0.40d);
    }

    private static <T> ObjectProvider<T> providerOf(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }

            @Override
            public T getObject() {
                return instance;
            }
        };
    }

    private RbacVectorScope resolveHrEmployeeScope() {
        CallerContext caller = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
        CustomUserDetails principal = new CustomUserDetails(
            HR_EMPLOYEE_USER_ID,
            HR_DEPT_ID,
            Week2SseTestClient.HR_EMPLOYEE_EMAIL,
            "HR Employee",
            Role.STANDARD_EMPLOYEE,
            "test-jti-" + UUID.randomUUID(),
            java.time.Instant.now().plusSeconds(900)
        );
        var previous = SecurityContextHolder.getContext();
        try {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(context);
            return rbacVectorFilterFactory.resolveScope(caller, null);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void seedKpiDoc(String managerJwt, UUID deptId, String fileName, String content, int axis) throws Exception {
        embeddingModel.registerVector(content, ControlledSemanticEmbeddingModel.unitAxis(axis));
        client.uploadAndAwaitCompleted(
            port,
            fileName,
            "text/plain",
            content.getBytes(StandardCharsets.UTF_8),
            AccessLevel.DEPARTMENT,
            deptId,
            managerJwt
        );
    }

    private void warmAndVerifyL1Hit(String jwt, String question, String expectedFileName) throws Exception {
        List<SseFrame> first = client.collectAllSseFrames(port, Map.of("question", question), jwt);
        JsonNode firstDone = objectMapper.readTree(first.getLast().data());
        assertThat(firstDone.path("abstained").asBoolean()).isFalse();
        assertThat(first.getFirst().data()).contains(expectedFileName);

        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> second = client.collectAllSseFrames(port, Map.of("question", question), jwt);
        JsonNode secondDone = objectMapper.readTree(second.getLast().data());
        assertThat(secondDone.path("cacheHit").asBoolean()).isTrue();
        assertThat(secondDone.path("cacheTier").asText()).isEqualTo("L1");
        assertThat(second.getFirst().data()).contains(expectedFileName);
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
    }

    private void assertStillHitsL1(String jwt, String question, String expectedFileName) throws Exception {
        chatModel.resetCounts();
        embeddingModel.resetCounts();
        List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", question), jwt);
        JsonNode done = objectMapper.readTree(frames.getLast().data());
        assertThat(done.path("abstained").asBoolean()).isFalse();
        assertThat(done.path("cacheHit").asBoolean()).isTrue();
        assertThat(done.path("cacheTier").asText()).isEqualTo("L1");
        assertThat(frames.getFirst().data()).contains(expectedFileName);
        assertThat(chatModel.answerCallCount()).isZero();
        assertThat(embeddingModel.embedCount()).isZero();
    }

    private String assembleTokens(List<SseFrame> frames) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (SseFrame frame : frames) {
            if ("token".equals(frame.event())) {
                sb.append(objectMapper.readTree(frame.data()).path("delta").asText(""));
            }
        }
        return sb.toString();
    }

    private static String fmt(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }

    private record LatencySummary(double p50, double p95, double max, long overThreshold) {
        static LatencySummary of(List<Double> samples, double thresholdMs) {
            List<Double> sorted = new ArrayList<>(samples);
            Collections.sort(sorted);
            double p50 = percentile(sorted, 0.50d);
            double p95 = percentile(sorted, 0.95d);
            double max = sorted.isEmpty() ? 0.0d : sorted.getLast();
            long over = sorted.stream().filter(v -> v > thresholdMs).count();
            return new LatencySummary(p50, p95, max, over);
        }

        private static double percentile(List<Double> sorted, double q) {
            if (sorted.isEmpty()) {
                return 0.0d;
            }
            int idx = (int) Math.ceil(q * sorted.size()) - 1;
            int bounded = Math.max(0, Math.min(sorted.size() - 1, idx));
            return sorted.get(bounded);
        }
    }

    static final class AnswerHoldControl {
        private final CountDownLatch enteredLatch = new CountDownLatch(1);
        private final CountDownLatch releaseLatch = new CountDownLatch(1);

        CountDownLatch enteredLatch() {
            return enteredLatch;
        }

        CountDownLatch releaseLatch() {
            return releaseLatch;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ControlledCacheTestConfig {

        @Bean
        @Primary
        ControlledSemanticEmbeddingModel controlledSemanticEmbeddingModel() {
            return new ControlledSemanticEmbeddingModel();
        }

        @Bean
        @Primary
        ControllableCacheTestChatModel controllableCacheTestChatModel() {
            return new ControllableCacheTestChatModel();
        }

        @Bean(name = "testChatClientOverridePostProcessor")
        static BeanPostProcessor cacheTestChatClientPostProcessor(
            ObjectProvider<ControllableCacheTestChatModel> chatModelProvider
        ) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof ChatClient
                        && ("groqChatClient".equals(beanName) || "geminiChatClient".equals(beanName))) {
                        return ChatClient.create(chatModelProvider.getObject());
                    }
                    return bean;
                }
            };
        }
    }

    static final class ControlledSemanticEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 1024;
        private final ConcurrentHashMap<String, float[]> exactVectors = new ConcurrentHashMap<>();
        private final AtomicInteger embedCalls = new AtomicInteger();

        static float[] unitAxis(int axis) {
            float[] vector = new float[DIMENSIONS];
            vector[Math.floorMod(axis, DIMENSIONS)] = 1.0f;
            return vector;
        }

        static float[] unitVectorAtCosine(int primaryAxis, int orthogonalAxis, double targetCosine) {
            float[] vector = new float[DIMENSIONS];
            double clamped = Math.max(-1.0d, Math.min(1.0d, targetCosine));
            vector[Math.floorMod(primaryAxis, DIMENSIONS)] = (float) clamped;
            vector[Math.floorMod(orthogonalAxis, DIMENSIONS)] = (float) Math.sqrt(Math.max(0.0d, 1.0d - clamped * clamped));
            return vector;
        }

        void registerVector(String text, float[] vector) {
            exactVectors.put(normalizeKey(text), vector.clone());
        }

        void reset() {
            exactVectors.clear();
            embedCalls.set(0);
        }

        void resetCounts() {
            embedCalls.set(0);
        }

        int embedCount() {
            return embedCalls.get();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            embedCalls.addAndGet(instructions.size());
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(vectorFor(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        private float[] vectorFor(String rawText) {
            String key = normalizeKey(rawText);
            float[] explicit = exactVectors.get(key);
            if (explicit != null) {
                return explicit.clone();
            }
            for (Map.Entry<String, float[]> entry : exactVectors.entrySet()) {
                if (key.contains(entry.getKey()) && entry.getKey().length() >= 12) {
                    return entry.getValue().clone();
                }
            }
            if (key.contains("kpi_hr_topic_1")) {
                return unitAxis(101);
            }
            if (key.contains("kpi_hr_topic_2")) {
                return unitAxis(102);
            }
            if (key.contains("kpi_hr_topic_3")) {
                return unitAxis(103);
            }
            if (key.contains("kpi_hr_topic_4")) {
                return unitAxis(104);
            }
            if (key.contains("kpi_sales_topic_1")) {
                return unitAxis(105);
            }
            if (key.contains("kpi_sales_topic_2")) {
                return unitAxis(106);
            }
            if (key.contains("nghỉ phép") || key.contains("phép năm")) {
                return unitAxis(0);
            }
            if (key.contains("thử việc")) {
                return unitAxis(1);
            }
            if (key.contains("hoa hồng") || key.contains("doanh số") || key.contains("sales")) {
                return unitAxis(2);
            }
            if (key.contains("lương mật") || key.contains("thưởng lãnh đạo") || key.contains("vip_comp_secret")) {
                return unitAxis(3);
            }
            if (key.contains("nội quy chung") || key.contains("giờ làm việc") || key.contains("08:30")) {
                return unitAxis(4);
            }
            int fallbackAxis = 500 + Math.floorMod(key.hashCode(), 400);
            return unitAxis(fallbackAxis);
        }

        private static String normalizeKey(String text) {
            if (text == null) {
                return "";
            }
            return Normalizer.normalize(text, Normalizer.Form.NFC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
        }
    }

    static final class ControllableCacheTestChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private static final Pattern DOC_CONTENT_PATTERN = Pattern.compile("<document[^>]*>(.*?)</document>", Pattern.DOTALL);

        private final AtomicInteger rewriteCalls = new AtomicInteger();
        private final AtomicInteger answerCalls = new AtomicInteger();
        private final AtomicReference<AnswerHoldControl> armedHold = new AtomicReference<>();

        void reset() {
            resetCounts();
            releaseAllHolds();
        }

        void resetCounts() {
            rewriteCalls.set(0);
            answerCalls.set(0);
        }

        AnswerHoldControl armAnswerHold() {
            AnswerHoldControl control = new AnswerHoldControl();
            armedHold.set(control);
            return control;
        }

        void releaseAllHolds() {
            AnswerHoldControl current = armedHold.getAndSet(null);
            if (current != null) {
                current.releaseLatch().countDown();
            }
        }

        int rewriteCallCount() {
            return rewriteCalls.get();
        }

        int answerCallCount() {
            return answerCalls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String systemText = extractFirstByType(prompt, MessageType.SYSTEM);
            String latestUserText = extractLastByType(prompt, MessageType.USER);
            if (systemText.contains("<conversation_history>")) {
                rewriteCalls.incrementAndGet();
                String rewritten = rewriteFromHistory(systemText, latestUserText);
                ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(42, 14, 56))
                    .build();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rewritten))), meta);
            }

            answerCalls.incrementAndGet();
            awaitArmedHoldIfPresent();
            String groundedAnswer = buildGroundedAnswer(systemText);
            ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 32, 152))
                .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(groundedAnswer))), meta);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                answerCalls.incrementAndGet();
                awaitArmedHoldIfPresent();
                String systemText = extractFirstByType(prompt, MessageType.SYSTEM);
                String groundedAnswer = buildGroundedAnswer(systemText);
                int split = Math.max(1, groundedAnswer.length() / 2);
                ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(120, 32, 152))
                    .build();
                return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(groundedAnswer.substring(0, split))))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage(groundedAnswer.substring(split))))),
                    new ChatResponse(List.of(), meta)
                );
            });
        }

        private void awaitArmedHoldIfPresent() {
            AnswerHoldControl control = armedHold.getAndSet(null);
            if (control != null) {
                control.enteredLatch().countDown();
                try {
                    control.releaseLatch().await(15, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private static String rewriteFromHistory(String systemText, String latestUserText) {
            String lowerSystem = systemText.toLowerCase(Locale.ROOT);
            String lowerUser = latestUserText.toLowerCase(Locale.ROOT);
            if (lowerUser.contains("điều kiện")) {
                if (lowerSystem.contains("thử việc")) {
                    return "Điều kiện áp dụng chính sách thử việc cho nhân viên mới là gì?";
                }
                if (lowerSystem.contains("nghỉ phép") || lowerSystem.contains("phép năm")) {
                    return "Điều kiện áp dụng chính sách nghỉ phép năm cho nhân viên chính thức là gì?";
                }
            }
            return latestUserText;
        }

        private static String buildGroundedAnswer(String systemText) {
            Matcher citationMatcher = CITATION_ATTR_PATTERN.matcher(systemText);
            String citation = citationMatcher.find() ? citationMatcher.group(1) : "[Nguồn: internal-doc]";
            Matcher contentMatcher = DOC_CONTENT_PATTERN.matcher(systemText);
            String snippet = contentMatcher.find()
                ? contentMatcher.group(1).trim().replaceAll("\\s+", " ")
                : "Thông tin nội bộ đã xác thực.";
            if (snippet.length() > 140) {
                snippet = snippet.substring(0, 140);
            }
            return "Theo tài liệu " + citation + ": " + snippet;
        }

        private static String extractFirstByType(Prompt prompt, MessageType type) {
            for (Message message : prompt.getInstructions()) {
                if (message.getMessageType() == type && message.getText() != null) {
                    return message.getText();
                }
            }
            return "";
        }

        private static String extractLastByType(Prompt prompt, MessageType type) {
            List<Message> instructions = prompt.getInstructions();
            for (int i = instructions.size() - 1; i >= 0; i--) {
                Message message = instructions.get(i);
                if (message.getMessageType() == type && message.getText() != null) {
                    return message.getText();
                }
            }
            return "";
        }
    }
}
