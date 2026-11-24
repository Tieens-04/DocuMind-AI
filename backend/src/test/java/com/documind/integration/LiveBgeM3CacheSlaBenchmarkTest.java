package com.documind.integration;

import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.CacheTier;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.security.CustomUserDetails;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagCacheService;
import com.documind.service.rag.RbacVectorFilterFactory;
import com.documind.service.rag.RbacVectorScope;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live {@code Ollama bge-m3} + Testcontainers Redis 7 / PostgreSQL {@code pgvector} / MinIO SLA verification
 * benchmark for {@code DA-19}, isolating real {@code bge-m3} embedding + {@code pgvector} L2 latency from
 * external chat-completion API keys.
 *
 * <p>Run with Docker Desktop + local Ollama {@code bge-m3}:
 * {@code .\mvnw.cmd test -Dsmoke=true -Dtest=LiveBgeM3CacheSlaBenchmarkTest}
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false",
        "documind.security.dev-caller-fallback.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1"
    }
)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "smoke", matches = "true")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    LiveBgeM3CacheSlaBenchmarkTest.LiveBenchmarkChatConfig.class
})
class LiveBgeM3CacheSlaBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(LiveBgeM3CacheSlaBenchmarkTest.class);
    private static final String BENCHMARK_PASSWORD = "DocuMindCacheBench@2026!";

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private RagCacheService ragCacheService;

    @Autowired
    private RbacVectorFilterFactory rbacVectorFilterFactory;

    @Autowired
    private DeterministicSeedChatModel seedChatModel;

    private final List<UUID> createdDepartmentIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdDocumentIds = new ArrayList<>();
    private final List<String> createdStorageKeys = new ArrayList<>();
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
    void resetCounters() {
        seedChatModel.reset();
        AbstractIntegrationTest.flushRedis(stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM chat_sessions WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
        for (UUID docId : createdDocumentIds) {
            jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", docId);
        }
        createdDocumentIds.clear();
        for (String key : createdStorageKeys) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(storageProperties.bucket()).key(key).build());
            } catch (Exception ignored) {
                // Best-effort cleanup
            }
        }
        createdStorageKeys.clear();
        for (UUID deptId : createdDepartmentIds) {
            jdbcTemplate.update("DELETE FROM semantic_cache WHERE rbac_scope LIKE ?", "%" + deptId + "%");
            jdbcTemplate.update("DELETE FROM audit_logs WHERE department_id = ?", deptId);
            jdbcTemplate.update("DELETE FROM departments WHERE id = ?", deptId);
        }
        createdDepartmentIds.clear();
        AbstractIntegrationTest.flushRedis(stringRedisTemplate);
    }

    @Test
    void measuresLiveBgeM3L1AndL2CacheSlaOverRealSse() throws Exception {
        assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);

        UUID deptId = UUID.randomUUID();
        String deptCode = "CB_" + deptId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        jdbcTemplate.update(
            "INSERT INTO departments (id, code, name, description, cache_version) VALUES (?, ?, ?, ?, 1)",
            deptId, deptCode, "Cache Benchmark Dept", "DA-19 Live bge-m3 Benchmark"
        );
        createdDepartmentIds.add(deptId);

        UUID userId = UUID.randomUUID();
        String email = "cache-bench-" + userId.toString().substring(0, 8) + "@documind.ai";
        jdbcTemplate.update(
            "INSERT INTO users (id, department_id, email, password_hash, full_name, role, is_active) VALUES (?, ?, ?, ?, ?, 'STANDARD_EMPLOYEE', TRUE)",
            userId, deptId, email, passwordEncoder.encode(BENCHMARK_PASSWORD), "Cache Benchmark User"
        );
        createdUserIds.add(userId);

        // Warm live bge-m3 weights
        float[] warmVec = embeddingModel.embed("Khởi động bge-m3 cho kiểm thử hiệu năng cache DA-19");
        assertThat(warmVec).hasSize(1024);

        String content = "Chính sách nghỉ phép năm 2026: nhân viên chính thức tại DocuMind AI được hưởng 14 ngày nghỉ phép năm hưởng nguyên lương.";
        seedDocumentChunk(deptId, "live-cache-leave.txt", content);

        String jwt = client.login(port, email, BENCHMARK_PASSWORD).accessToken();
        String baseQuestion = "Nhân viên chính thức tại DocuMind AI được hưởng bao nhiêu ngày nghỉ phép năm?";
        List<SseFrame> seed = client.collectAllSseFrames(port, Map.of("question", baseQuestion), jwt);
        assertThat(objectMapper.readTree(seed.getLast().data()).path("abstained").asBoolean()).isFalse();
        assertThat(seedChatModel.answerCallCount()).isEqualTo(1);

        RbacVectorScope scope = resolveScope(userId, deptId, email);

        // L1: 5 warm-ups + 12 measured SSE + lookup samples
        seedChatModel.reset();
        int l1Warmup = 5;
        int l1Samples = 12;
        for (int i = 0; i < l1Warmup; i++) {
            client.collectAllSseFrames(port, Map.of("question", baseQuestion), jwt);
        }
        List<Double> l1SseMs = new ArrayList<>(l1Samples);
        for (int i = 0; i < l1Samples; i++) {
            long start = System.nanoTime();
            List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", baseQuestion), jwt);
            l1SseMs.add((System.nanoTime() - start) / 1_000_000.0d);
            JsonNode done = objectMapper.readTree(frames.getLast().data());
            assertThat(done.path("cacheHit").asBoolean()).isTrue();
            assertThat(done.path("cacheTier").asText()).isEqualTo("L1");
        }
        List<Double> l1LookupMs = new ArrayList<>(l1Samples);
        for (int i = 0; i < l1Samples; i++) {
            RagCacheService.CacheLookupResult res = ragCacheService.lookup(scope, baseQuestion, List.of());
            assertThat(res.cacheTier()).isEqualTo(CacheTier.L1);
            l1LookupMs.add(res.l1LookupNanos() / 1_000_000.0d);
        }

        // L2: 3 warm-ups + 10 verified high-similarity paraphrases (cosine >= 0.94 on bge-m3)
        List<String> l2WarmupParaphrases = List.of(
            "Nhân viên chính thức tại DocuMind AI được hưởng bao nhiêu ngày nghỉ phép hàng năm?",
            "Nhân viên chính thức tại DocuMind AI có được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Tổng số ngày nghỉ phép năm mà nhân viên chính thức tại DocuMind AI được hưởng là bao nhiêu?"
        );
        for (String warmup : l2WarmupParaphrases) {
            RagCacheService.CacheLookupResult warmLookup = ragCacheService.lookup(scope, warmup, List.of());
            assertThat(warmLookup.cacheTier()).isEqualTo(CacheTier.L2);
        }

        List<String> l2Paraphrases = List.of(
            "Nhân viên chính thức ở DocuMind AI được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Tại DocuMind AI, nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Nhân viên chính thức tại DocuMind AI được hưởng mấy ngày nghỉ phép năm?",
            "Bao nhiêu ngày nghỉ phép năm mà nhân viên chính thức tại DocuMind AI được hưởng?",
            "Nhân viên chính thức của DocuMind AI được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Số ngày nghỉ phép năm nhân viên chính thức tại DocuMind AI được hưởng là bao nhiêu?",
            "Nhân viên chính thức tại công ty DocuMind AI được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Ở DocuMind AI, nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?",
            "Nhân viên chính thức tại DocuMind AI được cấp bao nhiêu ngày nghỉ phép năm?",
            "Nhân viên chính thức tại DocuMind AI được nghỉ phép năm bao nhiêu ngày?"
        );
        List<Double> embedMs = new ArrayList<>(l2Paraphrases.size());
        List<Double> l2QueryMs = new ArrayList<>(l2Paraphrases.size());
        List<Double> l2SseMs = new ArrayList<>(l2Paraphrases.size());
        long ver = ragCacheService.readAuthoritativeVersion(scope);
        for (String p : l2Paraphrases) {
            stringRedisTemplate.delete(RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), ver, RagCacheService.hashStandaloneQuery(p)));
            RagCacheService.CacheLookupResult lookup = ragCacheService.lookup(scope, p, List.of());
            assertThat(lookup.cacheTier())
                .as("Expected genuine L2 semantic hit (cosine >= 0.94) on bge-m3 for: %s", p)
                .isEqualTo(CacheTier.L2);
            embedMs.add(lookup.embeddingNanos() / 1_000_000.0d);
            l2QueryMs.add(lookup.l2LookupNanos() / 1_000_000.0d);

            // Delete promoted L1 key so SSE measures full bge-m3 embed + pgvector L2 + replay
            stringRedisTemplate.delete(RagCacheService.buildStandaloneL1Key(scope.cacheScopeKey(), ver, RagCacheService.hashStandaloneQuery(p)));
            long start = System.nanoTime();
            List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", p), jwt);
            l2SseMs.add((System.nanoTime() - start) / 1_000_000.0d);
            JsonNode done = objectMapper.readTree(frames.getLast().data());
            assertThat(done.path("cacheHit").asBoolean()).isTrue();
            assertThat(done.path("cacheTier").asText()).isEqualTo("L2");
        }
        assertThat(seedChatModel.answerCallCount()).isZero();

        long over15 = countOver(l1LookupMs, 15.0d);
        long over50 = countOver(l1SseMs, 50.0d);
        long over70 = countOver(l2QueryMs, 70.0d);
        long over80 = countOver(l2SseMs, 80.0d);
        log.info(
            "DA19_LIVE_BGEM3_SLA l1Warmup={} l1Samples={} l1LookupMs=[p50={}, p95={}, over15ms={}] l1SseMs=[p50={}, p95={}, over50ms={}] l2Warmup={} l2Samples={} bgeM3EmbedMs=[p50={}, p95={}] l2QueryMs=[p50={}, p95={}, over70ms={}] l2SseMs=[p50={}, p95={}, over80ms={}]",
            l1Warmup, l1Samples, fmt(percentile(l1LookupMs, 0.50)), fmt(percentile(l1LookupMs, 0.95)), over15,
            fmt(percentile(l1SseMs, 0.50)), fmt(percentile(l1SseMs, 0.95)), over50,
            l2WarmupParaphrases.size(), l2Paraphrases.size(),
            fmt(percentile(embedMs, 0.50)), fmt(percentile(embedMs, 0.95)),
            fmt(percentile(l2QueryMs, 0.50)), fmt(percentile(l2QueryMs, 0.95)), over70,
            fmt(percentile(l2SseMs, 0.50)), fmt(percentile(l2SseMs, 0.95)), over80
        );
        assertThat(percentile(l1LookupMs, 0.95)).isLessThan(15.0d);
        assertThat(percentile(l1SseMs, 0.95)).isLessThan(50.0d);
        assertThat(percentile(l2QueryMs, 0.95)).isLessThan(70.0d);
        assertThat(percentile(l2SseMs, 0.95)).isLessThan(80.0d);
    }

    private RbacVectorScope resolveScope(UUID userId, UUID deptId, String email) {
        var prevCtx = SecurityContextHolder.getContext();
        try {
            var principal = new CustomUserDetails(
                userId, deptId, email, "Cache Benchmark User", Role.STANDARD_EMPLOYEE, "bench-jti", Instant.now().plusSeconds(900)
            );
            var ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
            SecurityContextHolder.setContext(ctx);
            return rbacVectorFilterFactory.resolveScope(new CallerContext(userId, deptId, Role.STANDARD_EMPLOYEE, "127.0.0.1"), null);
        } finally {
            SecurityContextHolder.setContext(prevCtx);
        }
    }

    private void seedDocumentChunk(UUID deptId, String fileName, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        UUID docId = UUID.randomUUID();
        String storageKey = minioStorageService.buildStorageKey(deptId, docId, 1, fileName);
        minioStorageService.uploadStream(storageKey, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
        createdStorageKeys.add(storageKey);

        var entity = new com.documind.domain.entity.DocumentEntity();
        entity.setId(docId);
        entity.setDepartment(departmentRepository.getReferenceById(deptId));
        entity.setOriginalName(fileName);
        entity.setStorageKey(storageKey);
        entity.setFileExtension("txt");
        entity.setMimeType("text/plain");
        entity.setFileSizeBytes((long) bytes.length);
        entity.setFileHash(sha256);
        entity.setAccessLevel(AccessLevel.DEPARTMENT);
        entity.setStatus(DocumentStatus.COMPLETED);
        entity.setCurrentVersion(1);
        entity.setChunkCount(1);
        documentRepository.saveAndFlush(entity);
        createdDocumentIds.add(docId);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, docId.toString());
        metadata.put(ChunkMetadataKeys.FILE_NAME, fileName);
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, deptId.toString());
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, AccessLevel.DEPARTMENT.name());
        metadata.put(ChunkMetadataKeys.DOC_VERSION, 1);
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, 0);
        vectorStore.add(List.of(new Document(ChunkIdGenerator.generate(docId, 1, 0).toString(), content, metadata)));
    }

    private static long countOver(List<Double> values, double thresholdMs) {
        return values.stream().filter(v -> v > thresholdMs).count();
    }

    private static double percentile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int idx = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(q * sorted.size()) - 1));
        return sorted.get(idx);
    }

    private static String fmt(double ms) {
        return String.format(Locale.ROOT, "%.2f", ms);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LiveBenchmarkChatConfig {

        @Bean
        @Primary
        DeterministicSeedChatModel deterministicSeedChatModel() {
            return new DeterministicSeedChatModel();
        }

        @Bean(name = "liveBenchmarkChatClientOverride")
        static BeanPostProcessor chatClientPostProcessor(ObjectProvider<DeterministicSeedChatModel> chatModelProvider) {
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

    static final class DeterministicSeedChatModel implements ChatModel {

        private final AtomicInteger answerCalls = new AtomicInteger();

        void reset() {
            answerCalls.set(0);
        }

        int answerCallCount() {
            return answerCalls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            answerCalls.incrementAndGet();
            String text = "Nhân viên chính thức tại DocuMind AI được hưởng 14 ngày nghỉ phép năm [Nguồn: live-cache-leave.txt].";
            ChatResponseMetadata meta = ChatResponseMetadata.builder().usage(new DefaultUsage(60, 20, 80)).build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), meta);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                answerCalls.incrementAndGet();
                String text = "Nhân viên chính thức tại DocuMind AI được hưởng 14 ngày nghỉ phép năm [Nguồn: live-cache-leave.txt].";
                ChatResponseMetadata meta = ChatResponseMetadata.builder().usage(new DefaultUsage(60, 20, 80)).build();
                return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(text)))),
                    new ChatResponse(List.of(), meta)
                );
            });
        }
    }
}
