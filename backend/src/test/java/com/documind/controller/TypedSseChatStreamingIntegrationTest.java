package com.documind.controller;

import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.services.s3.S3Client;

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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Testcontainers integration suite ({@code pgvector/pgvector:pg16}, {@code redis:7-alpine}, {@code minio})
 * validating {@code DA-16}: typed SSE streaming ({@code citations}, {@code token}, {@code done}, {@code error}),
 * real-time {@code citations < 500ms} and {@code TTFT < 2.5s}, atomic post-stream DB persistence,
 * multi-turn contextualization + RBAC zero-leakage over SSE, Groq-to-Gemini streaming failover,
 * and client disconnect ({@code AbortController}) upstream cancellation with {@code 499} FinOps audit logging.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=false",
        "documind.security.dev-caller-fallback.enabled=false",
        "documind.ai.gemini.api-key=test-gemini-fallback-key"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class,
    TypedSseChatStreamingIntegrationTest.DualProviderStreamingTestConfig.class
})
class TypedSseChatStreamingIntegrationTest {

    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    private static final String ADMIN_EMAIL = "admin@documind.ai";
    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String HR_EMPLOYEE_EMAIL = "employee.hr@documind.ai";
    private static final String SALES_EMPLOYEE_EMAIL = "employee.sales@documind.ai";

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;

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
    private MinioStorageService minioStorageService;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    @Qualifier("testGroqStreamingModel")
    private ControllableStreamingChatModel groqModel;

    @Autowired
    @Qualifier("testGeminiStreamingModel")
    private ControllableStreamingChatModel geminiModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @BeforeEach
    void setUp() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
        groqModel.reset();
        geminiModel.reset();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        groqModel.reset();
        geminiModel.reset();
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
    }

    @Test
    void streamEmitsCitationsUnder500msStreamsTokensUnderTtftAndPersistsCompleteTurnAndAudit() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-leave-policy.pdf",
            2,
            "Chính sách nghỉ phép năm",
            "Nhân viên chính thức được hưởng 14 ngày phép năm. Làm đủ 5 năm thâm niên được cộng thêm 1 ngày phép."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
        CountDownLatch firstTokenGate = groqModel.armFirstTokenGate();

        long requestStartNanos = System.nanoTime();
        HttpResponse<InputStream> response = openSseRequest(
            hrEmployeeJwt,
            Map.of("question", "Nhân viên chính thức được nghỉ phép năm bao nhiêu ngày?")
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
            .contains("text/event-stream")
            .containsIgnoringCase("UTF-8");
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL).orElse(""))
            .contains("no-cache");
        assertThat(response.headers().firstValue("X-Accel-Buffering").orElse(""))
            .isEqualTo("no");

        List<SseFrame> frames = new ArrayList<>();
        long citationsArrivalMs;
        long firstTokenArrivalMs;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            // 1. Citations frame MUST flush immediately (< 500ms) even while LLM firstTokenGate is still held
            SseFrame citationsFrame = readNextSseFrame(reader);
            citationsArrivalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartNanos);
            assertThat(citationsFrame).isNotNull();
            assertThat(citationsFrame.event()).isEqualTo("citations");
            frames.add(citationsFrame);

            // Release LLM streaming after proving citations arrived before any LLM token was generated
            firstTokenGate.countDown();

            SseFrame firstTokenFrame = readNextSseFrame(reader);
            firstTokenArrivalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartNanos);
            assertThat(firstTokenFrame).isNotNull();
            assertThat(firstTokenFrame.event()).isEqualTo("token");
            frames.add(firstTokenFrame);

            SseFrame next;
            while ((next = readNextSseFrame(reader)) != null) {
                frames.add(next);
            }
        }

        assertThat(citationsArrivalMs).isLessThan(500L);
        assertThat(firstTokenArrivalMs).isLessThan(2_500L);
        assertThat(groqModel.wasTransactionActiveOnAnyCall()).isFalse();
        assertThat(groqModel.rewriteCallCount()).isZero(); // Turn 1 skips rewrite

        JsonNode citationsJson = objectMapper.readTree(frames.get(0).data());
        assertThat(citationsJson.get("rewrittenQuery").isNull()).isTrue();
        assertThat(citationsJson.get("citations")).hasSize(1);
        assertThat(citationsJson.get("citations").get(0).get("fileName").asText()).isEqualTo("hr-leave-policy.pdf");
        assertThat(citationsJson.get("citations").get(0).get("pageNumber").asInt()).isEqualTo(2);
        assertThat(citationsJson.get("citations").get(0).get("presignedUrl").asText()).contains("#page=2");

        StringBuilder streamedAnswer = new StringBuilder();
        for (int i = 1; i < frames.size() - 1; i++) {
            assertThat(frames.get(i).event()).isEqualTo("token");
            streamedAnswer.append(objectMapper.readTree(frames.get(i).data()).get("delta").asText());
        }
        String expectedAnswer = streamedAnswer.toString().trim();
        assertThat(expectedAnswer).contains("hr-leave-policy.pdf");

        SseFrame doneFrame = frames.getLast();
        assertThat(doneFrame.event()).isEqualTo("done");
        JsonNode doneJson = objectMapper.readTree(doneFrame.data());
        UUID sessionId = UUID.fromString(doneJson.get("sessionId").asText());
        UUID messageId = UUID.fromString(doneJson.get("messageId").asText());
        assertThat(doneJson.get("abstained").asBoolean()).isFalse();
        assertThat(doneJson.get("modelProvider").asText()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(doneJson.get("cacheHit").asBoolean()).isFalse();
        assertThat(doneJson.get("cacheTier").asText()).isEqualTo("NONE");
        assertThat(doneJson.get("tokenUsage").get("promptTokens").asInt()).isEqualTo(130);
        assertThat(doneJson.get("tokenUsage").get("completionTokens").asInt()).isEqualTo(42);
        assertThat(doneJson.get("tokenUsage").get("totalTokens").asInt()).isEqualTo(172);
        assertThat(doneJson.get("latencyMs").asLong()).isGreaterThanOrEqualTo(0L);

        // Verify PostgreSQL persistence matches streamed output & citations 100%
        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
            """
            SELECT id, role, content, citations::text AS citations_json, abstained,
                   prompt_tokens, completion_tokens, model_provider
            FROM chat_messages
            WHERE session_id = ?
            ORDER BY created_at ASC, id ASC
            """,
            sessionId
        );
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role")).isEqualTo("USER");
        assertThat(messages.get(1).get("id")).isEqualTo(messageId);
        assertThat(messages.get(1).get("role")).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).get("content")).isEqualTo(expectedAnswer);
        assertThat(messages.get(1).get("abstained")).isEqualTo(false);
        assertThat(messages.get(1).get("prompt_tokens")).isEqualTo(130);
        assertThat(messages.get(1).get("completion_tokens")).isEqualTo(42);
        assertThat(messages.get(1).get("model_provider")).isEqualTo(RagConstants.PROVIDER_GROQ);
        JsonNode persistedCitations = objectMapper.readTree((String) messages.get(1).get("citations_json"));
        assertThat(persistedCitations).hasSize(1);
        assertThat(persistedCitations.get(0).get("fileName").asText()).isEqualTo("hr-leave-policy.pdf");

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT session_id, status_code, prompt_tokens, completion_tokens, abstained, cache_hit, cache_tier "
                + "FROM audit_logs WHERE action_type = 'CHAT_QUERY'"
        );
        assertThat(audit.get("session_id")).isEqualTo(sessionId);
        assertThat(audit.get("status_code")).isEqualTo(200);
        assertThat(audit.get("prompt_tokens")).isEqualTo(130);
        assertThat(audit.get("completion_tokens")).isEqualTo(42);
        assertThat(audit.get("abstained")).isEqualTo(false);
        assertThat(audit.get("cache_hit")).isEqualTo(false);
        assertThat(audit.get("cache_tier")).isEqualTo("NONE");
    }

    @Test
    void streamMultiTurnFollowUpContextualizesAndEnforcesRbacZeroCrossDepartmentLeakageAndPreFlightErrors() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-leave-policy.pdf",
            2,
            "Chính sách nghỉ phép năm",
            "Nhân viên chính thức được hưởng 14 ngày phép năm. Làm đủ 5 năm thâm niên được cộng thêm 1 ngày phép."
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.CONFIDENTIAL,
            "hr-exec-compensation.pdf",
            1,
            "Thưởng hoa hồng điều hành mật",
            "Bí mật hoa hồng doanh số điều hành phòng HR: chi trả hoa hồng ngày 15 với hệ số thưởng 3.5x cho cấp quản lý."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
        String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);
        String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
        String adminJwt = loginAndGetAccessToken(ADMIN_EMAIL);

        // Turn 1: HR employee opens a streaming session
        List<SseFrame> turn1 = collectAllSseFrames(
            hrEmployeeJwt,
            Map.of("question", "Chính sách nghỉ phép năm cho nhân viên chính thức?")
        );
        UUID sessionId = UUID.fromString(objectMapper.readTree(turn1.getLast().data()).get("sessionId").asText());

        // Turn 2: follow-up pronoun question triggers QueryContextualizerService and sums Rewrite + Answer tokens
        List<SseFrame> turn2 = collectAllSseFrames(
            hrEmployeeJwt,
            Map.of("question", "Còn làm đủ 5 năm thì sao?", "sessionId", sessionId.toString())
        );
        JsonNode turn2Citations = objectMapper.readTree(turn2.get(0).data());
        assertThat(turn2Citations.get("rewrittenQuery").asText())
            .contains("5 năm")
            .contains("nghỉ phép năm");
        JsonNode turn2Done = objectMapper.readTree(turn2.getLast().data());
        // Rewrite (45/14) + Answer (130/42) = 175/56 = 231
        assertThat(turn2Done.get("tokenUsage").get("promptTokens").asInt()).isEqualTo(175);
        assertThat(turn2Done.get("tokenUsage").get("completionTokens").asInt()).isEqualTo(56);
        assertThat(turn2Done.get("tokenUsage").get("totalTokens").asInt()).isEqualTo(231);

        // RBAC Zero-Leakage over SSE: Sales employee and HR standard employee MUST NOT see HR CONFIDENTIAL doc
        groqModel.resetCounts();
        for (String unauthorizedJwt : List.of(salesEmployeeJwt, hrEmployeeJwt)) {
            List<SseFrame> oosFrames = collectAllSseFrames(
                unauthorizedJwt,
                Map.of("question", "Hệ số thưởng hoa hồng doanh số bí mật lương thưởng điều hành là bao nhiêu?")
            );
            assertThat(oosFrames).hasSize(3);
            assertThat(oosFrames.get(0).event()).isEqualTo("citations");
            assertThat(objectMapper.readTree(oosFrames.get(0).data()).get("citations")).isEmpty();
            assertThat(oosFrames.get(1).event()).isEqualTo("token");
            assertThat(objectMapper.readTree(oosFrames.get(1).data()).get("delta").asText())
                .isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(oosFrames.get(2).event()).isEqualTo("done");
            assertThat(objectMapper.readTree(oosFrames.get(2).data()).get("abstained").asBoolean()).isTrue();
            assertThat(objectMapper.readTree(oosFrames.get(2).data()).get("tokenUsage").get("totalTokens").asInt()).isZero();
        }
        assertThat(groqModel.answerCallCount()).isZero();

        // HR Department Manager CAN read HR CONFIDENTIAL doc via SSE
        List<SseFrame> managerFrames = collectAllSseFrames(
            hrManagerJwt,
            Map.of("question", "Hệ số thưởng hoa hồng doanh số bí mật lương thưởng điều hành là bao nhiêu?")
        );
        JsonNode managerCitations = objectMapper.readTree(managerFrames.get(0).data());
        assertThat(managerCitations.get("citations")).isNotEmpty();
        assertThat(managerCitations.get("citations").get(0).get("fileName").asText()).isEqualTo("hr-exec-compensation.pdf");
        assertThat(objectMapper.readTree(managerFrames.getLast().data()).get("abstained").asBoolean()).isFalse();

        // Pre-flight HTTP ProblemDetail checks (401 / 400 / 403 / 404 before opening SSE)
        HttpRequest unauthReq = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"Chính sách nghỉ phép năm?\"}", StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> unauthResp = httpClient.send(unauthReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(unauthResp.statusCode()).isEqualTo(401);
        assertThat(unauthResp.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(""))
            .contains("Bearer");
        assertThat(objectMapper.readTree(unauthResp.body()).get("errorCode").asText())
            .isEqualTo("AUTHENTICATION_REQUIRED");

        HttpResponse<String> badReq = sendRawPost(hrEmployeeJwt, Map.of("question", "   "));
        assertThat(badReq.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(badReq.body()).get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");

        HttpResponse<String> forbiddenDept = sendRawPost(
            hrEmployeeJwt,
            Map.of("question", "Chính sách nghỉ phép năm?", "departmentId", IT_DEPT_ID.toString())
        );
        assertThat(forbiddenDept.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(forbiddenDept.body()).get("errorCode").asText()).isEqualTo("DOCUMENT_ACCESS_DENIED");

        // Strict Owner-Only 404 even for SYSTEM_ADMIN targeting HR employee's sessionId
        HttpResponse<String> foreignSession = sendRawPost(
            adminJwt,
            Map.of("question", "Tiếp tục phiên của người khác?", "sessionId", sessionId.toString())
        );
        assertThat(foreignSession.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(foreignSession.body()).get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void streamFailsOverFromGroqToGeminiWhenPrimaryFailsBeforeFirstToken() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            "company-probation-policy.pdf",
            1,
            "Thử việc",
            "Thời gian thử việc tối đa 60 ngày đối với vị trí chuyên môn."
        );

        String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
        groqModel.failNextStreamBeforeFirstToken();

        List<SseFrame> frames = collectAllSseFrames(
            salesEmployeeJwt,
            Map.of("question", "Thời gian thử việc tối đa bao nhiêu ngày?")
        );

        assertThat(frames.get(0).event()).isEqualTo("citations");
        assertThat(frames.stream().filter(f -> f.event().equals("error")).count()).isZero();
        JsonNode done = objectMapper.readTree(frames.getLast().data());
        assertThat(done.get("modelProvider").asText()).isEqualTo(RagConstants.PROVIDER_GEMINI);
        assertThat(done.get("abstained").asBoolean()).isFalse();
        assertThat(geminiModel.answerCallCount()).isEqualTo(1);

        UUID messageId = UUID.fromString(done.get("messageId").asText());
        String persistedProvider = jdbcTemplate.queryForObject(
            "SELECT model_provider FROM chat_messages WHERE id = ?",
            String.class,
            messageId
        );
        assertThat(persistedProvider).isEqualTo(RagConstants.PROVIDER_GEMINI);
    }

    @Test
    void streamCancelsUpstreamAndLogs499WithoutOrphanMessagesOnClientDisconnect() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            "company-probation-policy.pdf",
            1,
            "Thử việc",
            "Thời gian thử việc tối đa 60 ngày đối với vị trí chuyên môn."
        );

        String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
        ControlledDisconnectStream disconnectStream = groqModel.armControlledDisconnectStream();

        HttpResponse<InputStream> response = openSseRequest(
            salesEmployeeJwt,
            Map.of("question", "Thời gian thử việc tối đa bao nhiêu ngày?")
        );
        assertThat(response.statusCode()).isEqualTo(200);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            SseFrame citations = readNextSseFrame(reader);
            assertThat(citations).isNotNull();
            assertThat(citations.event()).isEqualTo("citations");

            assertThat(disconnectStream.subscribedLatch().await(5, TimeUnit.SECONDS)).isTrue();
            disconnectStream.sink().tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("Đoạn 1 ")))));

            SseFrame firstToken = readNextSseFrame(reader);
            assertThat(firstToken).isNotNull();
            assertThat(firstToken.event()).isEqualTo("token");
            // Client aborts stream mid-response (equivalent to browser AbortController.abort())
        }

        // Push subsequent chunks so Tomcat detects the broken client socket immediately
        for (int i = 0; i < 20 && disconnectStream.cancelledLatch().getCount() > 0; i++) {
            disconnectStream.sink().tryEmitNext(new ChatResponse(List.of(new Generation(
                new AssistantMessage("chunk-after-abort-" + i + " " + "x".repeat(512))
            ))));
            if (disconnectStream.cancelledLatch().await(100, TimeUnit.MILLISECONDS)) {
                break;
            }
        }

        assertThat(disconnectStream.cancelledLatch().await(5, TimeUnit.SECONDS)).isTrue();

        // Wait for virtual thread to finish writing the single 499 CHAT_QUERY audit log
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        Integer auditCount = 0;
        while (System.nanoTime() < deadlineNanos) {
            auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY'",
                Integer.class
            );
            if (auditCount != null && auditCount > 0) {
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(auditCount).isEqualTo(1);
        Integer sessionCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
        Integer messageCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class);
        assertThat(sessionCount).isZero();
        assertThat(messageCount).isZero();

        Map<String, Object> audit = jdbcTemplate.queryForMap(
            "SELECT status_code, abstained, provider FROM audit_logs WHERE action_type = 'CHAT_QUERY'"
        );
        assertThat(audit.get("status_code")).isEqualTo(499);
        assertThat(audit.get("abstained")).isEqualTo(true);
        assertThat(audit.get("provider")).isEqualTo(RagConstants.PROVIDER_GROQ);
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
            "email", email,
            "password", SEED_DEV_PASSWORD
        ));
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return objectMapper.readValue(resp.body(), AuthTokenResponse.class).accessToken();
    }

    private HttpResponse<InputStream> openSseRequest(String bearerToken, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
    }

    private HttpResponse<String> sendRawPost(String bearerToken, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private List<SseFrame> collectAllSseFrames(String bearerToken, Map<String, Object> body) throws Exception {
        HttpResponse<InputStream> response = openSseRequest(bearerToken, body);
        assertThat(response.statusCode()).isEqualTo(200);
        List<SseFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            SseFrame frame;
            while ((frame = readNextSseFrame(reader)) != null) {
                frames.add(frame);
            }
        }
        return frames;
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

    private void seedIndexedDocument(
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
    }

    private record SseFrame(String event, String data) {
    }

    record ControlledDisconnectStream(
        Sinks.Many<ChatResponse> sink,
        CountDownLatch subscribedLatch,
        CountDownLatch cancelledLatch
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DualProviderStreamingTestConfig {

        @Bean("testGroqStreamingModel")
        ControllableStreamingChatModel testGroqStreamingModel() {
            return new ControllableStreamingChatModel("groq");
        }

        @Bean("testGeminiStreamingModel")
        ControllableStreamingChatModel testGeminiStreamingModel() {
            return new ControllableStreamingChatModel("gemini");
        }

        @Bean(name = "testChatClientOverridePostProcessor")
        static BeanPostProcessor dualProviderStreamingPostProcessor(
            @Qualifier("testGroqStreamingModel") ObjectProvider<ControllableStreamingChatModel> groqProvider,
            @Qualifier("testGeminiStreamingModel") ObjectProvider<ControllableStreamingChatModel> geminiProvider
        ) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof ChatClient) {
                        if ("geminiChatClient".equals(beanName)) {
                            return ChatClient.create(geminiProvider.getObject());
                        }
                        if ("groqChatClient".equals(beanName)) {
                            return ChatClient.create(groqProvider.getObject());
                        }
                    }
                    return bean;
                }
            };
        }
    }

    static final class ControllableStreamingChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");

        private final String providerLabel;
        private final AtomicInteger rewriteCalls = new AtomicInteger();
        private final AtomicInteger answerCalls = new AtomicInteger();
        private final AtomicBoolean txActiveAny = new AtomicBoolean(false);
        private final AtomicBoolean failBeforeFirstToken = new AtomicBoolean(false);
        private final AtomicReference<CountDownLatch> firstTokenGateRef = new AtomicReference<>();
        private final AtomicReference<ControlledDisconnectStream> controlledDisconnectRef = new AtomicReference<>();

        ControllableStreamingChatModel(String providerLabel) {
            this.providerLabel = Objects.requireNonNull(providerLabel);
        }

        void reset() {
            resetCounts();
            txActiveAny.set(false);
            failBeforeFirstToken.set(false);
            CountDownLatch gate = firstTokenGateRef.getAndSet(null);
            if (gate != null) {
                gate.countDown();
            }
            controlledDisconnectRef.set(null);
        }

        void resetCounts() {
            rewriteCalls.set(0);
            answerCalls.set(0);
        }

        CountDownLatch armFirstTokenGate() {
            CountDownLatch gate = new CountDownLatch(1);
            firstTokenGateRef.set(gate);
            return gate;
        }

        void failNextStreamBeforeFirstToken() {
            failBeforeFirstToken.set(true);
        }

        ControlledDisconnectStream armControlledDisconnectStream() {
            ControlledDisconnectStream control = new ControlledDisconnectStream(
                Sinks.many().unicast().onBackpressureBuffer(),
                new CountDownLatch(1),
                new CountDownLatch(1)
            );
            controlledDisconnectRef.set(control);
            return control;
        }

        int rewriteCallCount() {
            return rewriteCalls.get();
        }

        int answerCallCount() {
            return answerCalls.get();
        }

        boolean wasTransactionActiveOnAnyCall() {
            return txActiveAny.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txActiveAny.set(true);
            }
            String systemText = extractFirstByType(prompt, MessageType.SYSTEM);
            String latestUserText = extractLastByType(prompt, MessageType.USER);

            if (systemText.contains("<conversation_history>")) {
                rewriteCalls.incrementAndGet();
                String rewritten = latestUserText.contains("5 năm")
                    ? "Nhân viên chính thức làm đủ 5 năm thâm niên được cộng thêm mấy ngày nghỉ phép năm?"
                    : latestUserText;
                ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(45, 14, 59))
                    .build();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rewritten))), meta);
            }

            answerCalls.incrementAndGet();
            String firstCitation = extractFirstCitation(systemText);
            ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(130, 42, 172))
                .build();
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage("Theo tài liệu nội bộ (" + providerLabel + ") " + firstCitation + "."))),
                meta
            );
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txActiveAny.set(true);
            }
            answerCalls.incrementAndGet();

            if (failBeforeFirstToken.compareAndSet(true, false)) {
                return Flux.error(new RuntimeException("Simulated " + providerLabel + " 503 outage before first token"));
            }

            ControlledDisconnectStream disconnectControl = controlledDisconnectRef.getAndSet(null);
            if (disconnectControl != null) {
                return disconnectControl.sink().asFlux()
                    .doOnSubscribe(s -> disconnectControl.subscribedLatch().countDown())
                    .doOnCancel(() -> disconnectControl.cancelledLatch().countDown());
            }

            String systemText = extractFirstByType(prompt, MessageType.SYSTEM);
            String firstCitation = extractFirstCitation(systemText);
            CountDownLatch gate = firstTokenGateRef.getAndSet(null);

            return Flux.defer(() -> {
                if (gate != null) {
                    try {
                        if (!gate.await(10, TimeUnit.SECONDS)) {
                            return Flux.error(new IllegalStateException("Timed out waiting for firstTokenGate"));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return Flux.error(ex);
                    }
                }
                ChatResponseMetadata usageMeta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(130, 42, 172))
                    .build();
                return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("Theo tài liệu nội bộ ")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("(" + providerLabel + ") ")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage(firstCitation + ".")))),
                    new ChatResponse(List.of(), usageMeta)
                );
            });
        }

        private static String extractFirstByType(Prompt prompt, MessageType type) {
            return prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == type)
                .map(Message::getText)
                .findFirst()
                .orElse("");
        }

        private static String extractLastByType(Prompt prompt, MessageType type) {
            return prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == type)
                .map(Message::getText)
                .reduce((first, second) -> second)
                .orElse("");
        }

        private static String extractFirstCitation(String systemText) {
            Matcher matcher = CITATION_ATTR_PATTERN.matcher(systemText);
            return matcher.find() ? matcher.group(1) : "[Nguồn: document]";
        }
    }
}
