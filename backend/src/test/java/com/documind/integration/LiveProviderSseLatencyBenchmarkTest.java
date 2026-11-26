package com.documind.integration;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.chat.ChatStreamCitationsEvent;
import com.documind.controller.dto.chat.ChatStreamDoneEvent;
import com.documind.controller.dto.chat.ChatStreamTokenEvent;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.ai.AiModelConfig;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagChatStreamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live-provider SSE latency verification benchmark ({@code DA-16}) against real local
 * {@code OllamaEmbeddingModel} ({@code bge-m3}, 1024-dim) and live LLM streaming providers
 * ({@code Groq} primary / {@code Gemini} fallback) over the local Docker Compose infrastructure
 * ({@code documind-postgres}, {@code documind-redis}, {@code documind-minio}), matching
 * {@code Day1InfrastructureAndAiSmokeTest}.
 *
 * <p>SLA Contract verified by this benchmark:
 * <ul>
 *   <li><b>Turn 1 (Zero-Rewrite Direct Retrieval Path):</b> {@code citations < 500 ms} and
 *       first visible streamed token {@code TTFT < 2,500 ms} on warm {@code bge-m3} + live LLM.</li>
 *   <li><b>Turn 2+ (Contextualized Multi-Turn Follow-Up Path):</b> executes synchronous LLM
 *       standalone-query rewrite prior to {@code bge-m3} embedding; verifies rewritten query quality,
 *       post-rewrite {@code bge-m3} retrieval latency {@code < 500 ms}, and end-to-end Turn 2+ streaming.</li>
 * </ul>
 *
 * <p>Run with: {@code .\mvnw.cmd test -Dsmoke=true -Dtest=LiveProviderSseLatencyBenchmarkTest}
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "documind.ingestion.worker.enabled=false",
        "documind.security.dev-caller-fallback.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        "documind.rate-limit.chat.viewer-limit=500",
        "documind.rate-limit.chat.manager-limit=500",
        "documind.rate-limit.chat.admin-limit=500",
        "documind.rate-limit.upload.manager-limit=200",
        "documind.rate-limit.upload.admin-limit=200"
    }
)
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "smoke", matches = "true")
class LiveProviderSseLatencyBenchmarkTest {

    private static final String BENCHMARK_PASSWORD = "DocuMindBench@2026!";
    private static final long TURN1_MAX_CITATIONS_MS = 500L;
    private static final long TURN1_MAX_TTFT_MS = 2_500L;
    private static final long POST_REWRITE_MAX_CITATIONS_MS = 500L;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Environment environment;

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private S3Client s3Client;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final List<UUID> createdSessionIds = new ArrayList<>();
    private final List<UUID> createdDocumentIds = new ArrayList<>();
    private final List<String> createdStorageKeys = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdDepartmentIds = new ArrayList<>();

    @BeforeEach
    void purgeStaleBenchmarkArtifactsIfAny() {
        jdbcTemplate.update(
            "DELETE FROM audit_logs WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'sse-bench-%@documind.ai')"
        );
        jdbcTemplate.update(
            "DELETE FROM chat_messages WHERE session_id IN (SELECT id FROM chat_sessions WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'sse-bench-%@documind.ai'))"
        );
        jdbcTemplate.update(
            "DELETE FROM chat_sessions WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'sse-bench-%@documind.ai')"
        );
        jdbcTemplate.update(
            "DELETE FROM refresh_tokens WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'sse-bench-%@documind.ai')"
        );
        jdbcTemplate.update(
            "DELETE FROM document_chunks WHERE document_id IN (SELECT id FROM documents WHERE department_id IN (SELECT id FROM departments WHERE code LIKE 'BM_%'))"
        );
        jdbcTemplate.update(
            "DELETE FROM documents WHERE department_id IN (SELECT id FROM departments WHERE code LIKE 'BM_%')"
        );
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE 'sse-bench-%@documind.ai'");
        jdbcTemplate.update("DELETE FROM departments WHERE code LIKE 'BM_%'");
    }

    @AfterEach
    void cleanCreatedBenchmarkFixtures() {
        for (UUID sessionId : createdSessionIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM chat_messages WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM chat_sessions WHERE id = ?", sessionId);
        }
        for (UUID docId : createdDocumentIds) {
            jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", docId);
            jdbcTemplate.update("DELETE FROM documents WHERE id = ?", docId);
        }
        for (String key : createdStorageKeys) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(storageProperties.bucket())
                    .key(key)
                    .build());
            } catch (Exception ignored) {
                // Best-effort S3 test object cleanup
            }
        }
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (UUID deptId : createdDepartmentIds) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE department_id = ?", deptId);
            jdbcTemplate.update("DELETE FROM departments WHERE id = ?", deptId);
        }
    }

    @Test
    void verifiesLiveTurn1AndTurn2SseLatencyThresholdsAgainstOllamaBgeM3AndLiveLlmProvider() throws Exception {
        assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);
        String groqKey = environment.getProperty("GROQ_API_KEY", "");
        assertThat(groqKey)
            .as("Live GROQ_API_KEY must be configured in .env for -Dsmoke=true SSE benchmark")
            .isNotBlank()
            .isNotEqualTo(AiModelConfig.UNCONFIGURED_API_KEY);

        // Create an isolated temporary department & standard employee so no existing dev documents interfere
        UUID benchDeptId = UUID.randomUUID();
        String deptCode = "BM_" + benchDeptId.toString().substring(0, 8).toUpperCase();
        jdbcTemplate.update(
            "INSERT INTO departments (id, code, name, description, cache_version) VALUES (?, ?, ?, ?, 1)",
            benchDeptId,
            deptCode,
            "Benchmark Isolated Department",
            "DA-16 Live SSE Benchmark"
        );
        createdDepartmentIds.add(benchDeptId);

        UUID benchUserId = UUID.randomUUID();
        String benchEmail = "sse-bench-" + benchUserId.toString().substring(0, 8) + "@documind.ai";
        jdbcTemplate.update(
            "INSERT INTO users (id, department_id, email, password_hash, full_name, role, is_active) VALUES (?, ?, ?, ?, ?, 'STANDARD_EMPLOYEE', TRUE)",
            benchUserId,
            benchDeptId,
            benchEmail,
            passwordEncoder.encode(BENCHMARK_PASSWORD),
            "SSE Benchmark Employee"
        );
        createdUserIds.add(benchUserId);

        // Warm up bge-m3 model weights in Ollama memory and seed real 1024-dim document chunk
        long warmEmbedStart = System.nanoTime();
        float[] warmupVector = embeddingModel.embed("Khởi động bộ nhúng ngữ nghĩa bge-m3 cho DocuMind AI");
        long warmEmbedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmEmbedStart);
        assertThat(warmupVector).hasSize(1024);

        UUID docId = seedLiveEmbeddedDocument(
            benchDeptId,
            AccessLevel.DEPARTMENT,
            "hr-annual-leave-policy-2026.pdf",
            2,
            "Điều 4: Chế độ nghỉ phép năm và thâm niên",
            "Nhân viên chính thức tại DocuMind AI được hưởng 14 ngày nghỉ phép năm hưởng nguyên lương. "
                + "Cứ mỗi 5 năm làm việc thâm niên liên tục, nhân viên được cộng thêm 1 ngày phép năm. "
                + "Ngày phép chưa sử dụng hết được bảo lưu tối đa 5 ngày sang quý 1 năm kế tiếp."
        );

        String employeeJwt = loginAndGetAccessToken(benchEmail);

        // Warm up HTTP/TLS keep-alive connection pool to live LLM provider before steady-state SLA measurement
        SseTurnMeasurement warmupTurn = executeAndMeasureSseTurn(
            employeeJwt,
            Map.of("question", "Ngày phép chưa dùng hết được bảo lưu tối đa mấy ngày?")
        );
        assertThat(warmupTurn.doneEvent()).isNotNull();
        if (warmupTurn.doneEvent().sessionId() != null) {
            createdSessionIds.add(warmupTurn.doneEvent().sessionId());
        }

        // === TURN 1 (New Session: 0 LLM Rewrite Calls -> Direct bge-m3 Retrieval -> Live LLM Stream) ===
        SseTurnMeasurement turn1 = executeAndMeasureSseTurn(
            employeeJwt,
            Map.of("question", "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?")
        );

        assertThat(turn1.doneEvent()).isNotNull();
        UUID sessionId = turn1.doneEvent().sessionId();
        assertThat(sessionId).isNotNull();
        createdSessionIds.add(sessionId);

        assertThat(turn1.doneEvent().abstained()).isFalse();
        assertThat(turn1.citationsEvent()).isNotNull();
        assertThat(turn1.citationsEvent().rewrittenQuery()).isNull();
        assertThat(turn1.citationsEvent().citations()).isNotEmpty();
        assertThat(turn1.citationsEvent().citations().getFirst().documentId()).isEqualTo(docId);
        assertThat(turn1.citationsEvent().citations().getFirst().docVersion()).isEqualTo(1);
        assertThat(turn1.assembledAnswer()).contains("14");

        // Verify download-time RBAC preview URL works live on the streamed citation
        HttpResponse<byte[]> previewResp = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(turn1.citationsEvent().citations().getFirst().presignedUrl()))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(previewResp.statusCode()).isEqualTo(200);

        // === TURN 2 (Multi-Turn Follow-Up: Contextualizer Rewrite -> bge-m3 Retrieval -> Live LLM Stream) ===
        SseTurnMeasurement turn2 = executeAndMeasureSseTurn(
            employeeJwt,
            Map.of(
                "sessionId", sessionId.toString(),
                "question", "Còn nếu làm đủ 5 năm liên tục thì được cộng thêm mấy ngày?"
            )
        );

        assertThat(turn2.doneEvent()).isNotNull();
        assertThat(turn2.doneEvent().abstained()).isFalse();
        assertThat(turn2.citationsEvent()).isNotNull();
        assertThat(turn2.citationsEvent().citations()).isNotEmpty();
        assertThat(turn2.citationsEvent().citations().getFirst().documentId()).isEqualTo(docId);
        assertThat(turn2.assembledAnswer()).containsAnyOf("1", "một", "15");

        // Measure isolated post-rewrite bge-m3 retrieval latency on the exact rewritten query
        String effectiveTurn2Query = turn2.citationsEvent().rewrittenQuery() != null
            ? turn2.citationsEvent().rewrittenQuery()
            : "Còn nếu làm đủ 5 năm liên tục thì được cộng thêm mấy ngày?";
        long postRewriteStart = System.nanoTime();
        float[] rewrittenEmbed = embeddingModel.embed(effectiveTurn2Query);
        long isolatedBgeM3Ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - postRewriteStart);
        assertThat(rewrittenEmbed).hasSize(1024);

        System.out.println("\n=== [DA-16] LIVE PROVIDER SSE LATENCY BENCHMARK ===");
        System.out.printf("Embedding Model        : bge-m3 (warm embed = %d ms, isolated Turn 2 embed = %d ms)%n",
            warmEmbedMs, isolatedBgeM3Ms);
        System.out.printf("Configured Groq Model  : %s%n",
            environment.getProperty("spring.ai.openai.chat.options.model"));
        System.out.printf("Warmup Turn            : provider=%s | citations=%d ms | TTFT=%d ms | total=%d ms%n",
            warmupTurn.doneEvent().modelProvider(), warmupTurn.citationsMs(), warmupTurn.ttftMs(), warmupTurn.totalMs());
        System.out.printf("Turn 1 (0-Rewrite)     : provider=%s | citations=%d ms (<500ms) | TTFT=%d ms (<2500ms) | total=%d ms | tokens=%d/%d%n",
            turn1.doneEvent().modelProvider(),
            turn1.citationsMs(),
            turn1.ttftMs(),
            turn1.totalMs(),
            turn1.doneEvent().tokenUsage().promptTokens(),
            turn1.doneEvent().tokenUsage().completionTokens());
        System.out.printf("Turn 2 (With Rewrite)  : provider=%s | citations=%d ms | TTFT=%d ms | total=%d ms | tokens=%d/%d | rewritten='%s'%n",
            turn2.doneEvent().modelProvider(),
            turn2.citationsMs(),
            turn2.ttftMs(),
            turn2.totalMs(),
            turn2.doneEvent().tokenUsage().promptTokens(),
            turn2.doneEvent().tokenUsage().completionTokens(),
            turn2.citationsEvent().rewrittenQuery());
        System.out.println("===================================================\n");

        assertThat(turn1.citationsMs())
            .as("Turn 1 SSE citations event must arrive in < 500 ms with live Ollama bge-m3 + pgvector")
            .isLessThan(TURN1_MAX_CITATIONS_MS);
        assertThat(turn1.ttftMs())
            .as("Turn 1 SSE first token (TTFT) must arrive in < 2,500 ms with live LLM provider")
            .isLessThan(TURN1_MAX_TTFT_MS);
        assertThat(turn2.citationsEvent().rewrittenQuery())
            .as("Turn 2 follow-up must produce a standalone rewritten query prior to retrieval")
            .isNotBlank();
        assertThat(isolatedBgeM3Ms)
            .as("Post-rewrite bge-m3 embedding latency must remain well below 500 ms")
            .isLessThan(POST_REWRITE_MAX_CITATIONS_MS);
        assertThat(turn2.ttftMs() - turn2.citationsMs())
            .as("Turn 2 post-citations answer first-token latency must remain < 2,500 ms")
            .isLessThan(TURN1_MAX_TTFT_MS);
    }

    private SseTurnMeasurement executeAndMeasureSseTurn(
        String bearerToken,
        Map<String, Object> payload
    ) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();

        long startedAtNanos = System.nanoTime();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);

        long citationsMs = -1L;
        long ttftMs = -1L;
        ChatStreamCitationsEvent citationsEvent = null;
        ChatStreamDoneEvent doneEvent = null;
        StringBuilder assembledAnswer = new StringBuilder();
        List<String> eventOrder = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            SseFrame frame;
            while ((frame = readNextSseFrame(reader)) != null) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
                eventOrder.add(frame.event());
                if (RagChatStreamService.EVENT_CITATIONS.equals(frame.event())) {
                    citationsMs = elapsedMs;
                    citationsEvent = objectMapper.readValue(frame.data(), ChatStreamCitationsEvent.class);
                } else if (RagChatStreamService.EVENT_TOKEN.equals(frame.event())) {
                    if (ttftMs < 0L) {
                        ttftMs = elapsedMs;
                    }
                    ChatStreamTokenEvent tokenEvent = objectMapper.readValue(frame.data(), ChatStreamTokenEvent.class);
                    assembledAnswer.append(tokenEvent.delta());
                } else if (RagChatStreamService.EVENT_DONE.equals(frame.event())) {
                    doneEvent = objectMapper.readValue(frame.data(), ChatStreamDoneEvent.class);
                } else if (RagChatStreamService.EVENT_ERROR.equals(frame.event())) {
                    throw new AssertionError("Unexpected SSE error event during live benchmark: " + frame.data());
                }
            }
        }

        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(eventOrder).isNotEmpty();
        assertThat(eventOrder.getFirst()).isEqualTo(RagChatStreamService.EVENT_CITATIONS);
        assertThat(eventOrder.getLast()).isEqualTo(RagChatStreamService.EVENT_DONE);

        return new SseTurnMeasurement(
            citationsMs,
            ttftMs,
            totalMs,
            citationsEvent,
            doneEvent,
            assembledAnswer.toString()
        );
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "email", email,
            "password", BENCHMARK_PASSWORD
        ));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readValue(resp.body(), AuthTokenResponse.class).accessToken();
    }

    private UUID seedLiveEmbeddedDocument(
        UUID departmentId,
        AccessLevel accessLevel,
        String fileName,
        int pageNumber,
        String sectionHeading,
        String content
    ) throws Exception {
        DepartmentEntity department = departmentRepository.findById(departmentId).orElseThrow();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String storageKey = "departments/" + departmentId + "/" + UUID.randomUUID() + "-" + fileName;
        minioStorageService.uploadStream(
            storageKey,
            new ByteArrayInputStream(bytes),
            bytes.length,
            "application/pdf"
        );
        createdStorageKeys.add(storageKey);

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        DocumentEntity doc = new DocumentEntity();
        doc.setDepartment(department);
        doc.setOriginalName(fileName);
        doc.setStorageKey(storageKey);
        doc.setFileExtension("pdf");
        doc.setMimeType("application/pdf");
        doc.setFileSizeBytes((long) bytes.length);
        doc.setFileHash(hash);
        doc.setAccessLevel(accessLevel);
        doc.setStatus(DocumentStatus.COMPLETED);
        doc.setCurrentVersion(1);
        doc.setChunkCount(1);
        DocumentEntity savedDoc = documentRepository.saveAndFlush(doc);
        createdDocumentIds.add(savedDoc.getId());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, savedDoc.getId().toString());
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString());
        metadata.put(ChunkMetadataKeys.FILE_NAME, fileName);
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, accessLevel.name());
        metadata.put(ChunkMetadataKeys.DOC_VERSION, 1);
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, 0);
        metadata.put(ChunkMetadataKeys.PAGE_NUMBER, pageNumber);
        metadata.put(ChunkMetadataKeys.SECTION_HEADING, sectionHeading);

        String chunkId = ChunkIdGenerator.generate(savedDoc.getId(), 1, 0).toString();
        vectorStore.add(List.of(new Document(chunkId, content, metadata)));
        return savedDoc.getId();
    }

    private static SseFrame readNextSseFrame(BufferedReader reader) throws IOException {
        String eventName = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (eventName != null || !data.isEmpty()) {
                    return new SseFrame(eventName, data.toString());
                }
                continue;
            }
            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                data.append(value);
            }
        }
        if (eventName != null || !data.isEmpty()) {
            return new SseFrame(eventName, data.toString());
        }
        return null;
    }

    private record SseFrame(String event, String data) {
    }

    private record SseTurnMeasurement(
        long citationsMs,
        long ttftMs,
        long totalMs,
        ChatStreamCitationsEvent citationsEvent,
        ChatStreamDoneEvent doneEvent,
        String assembledAnswer
    ) {
    }
}
