package com.documind.integration;

import com.documind.controller.dto.chat.ChatMessageResponse;
import com.documind.controller.dto.chat.ChatSessionResponse;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.MessageRole;
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
import com.documind.support.Week2SseTestClient;
import com.documind.support.Week2SseTestClient.DedicatedSseConnection;
import com.documind.support.Week2SseTestClient.SseFrame;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import software.amazon.awssdk.services.s3.S3Client;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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

import static com.documind.support.Week2SseTestClient.ADMIN_EMAIL;
import static com.documind.support.Week2SseTestClient.HR_EMPLOYEE_EMAIL;
import static com.documind.support.Week2SseTestClient.HR_MANAGER_EMAIL;
import static com.documind.support.Week2SseTestClient.IT_EMPLOYEE_EMAIL;
import static com.documind.support.Week2SseTestClient.IT_MANAGER_EMAIL;
import static com.documind.support.Week2SseTestClient.SALES_EMPLOYEE_EMAIL;
import static com.documind.support.Week2SseTestClient.SALES_MANAGER_EMAIL;
import static com.documind.support.Week2SseTestClient.SEED_DEV_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 2 ({@code DA-18}) Afternoon Suite: End-to-end Testcontainers concurrency and resilience verification
 * for {@code POST /api/v1/chat/stream} covering 28 simultaneous open SSE streams over a 20-connection HikariCP pool,
 * same-session {@code FOR UPDATE} serialization, all 3 OOS paths over SSE, multi-stage {@code AbortController}
 * disconnects, mid-stream session deletion, mid-stream {@code 502} drops, and a concurrent mixed wave.
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
    Week2SseConcurrencyAndResilienceIntegrationTest.Milestone2StreamingTestConfig.class
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Week2SseConcurrencyAndResilienceIntegrationTest {

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID SALES_DEPT_ID = AbstractIntegrationTest.SALES_DEPT_ID;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

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
    @Qualifier("milestone2GroqStreamingModel")
    private DirectiveStreamingChatModel groqModel;

    @Autowired
    @Qualifier("milestone2GeminiStreamingModel")
    private DirectiveStreamingChatModel geminiModel;

    private Week2SseTestClient client;

    @BeforeAll
    void setUpSuite() throws Exception {
        client = new Week2SseTestClient(objectMapper);
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
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
        // Corpus documents and users are read-only across all 5 tests in this suite, so seed once per class
        seedMultiDepartmentCorpus();
    }

    @BeforeEach
    void resetPerTestChatState() {
        groqModel.reset();
        geminiModel.reset();
        AbstractIntegrationTest.flushRedis(stringRedisTemplate);
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM chat_messages");
        jdbcTemplate.update("DELETE FROM chat_sessions");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
    }

    @AfterEach
    void releaseControlledHandles() {
        groqModel.completeAllControlledHandles();
        geminiModel.completeAllControlledHandles();
    }

    @AfterAll
    void tearDownSuite() throws InterruptedException {
        groqModel.reset();
        geminiModel.reset();
        if (client != null) {
            client.close();
        }
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
    void twentyEightConcurrentMultiTenantSseStreamsExceedHikariPoolWithoutStarvationOrCrossTalk() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);

        Map<String, String> jwtByEmail = client.loginAllSevenActors(port);

        List<ConcurrentStreamSpec> baseSpecs = List.of(
            new ConcurrentStreamSpec(
                ADMIN_EMAIL,
                AbstractIntegrationTest.ADMIN_USER_ID,
                IT_DEPT_ID,
                "What traffic percentages are used in the canary rollout soak stage?",
                "it-confidential-canary.pdf",
                List.of()
            ),
            new ConcurrentStreamSpec(
                HR_MANAGER_EMAIL,
                AbstractIntegrationTest.HR_MANAGER_USER_ID,
                HR_DEPT_ID,
                "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo là bao nhiêu?",
                "hr-confidential-pvi.pdf",
                List.of("it-confidential-canary.pdf", "sales-confidential-hotel.pdf", "sales-dept-commission.docx")
            ),
            new ConcurrentStreamSpec(
                HR_EMPLOYEE_EMAIL,
                AbstractIntegrationTest.HR_EMPLOYEE_USER_ID,
                HR_DEPT_ID,
                "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?",
                "hr-dept-leave.pdf",
                List.of("hr-confidential-pvi.pdf", "it-confidential-canary.pdf", "sales-confidential-hotel.pdf")
            ),
            new ConcurrentStreamSpec(
                IT_MANAGER_EMAIL,
                AbstractIntegrationTest.IT_MANAGER_USER_ID,
                IT_DEPT_ID,
                "What traffic percentages are used in the canary rollout soak stage?",
                "it-confidential-canary.pdf",
                List.of("hr-confidential-pvi.pdf", "hr-dept-leave.pdf", "sales-confidential-hotel.pdf")
            ),
            new ConcurrentStreamSpec(
                IT_EMPLOYEE_EMAIL,
                AbstractIntegrationTest.IT_EMPLOYEE_USER_ID,
                IT_DEPT_ID,
                "How should engineers configure WireGuard VPN and mandatory screen lock?",
                "it-dept-vpn.md",
                List.of("it-confidential-canary.pdf", "hr-confidential-pvi.pdf", "sales-confidential-hotel.pdf")
            ),
            new ConcurrentStreamSpec(
                SALES_MANAGER_EMAIL,
                AbstractIntegrationTest.SALES_MANAGER_USER_ID,
                SALES_DEPT_ID,
                "Hạn mức khách sạn công tác phí lưu trú nội địa 1.500.000 của cấp quản lý Sales là bao nhiêu?",
                "sales-confidential-hotel.pdf",
                List.of("hr-confidential-pvi.pdf", "it-confidential-canary.pdf", "hr-dept-leave.pdf")
            ),
            new ConcurrentStreamSpec(
                SALES_EMPLOYEE_EMAIL,
                AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID,
                SALES_DEPT_ID,
                "Quy định làm việc từ xa hybrid work và khung giờ phối hợp 09:30 như thế nào?",
                "hr-public-hybrid.txt",
                List.of("sales-confidential-hotel.pdf", "hr-confidential-pvi.pdf", "hr-dept-leave.pdf", "it-confidential-canary.pdf")
            )
        );

        int wavesPerPersona = 4;
        int totalStreams = baseSpecs.size() * wavesPerPersona; // 28 > 20 HikariCP maximumPoolSize
        assertThat(totalStreams).isEqualTo(28).isGreaterThan(hikari.getMaximumPoolSize());

        List<ControlledStreamHandle> handles = new ArrayList<>(totalStreams);
        List<String> reqTags = new ArrayList<>(totalStreams);
        for (int i = 0; i < totalStreams; i++) {
            handles.add(groqModel.registerControlledStream("conc-" + i));
            reqTags.add("s-" + i);
        }

        CyclicBarrier startBarrier = new CyclicBarrier(totalStreams);
        CountDownLatch allCitationsReceived = new CountDownLatch(totalStreams);
        CountDownLatch allFirstTokensReceived = new CountDownLatch(totalStreams);

        List<Future<ConcurrentStreamResult>> futures = new ArrayList<>(totalStreams);
        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < totalStreams; i++) {
                    int idx = i;
                    ConcurrentStreamSpec spec = baseSpecs.get(idx % baseSpecs.size());
                    String jwt = jwtByEmail.get(spec.actorEmail());
                    String reqTag = reqTags.get(idx);
                    String question = spec.question() + " [REQ:" + reqTag + "] [CONTROLLED:conc-" + idx + "]";

                    futures.add(vtPool.submit(() -> {
                        startBarrier.await(10, TimeUnit.SECONDS);
                        HttpResponse<InputStream> response = client.openSseStream(port, Map.of("question", question), jwt);
                        try (BufferedReader reader = Week2SseTestClient.openUtf8Reader(response)) {
                            assertThat(response.statusCode()).isEqualTo(200);
                            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(""))
                                .contains("text/event-stream");
                            assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL).orElse(""))
                                .contains("no-cache");
                            assertThat(response.headers().firstValue("X-Accel-Buffering").orElse(""))
                                .isEqualTo("no");

                            List<SseFrame> frames = new ArrayList<>();
                            SseFrame citationsFrame = Week2SseTestClient.readNextFrame(reader);
                            assertThat(citationsFrame).isNotNull();
                            assertThat(citationsFrame.event()).isEqualTo("citations");
                            frames.add(citationsFrame);
                            allCitationsReceived.countDown();

                            SseFrame firstTokenFrame = Week2SseTestClient.readNextFrame(reader);
                            assertThat(firstTokenFrame).isNotNull();
                            assertThat(firstTokenFrame.event()).isEqualTo("token");
                            frames.add(firstTokenFrame);
                            allFirstTokensReceived.countDown();

                            SseFrame next;
                            while ((next = Week2SseTestClient.readNextFrame(reader)) != null) {
                                frames.add(next);
                            }
                            return new ConcurrentStreamResult(reqTag, spec, frames);
                        }
                    }));
                }

                assertThat(allCitationsReceived.await(15, TimeUnit.SECONDS))
                    .as("All 28 SSE clients must receive citations without HikariCP timeout")
                    .isTrue();
                for (int i = 0; i < totalStreams; i++) {
                    assertThat(handles.get(i).subscribedLatch().await(10, TimeUnit.SECONDS))
                        .as("Stream %d must subscribe upstream", i)
                        .isTrue();
                }

                for (int i = 0; i < totalStreams; i++) {
                    handles.get(i).sink().tryEmitNext(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("Đoạn mở đầu [REQ:" + reqTags.get(i) + "] "))
                    )));
                }
                assertThat(allFirstTokensReceived.await(15, TimeUnit.SECONDS))
                    .as("All 28 SSE clients must receive first token simultaneously")
                    .isTrue();

                // Peak proof: 28 SSE streams open > 20 HikariCP max pool -> 0 DB connections held & live DB endpoints stay 200 OK
                assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
                assertThat(groqModel.wasTransactionActiveOnAnyCall()).isFalse();
                assertThat(client.sendJson(port, "GET", "/api/v1/auth/me", null, jwtByEmail.get(HR_EMPLOYEE_EMAIL)).statusCode())
                    .isEqualTo(200);
                assertThat(client.sendJson(port, "GET", "/api/v1/documents", null, jwtByEmail.get(ADMIN_EMAIL)).statusCode())
                    .isEqualTo(200);

                ChatResponseMetadata usageMeta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(130, 42, 172))
                    .build();
                for (int i = 0; i < totalStreams; i++) {
                    ControlledStreamHandle handle = handles.get(i);
                    handle.sink().tryEmitNext(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("kết thúc " + handle.resolvedCitation() + "."))
                    )));
                    handle.sink().tryEmitNext(new ChatResponse(List.of(), usageMeta));
                    handle.sink().tryEmitComplete();
                }

                for (Future<ConcurrentStreamResult> future : futures) {
                    ConcurrentStreamResult result = future.get(20, TimeUnit.SECONDS);
                    List<SseFrame> frames = result.frames();
                    assertThat(frames.size()).isGreaterThanOrEqualTo(4);
                    assertThat(frames.getFirst().event()).isEqualTo("citations");
                    assertThat(frames.getLast().event()).isEqualTo("done");

                    String citationsPayload = frames.getFirst().data();
                    assertThat(citationsPayload).contains(result.spec().expectedFileName());
                    for (String forbiddenFile : result.spec().forbiddenFileNames()) {
                        assertThat(citationsPayload).doesNotContain(forbiddenFile);
                    }

                    StringBuilder answer = new StringBuilder();
                    for (int k = 1; k < frames.size() - 1; k++) {
                        assertThat(frames.get(k).event()).isEqualTo("token");
                        answer.append(objectMapper.readTree(frames.get(k).data()).path("delta").asText());
                    }
                    assertThat(answer.toString())
                        .contains("[REQ:" + result.reqTag() + "]")
                        .contains(result.spec().expectedFileName());
                    for (String forbiddenFile : result.spec().forbiddenFileNames()) {
                        assertThat(answer.toString()).doesNotContain(forbiddenFile);
                    }

                    JsonNode done = objectMapper.readTree(frames.getLast().data());
                    assertThat(done.path("abstained").asBoolean()).isFalse();
                    assertThat(done.path("sessionId").asText()).isNotBlank();
                    assertThat(done.path("messageId").asText()).isNotBlank();
                    assertThat(done.path("tokenUsage").path("totalTokens").asInt()).isEqualTo(172);
                }
            } finally {
                // Release blocked sinks and interrupt virtual threads so assertion failures never hang vtPool.close() for 120s
                groqModel.completeAllControlledHandles();
                vtPool.shutdownNow();
            }
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isEqualTo(totalStreams);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(totalStreams * 2);
        for (ConcurrentStreamSpec spec : baseSpecs) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_sessions WHERE user_id = ?",
                Integer.class,
                spec.userId()
            )).as("Session attribution for %s", spec.actorEmail()).isEqualTo(wavesPerPersona);
            assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_logs
                WHERE action_type = 'CHAT_QUERY'
                  AND user_id = ?
                  AND department_id = ?
                  AND status_code = 200
                  AND cache_hit = FALSE
                  AND cache_tier = 'NONE'
                  AND prompt_tokens = 130
                  AND completion_tokens = 42
                  AND latency_ms >= 0
                """,
                Integer.class,
                spec.userId(),
                spec.departmentId()
            )).as("Audit attribution for %s", spec.actorEmail()).isEqualTo(wavesPerPersona);
        }
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    @Test
    void concurrentSameSessionSseStreamsSerializeCleanlyUnderForUpdateWithAlternatingTimestamps() throws Exception {
        String hrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        HttpResponse<String> createSessionResp = client.sendJson(
            port,
            "POST",
            "/api/v1/chat/sessions",
            Map.of("title", "Concurrent SSE Session"),
            hrEmployeeJwt
        );
        assertThat(createSessionResp.statusCode()).isEqualTo(201);
        UUID sessionId = objectMapper.readValue(createSessionResp.body(), ChatSessionResponse.class).id();

        ControlledStreamHandle handleA = groqModel.registerControlledStream("same-sess-a");
        ControlledStreamHandle handleB = groqModel.registerControlledStream("same-sess-b");

        CyclicBarrier startBarrier = new CyclicBarrier(2);
        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                Future<List<SseFrame>> futureA = vtPool.submit(() -> {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    return client.collectAllSseFrames(
                        port,
                        Map.of(
                            "question", "Chính sách nghỉ phép năm cho nhân viên chính thức? [CONTROLLED:same-sess-a]",
                            "sessionId", sessionId.toString()
                        ),
                        hrEmployeeJwt
                    );
                });
                Future<List<SseFrame>> futureB = vtPool.submit(() -> {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    return client.collectAllSseFrames(
                        port,
                        Map.of(
                            "question", "Quy định làm việc từ xa hybrid work 09:30? [CONTROLLED:same-sess-b]",
                            "sessionId", sessionId.toString()
                        ),
                        hrEmployeeJwt
                    );
                });

                assertThat(handleA.subscribedLatch().await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(handleB.subscribedLatch().await(10, TimeUnit.SECONDS)).isTrue();

                ChatResponseMetadata usageMeta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(120, 35, 155))
                    .build();
                handleA.sink().tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("Trả lời luồng A.")))));
                handleA.sink().tryEmitNext(new ChatResponse(List.of(), usageMeta));
                handleB.sink().tryEmitNext(new ChatResponse(List.of(new Generation(new AssistantMessage("Trả lời luồng B.")))));
                handleB.sink().tryEmitNext(new ChatResponse(List.of(), usageMeta));

                // Release both streams simultaneously to race into ChatSessionService.recordCompletedTurn FOR UPDATE lock
                handleA.sink().tryEmitComplete();
                handleB.sink().tryEmitComplete();

                List<SseFrame> framesA = futureA.get(15, TimeUnit.SECONDS);
                List<SseFrame> framesB = futureB.get(15, TimeUnit.SECONDS);
                assertThat(framesA.getLast().event()).isEqualTo("done");
                assertThat(framesB.getLast().event()).isEqualTo("done");
                assertThat(objectMapper.readTree(framesA.getLast().data()).path("sessionId").asText()).isEqualTo(sessionId.toString());
                assertThat(objectMapper.readTree(framesB.getLast().data()).path("sessionId").asText()).isEqualTo(sessionId.toString());
            } finally {
                groqModel.completeAllControlledHandles();
                vtPool.shutdownNow();
            }
        }

        HttpResponse<String> historyResp = client.sendJson(
            port,
            "GET",
            "/api/v1/chat/sessions/" + sessionId + "/messages",
            null,
            hrEmployeeJwt
        );
        assertThat(historyResp.statusCode()).isEqualTo(200);
        List<ChatMessageResponse> messages = objectMapper.readValue(historyResp.body(), new TypeReference<>() {});
        assertThat(messages).hasSize(4);
        assertThat(messages)
            .extracting(ChatMessageResponse::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
        for (int i = 1; i < messages.size(); i++) {
            assertThat(messages.get(i).createdAt()).isAfter(messages.get(i - 1).createdAt());
        }

        boolean aFirst = messages.get(0).content().contains("[CONTROLLED:same-sess-a]");
        if (aFirst) {
            assertThat(messages.get(1).content()).isEqualTo("Trả lời luồng A.");
            assertThat(messages.get(2).content()).contains("[CONTROLLED:same-sess-b]");
            assertThat(messages.get(3).content()).isEqualTo("Trả lời luồng B.");
        } else {
            assertThat(messages.get(0).content()).contains("[CONTROLLED:same-sess-b]");
            assertThat(messages.get(1).content()).isEqualTo("Trả lời luồng B.");
            assertThat(messages.get(2).content()).contains("[CONTROLLED:same-sess-a]");
            assertThat(messages.get(3).content()).isEqualTo("Trả lời luồng A.");
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND session_id = ? AND status_code = 200",
            Integer.class,
            sessionId
        )).isEqualTo(2);
    }

    @Test
    void allThreeOutOfScopePathsOverSseEnforceZeroHallucinationAndCleanPersistence() throws Exception {
        String hrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesEmployeeJwt = client.login(port, SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        groqModel.resetCounts();
        List<SseFrame> oosExternalTurn1 = client.collectAllSseFrames(
            port,
            Map.of("question", "Dự báo thời tiết sao Hỏa ngày mai như thế nào?"),
            hrEmployeeJwt
        );
        client.assertDeterministicSseRefusal(oosExternalTurn1, null, null, true);
        assertThat(groqModel.rewriteCallCount()).isZero();
        assertThat(groqModel.answerCallCount()).isZero();

        groqModel.resetCounts();
        String confidentialQuestion = "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo là bao nhiêu?";
        for (String unauthorizedJwt : List.of(hrEmployeeJwt, salesEmployeeJwt)) {
            List<SseFrame> oosRbacFrames = client.collectAllSseFrames(
                port,
                Map.of("question", confidentialQuestion),
                unauthorizedJwt
            );
            client.assertDeterministicSseRefusal(
                oosRbacFrames,
                "HR_CONFIDENTIAL_VIP_SECRET",
                "hr-confidential-pvi.pdf",
                true
            );
        }
        assertThat(groqModel.rewriteCallCount()).isZero();
        assertThat(groqModel.answerCallCount()).isZero();

        groqModel.resetCounts();
        List<SseFrame> groundedTurn1 = client.collectAllSseFrames(
            port,
            Map.of("question", "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?"),
            hrEmployeeJwt
        );
        UUID sessionId = UUID.fromString(objectMapper.readTree(groundedTurn1.getLast().data()).path("sessionId").asText());
        assertThat(groqModel.answerCallCount()).isEqualTo(1);

        List<SseFrame> oosExternalTurn2 = client.collectAllSseFrames(
            port,
            Map.of("question", "Công ty có tài trợ mua du thuyền vũ trụ không?", "sessionId", sessionId.toString()),
            hrEmployeeJwt
        );
        client.assertDeterministicSseRefusal(oosExternalTurn2, null, null, false);
        JsonNode turn2Done = objectMapper.readTree(oosExternalTurn2.getLast().data());
        assertThat(turn2Done.path("tokenUsage").path("promptTokens").asInt()).isEqualTo(45);
        assertThat(turn2Done.path("tokenUsage").path("completionTokens").asInt()).isEqualTo(14);
        assertThat(groqModel.answerCallCount()).isEqualTo(1);

        List<SseFrame> oosRbacTurn3 = client.collectAllSseFrames(
            port,
            Map.of("question", "Còn chế độ lương VIP mật thì sao?", "sessionId", sessionId.toString()),
            hrEmployeeJwt
        );
        client.assertDeterministicSseRefusal(
            oosRbacTurn3,
            "HR_CONFIDENTIAL_VIP_SECRET",
            "hr-confidential-pvi.pdf",
            false
        );
        JsonNode turn3Citations = objectMapper.readTree(oosRbacTurn3.getFirst().data());
        assertThat(turn3Citations.path("rewrittenQuery").asText()).contains("PVI Care");
        JsonNode turn3Done = objectMapper.readTree(oosRbacTurn3.getLast().data());
        assertThat(turn3Done.path("tokenUsage").path("promptTokens").asInt()).isEqualTo(45);
        assertThat(turn3Done.path("tokenUsage").path("completionTokens").asInt()).isEqualTo(14);
        assertThat(groqModel.answerCallCount()).isEqualTo(1);

        // Clear cache before synthetic [SOFT_REFUSAL] prompt so the toy 14-axis test embedder does not collide with Turn 1 at L2
        AbstractIntegrationTest.flushRedis(stringRedisTemplate);
        jdbcTemplate.update("DELETE FROM semantic_cache");

        List<SseFrame> softRefusalFrames = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách nghỉ phép năm có áp dụng cho thực tập sinh không? [SOFT_REFUSAL]"),
            hrEmployeeJwt
        );
        assertThat(softRefusalFrames.getFirst().event()).isEqualTo("citations");
        assertThat(objectMapper.readTree(softRefusalFrames.getFirst().data()).path("citations")).isNotEmpty();
        assertThat(softRefusalFrames.getLast().event()).isEqualTo("done");
        JsonNode softDone = objectMapper.readTree(softRefusalFrames.getLast().data());
        assertThat(softDone.path("abstained").asBoolean()).isTrue();
        UUID softMessageId = UUID.fromString(softDone.path("messageId").asText());

        Map<String, Object> softMsgRow = jdbcTemplate.queryForMap(
            "SELECT content, abstained, citations::text AS citations_json FROM chat_messages WHERE id = ?",
            softMessageId
        );
        assertThat(softMsgRow.get("content")).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(softMsgRow.get("abstained")).isEqualTo(true);
        assertThat(objectMapper.readTree((String) softMsgRow.get("citations_json"))).isEmpty();
    }

    @Test
    void multiStageAbortControllerDisconnectsMidStreamSessionDeleteAndProviderDropStayClean() throws Exception {
        String hrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
        String salesEmployeeJwt = client.login(port, SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

        // Stage A: Pre-First-Token AbortController disconnect (after citations, before token #1)
        ControlledStreamHandle preTokenHandle = groqModel.registerControlledStream("pre-token-abort");
        try (DedicatedSseConnection conn = client.openDedicatedSseStream(
            port,
            Map.of("question", "Quy định làm việc từ xa hybrid work 09:30? [CONTROLLED:pre-token-abort]"),
            salesEmployeeJwt
        ); BufferedReader reader = Week2SseTestClient.openUtf8Reader(conn.response())) {
            assertThat(conn.response().statusCode()).isEqualTo(200);
            SseFrame citations = Week2SseTestClient.readNextFrame(reader);
            assertThat(citations).isNotNull();
            assertThat(citations.event()).isEqualTo("citations");
            assertThat(preTokenHandle.subscribedLatch().await(5, TimeUnit.SECONDS)).isTrue();
        }

        triggerSocketDisconnectDetection(preTokenHandle);
        assertThat(preTokenHandle.cancelledLatch().await(5, TimeUnit.SECONDS))
            .as("Upstream Flux must cancel promptly after pre-first-token client disconnect")
            .isTrue();
        awaitAuditCount(499, null, 1, Duration.ofSeconds(5));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isZero();
        Map<String, Object> preTokenAbortAudit = jdbcTemplate.queryForMap(
            "SELECT user_id, department_id, session_id, abstained, status_code FROM audit_logs WHERE status_code = 499"
        );
        assertThat(preTokenAbortAudit.get("user_id")).isEqualTo(AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID);
        assertThat(preTokenAbortAudit.get("department_id")).isEqualTo(SALES_DEPT_ID);
        assertThat(preTokenAbortAudit.get("session_id")).isNull();
        assertThat(preTokenAbortAudit.get("abstained")).isEqualTo(true);

        // Stage B: Mid-Stream Abort on Turn 2 of active multi-turn session + clean Turn 3 recovery
        List<SseFrame> turn1Frames = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách nghỉ phép năm cho nhân viên chính thức là bao nhiêu ngày?"),
            hrEmployeeJwt
        );
        UUID activeSessionId = UUID.fromString(objectMapper.readTree(turn1Frames.getLast().data()).path("sessionId").asText());
        assertThat(countMessagesInSession(activeSessionId)).isEqualTo(2);

        ControlledStreamHandle turn2AbortHandle = groqModel.registerControlledStream("turn2-abort");
        try (DedicatedSseConnection conn = client.openDedicatedSseStream(
            port,
            Map.of(
                "question", "Nếu làm đủ 5 năm thâm niên thì sao? [CONTROLLED:turn2-abort]",
                "sessionId", activeSessionId.toString()
            ),
            hrEmployeeJwt
        ); BufferedReader reader = Week2SseTestClient.openUtf8Reader(conn.response())) {
            assertThat(conn.response().statusCode()).isEqualTo(200);
            SseFrame citations = Week2SseTestClient.readNextFrame(reader);
            assertThat(citations).isNotNull();
            assertThat(citations.event()).isEqualTo("citations");
            assertThat(turn2AbortHandle.subscribedLatch().await(5, TimeUnit.SECONDS)).isTrue();

            turn2AbortHandle.sink().tryEmitNext(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Đoạn đầu Turn 2 trước khi bấm Stop "))
            )));
            SseFrame firstToken = Week2SseTestClient.readNextFrame(reader);
            assertThat(firstToken).isNotNull();
            assertThat(firstToken.event()).isEqualTo("token");
        }

        triggerSocketDisconnectDetection(turn2AbortHandle);
        assertThat(turn2AbortHandle.cancelledLatch().await(5, TimeUnit.SECONDS)).isTrue();
        awaitAuditCount(499, activeSessionId, 1, Duration.ofSeconds(5));

        assertThat(countMessagesInSession(activeSessionId)).isEqualTo(2);
        Map<String, Object> turn2AbortAudit = jdbcTemplate.queryForMap(
            "SELECT status_code, abstained, prompt_tokens, completion_tokens FROM audit_logs "
                + "WHERE status_code = 499 AND session_id = ?",
            activeSessionId
        );
        assertThat(turn2AbortAudit.get("abstained")).isEqualTo(true);
        assertThat((Integer) turn2AbortAudit.get("prompt_tokens")).isGreaterThanOrEqualTo(45);
        assertThat((Integer) turn2AbortAudit.get("completion_tokens")).isGreaterThanOrEqualTo(14);

        List<SseFrame> turn3Frames = client.collectAllSseFrames(
            port,
            Map.of("question", "Nếu làm đủ 5 năm thâm niên thì được cộng thêm mấy ngày?", "sessionId", activeSessionId.toString()),
            hrEmployeeJwt
        );
        assertThat(turn3Frames.getLast().event()).isEqualTo("done");
        JsonNode turn3Done = objectMapper.readTree(turn3Frames.getLast().data());
        assertThat(turn3Done.path("sessionId").asText()).isEqualTo(activeSessionId.toString());
        assertThat(turn3Done.path("abstained").asBoolean()).isFalse();
        assertThat(countMessagesInSession(activeSessionId)).isEqualTo(4);

        // Stage C: Mid-Stream DELETE /api/v1/chat/sessions/{id} -> RESOURCE_NOT_FOUND + 404 FinOps Audit
        List<SseFrame> ephemeralTurn1 = client.collectAllSseFrames(
            port,
            Map.of("question", "Chính sách nghỉ phép năm cho nhân viên chính thức là bao nhiêu ngày?"),
            hrEmployeeJwt
        );
        UUID ephemeralSessionId = UUID.fromString(objectMapper.readTree(ephemeralTurn1.getLast().data()).path("sessionId").asText());

        ControlledStreamHandle midDeleteHandle = groqModel.registerControlledStream("mid-delete");
        HttpResponse<InputStream> midDeleteResponse = client.openSseStream(
            port,
            Map.of(
                "question", "Nếu làm đủ 5 năm thâm niên thì sao? [CONTROLLED:mid-delete]",
                "sessionId", ephemeralSessionId.toString()
            ),
            hrEmployeeJwt
        );

        List<SseFrame> midDeleteFrames = new ArrayList<>();
        try (BufferedReader reader = Week2SseTestClient.openUtf8Reader(midDeleteResponse)) {
            assertThat(midDeleteResponse.statusCode()).isEqualTo(200);
            SseFrame citations = Week2SseTestClient.readNextFrame(reader);
            assertThat(citations).isNotNull();
            assertThat(citations.event()).isEqualTo("citations");
            midDeleteFrames.add(citations);

            assertThat(midDeleteHandle.subscribedLatch().await(5, TimeUnit.SECONDS)).isTrue();
            midDeleteHandle.sink().tryEmitNext(new ChatResponse(List.of(
                new Generation(new AssistantMessage("Đang trả lời trước khi phiên bị xóa."))
            )));
            SseFrame firstToken = Week2SseTestClient.readNextFrame(reader);
            assertThat(firstToken).isNotNull();
            assertThat(firstToken.event()).isEqualTo("token");
            midDeleteFrames.add(firstToken);

            HttpResponse<String> deleteResp = client.sendJson(
                port, "DELETE", "/api/v1/chat/sessions/" + ephemeralSessionId, null, hrEmployeeJwt
            );
            assertThat(deleteResp.statusCode()).isEqualTo(204);

            midDeleteHandle.sink().tryEmitNext(new ChatResponse(
                List.of(),
                ChatResponseMetadata.builder().usage(new DefaultUsage(130, 42, 172)).build()
            ));
            midDeleteHandle.sink().tryEmitComplete();

            SseFrame next;
            while ((next = Week2SseTestClient.readNextFrame(reader)) != null) {
                midDeleteFrames.add(next);
            }
        }

        assertThat(midDeleteFrames.getLast().event()).isEqualTo("error");
        JsonNode deleteErr = objectMapper.readTree(midDeleteFrames.getLast().data());
        assertThat(deleteErr.path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(deleteErr.path("retryable").asBoolean()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_sessions WHERE id = ?", Integer.class, ephemeralSessionId
        )).isZero();
        assertThat(countMessagesInSession(ephemeralSessionId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE status_code = 404 AND session_id IS NULL AND prompt_tokens = 175 AND completion_tokens = 56",
            Integer.class
        )).isEqualTo(1);

        // Stage D: Mid-Stream Provider Error (502 LLM_STREAM_INTERRUPTED, NO Gemini failover after token #1)
        geminiModel.resetCounts();
        int sessionsBefore502 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class);
        int messagesBefore502 = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class);

        List<SseFrame> midErrorFrames = client.collectAllSseFrames(
            port,
            Map.of("question", "Quy định làm việc từ xa hybrid work 09:30? [FAIL_MID_502]"),
            salesEmployeeJwt
        );
        assertThat(midErrorFrames.getFirst().event()).isEqualTo("citations");
        assertThat(midErrorFrames.stream().anyMatch(f -> "token".equals(f.event()))).isTrue();
        assertThat(midErrorFrames.getLast().event()).isEqualTo("error");
        JsonNode midErrJson = objectMapper.readTree(midErrorFrames.getLast().data());
        assertThat(midErrJson.path("errorCode").asText()).isEqualTo("LLM_STREAM_INTERRUPTED");
        assertThat(midErrJson.path("provider").asText()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(midErrJson.path("retryable").asBoolean()).isTrue();
        assertThat(geminiModel.answerCallCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class))
            .isEqualTo(sessionsBefore502);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class))
            .isEqualTo(messagesBefore502);
        awaitAuditCount(502, null, 1, Duration.ofSeconds(5));
        Map<String, Object> drop502Audit = jdbcTemplate.queryForMap(
            "SELECT user_id, department_id, abstained, provider, prompt_tokens, completion_tokens "
                + "FROM audit_logs WHERE status_code = 502"
        );
        assertThat(drop502Audit.get("user_id")).isEqualTo(AbstractIntegrationTest.SALES_EMPLOYEE_USER_ID);
        assertThat(drop502Audit.get("department_id")).isEqualTo(SALES_DEPT_ID);
        assertThat(drop502Audit.get("abstained")).isEqualTo(true);
        assertThat(drop502Audit.get("provider")).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat((Integer) drop502Audit.get("prompt_tokens")).isGreaterThan(0);
        assertThat((Integer) drop502Audit.get("completion_tokens")).isGreaterThan(0);
    }

    @Test
    void concurrentMixedWaveHandlesGroundedOosAbortAndMidStream502WithoutDeadlocksOrAuditDrift() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        Map<String, String> jwtByEmail = client.loginAllSevenActors(port);
        groqModel.resetCounts();

        List<ControlledStreamHandle> abortHandles = List.of(
            groqModel.registerControlledStream("mix-abort-0"),
            groqModel.registerControlledStream("mix-abort-1")
        );

        int waveSize = 16; // 6 Grounded + 3 OOS_EXTERNAL + 3 OOS_RBAC + 2 Abort 499 + 2 Drop 502
        CyclicBarrier waveBarrier = new CyclicBarrier(waveSize);
        List<Callable<Void>> tasks = new ArrayList<>(waveSize);

        List<Map.Entry<String, String>> groundedPrompts = List.of(
            Map.entry(HR_MANAGER_EMAIL, "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo? [REQ:mix-g0]"),
            Map.entry(HR_EMPLOYEE_EMAIL, "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm? [REQ:mix-g1]"),
            Map.entry(IT_MANAGER_EMAIL, "What traffic percentages are used in the canary rollout soak stage? [REQ:mix-g2]"),
            Map.entry(IT_EMPLOYEE_EMAIL, "How should engineers configure WireGuard VPN and screen lock? [REQ:mix-g3]"),
            Map.entry(SALES_MANAGER_EMAIL, "Hạn mức khách sạn công tác phí lưu trú nội địa 1.500.000 của cấp quản lý Sales? [REQ:mix-g4]"),
            Map.entry(SALES_EMPLOYEE_EMAIL, "Quy định làm việc từ xa hybrid work khung giờ phối hợp 09:30? [REQ:mix-g5]")
        );
        for (Map.Entry<String, String> entry : groundedPrompts) {
            tasks.add(() -> {
                waveBarrier.await(10, TimeUnit.SECONDS);
                List<SseFrame> frames = client.collectAllSseFrames(
                    port,
                    Map.of("question", entry.getValue()),
                    jwtByEmail.get(entry.getKey())
                );
                assertThat(frames.getLast().event()).isEqualTo("done");
                assertThat(objectMapper.readTree(frames.getLast().data()).path("abstained").asBoolean()).isFalse();
                return null;
            });
        }

        for (int i = 0; i < 3; i++) {
            String actor = List.of(HR_EMPLOYEE_EMAIL, IT_EMPLOYEE_EMAIL, SALES_EMPLOYEE_EMAIL).get(i);
            String question = "Giá cổ phiếu công ty ngoài thị trường chứng khoán phiên " + i + " là bao nhiêu?";
            tasks.add(() -> {
                waveBarrier.await(10, TimeUnit.SECONDS);
                List<SseFrame> frames = client.collectAllSseFrames(port, Map.of("question", question), jwtByEmail.get(actor));
                client.assertDeterministicSseRefusal(frames, null, null, true);
                return null;
            });
        }

        List<OosRbacCase> oosRbacCases = List.of(
            new OosRbacCase(
                HR_EMPLOYEE_EMAIL,
                "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo?",
                "HR_CONFIDENTIAL_VIP_SECRET",
                "hr-confidential-pvi.pdf"
            ),
            new OosRbacCase(
                SALES_EMPLOYEE_EMAIL,
                "Hạn mức khách sạn công tác phí lưu trú nội địa 1.500.000 của cấp quản lý Sales?",
                "SALES_CONFIDENTIAL_HOTEL_SECRET",
                "sales-confidential-hotel.pdf"
            ),
            new OosRbacCase(
                IT_EMPLOYEE_EMAIL,
                "Ngày chi trả hoa hồng doanh số của phòng Sales là ngày nào?",
                "SALES_DEPT_SECRET",
                "sales-dept-commission.docx"
            )
        );
        for (OosRbacCase rbacCase : oosRbacCases) {
            tasks.add(() -> {
                waveBarrier.await(10, TimeUnit.SECONDS);
                List<SseFrame> frames = client.collectAllSseFrames(
                    port,
                    Map.of("question", rbacCase.question()),
                    jwtByEmail.get(rbacCase.actorEmail())
                );
                client.assertDeterministicSseRefusal(
                    frames,
                    rbacCase.forbiddenSecret(),
                    rbacCase.forbiddenFileName(),
                    true
                );
                return null;
            });
        }

        for (int i = 0; i < 2; i++) {
            int idx = i;
            ControlledStreamHandle handle = abortHandles.get(idx);
            String actor = idx == 0 ? HR_EMPLOYEE_EMAIL : SALES_EMPLOYEE_EMAIL;
            tasks.add(() -> {
                waveBarrier.await(10, TimeUnit.SECONDS);
                try (DedicatedSseConnection conn = client.openDedicatedSseStream(
                    port,
                    Map.of("question", "Quy định làm việc từ xa hybrid work 09:30? [CONTROLLED:mix-abort-" + idx + "]"),
                    jwtByEmail.get(actor)
                ); BufferedReader reader = Week2SseTestClient.openUtf8Reader(conn.response())) {
                    assertThat(conn.response().statusCode()).isEqualTo(200);
                    SseFrame citations = Week2SseTestClient.readNextFrame(reader);
                    assertThat(citations).isNotNull();
                    assertThat(citations.event()).isEqualTo("citations");
                    assertThat(handle.subscribedLatch().await(10, TimeUnit.SECONDS)).isTrue();
                    handle.sink().tryEmitNext(new ChatResponse(List.of(
                        new Generation(new AssistantMessage("Đoạn trước khi hủy mix-" + idx + " "))
                    )));
                    SseFrame firstToken = Week2SseTestClient.readNextFrame(reader);
                    assertThat(firstToken).isNotNull();
                    assertThat(firstToken.event()).isEqualTo("token");
                }
                triggerSocketDisconnectDetection(handle);
                assertThat(handle.cancelledLatch().await(5, TimeUnit.SECONDS)).isTrue();
                return null;
            });
        }

        for (int i = 0; i < 2; i++) {
            String actor = i == 0 ? IT_EMPLOYEE_EMAIL : SALES_EMPLOYEE_EMAIL;
            tasks.add(() -> {
                waveBarrier.await(10, TimeUnit.SECONDS);
                List<SseFrame> frames = client.collectAllSseFrames(
                    port,
                    Map.of("question", "Quy định làm việc từ xa hybrid work 09:30? [FAIL_MID_502]"),
                    jwtByEmail.get(actor)
                );
                assertThat(frames.getLast().event()).isEqualTo("error");
                assertThat(objectMapper.readTree(frames.getLast().data()).path("errorCode").asText())
                    .isEqualTo("LLM_STREAM_INTERRUPTED");
                return null;
            });
        }

        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                List<Future<Void>> futures = new ArrayList<>(waveSize);
                for (Callable<Void> task : tasks) {
                    futures.add(vtPool.submit(task));
                }
                for (Future<Void> future : futures) {
                    future.get(25, TimeUnit.SECONDS);
                }
            } finally {
                groqModel.completeAllControlledHandles();
                vtPool.shutdownNow();
            }
        }

        // Exact LLM call accounting under mixed concurrency: 6 grounded + 2 abort + 2 drop-502 = 10; 0 for all 6 OOS streams
        assertThat(groqModel.answerCallCount()).isEqualTo(10);
        assertThat(groqModel.rewriteCallCount()).isZero();

        awaitAuditCount(200, null, 12, Duration.ofSeconds(10));
        awaitAuditCount(499, null, 2, Duration.ofSeconds(10));
        awaitAuditCount(502, null, 2, Duration.ofSeconds(10));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isEqualTo(24);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' "
                + "AND NOT ((cache_hit = FALSE AND cache_tier = 'NONE') OR (cache_hit = TRUE AND cache_tier IN ('L1', 'L2')))",
            Integer.class
        )).isZero();
        assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).isZero();
    }

    private void triggerSocketDisconnectDetection(ControlledStreamHandle handle) throws InterruptedException {
        // Tomcat async SseEmitter detects a closed loopback socket only when flushing outbound chunks triggers IOException
        for (int i = 0; i < 24 && handle.cancelledLatch().getCount() > 0; i++) {
            handle.sink().tryEmitNext(new ChatResponse(List.of(new Generation(
                new AssistantMessage("probe-after-abort-" + i + " " + "x".repeat(512))
            ))));
            if (handle.cancelledLatch().await(80, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    private void awaitAuditCount(int statusCode, UUID sessionId, int expectedCount, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        Integer last = 0;
        while (System.nanoTime() < deadlineNanos) {
            last = sessionId == null
                ? jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND status_code = ?",
                    Integer.class,
                    statusCode
                )
                : jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND status_code = ? AND session_id = ?",
                    Integer.class,
                    statusCode,
                    sessionId
                );
            if (last != null && last == expectedCount) {
                return;
            }
            Thread.sleep(40L);
        }
        assertThat(last)
            .as("Expected audit_logs count for status_code=%d, session_id=%s", statusCode, sessionId)
            .isEqualTo(expectedCount);
    }

    private int countMessagesInSession(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Integer.class,
            sessionId
        );
        return count != null ? count : 0;
    }

    private void seedMultiDepartmentCorpus() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            "hr-public-hybrid.txt",
            "txt",
            "text/plain",
            null,
            "Hybrid Work",
            "Quy định làm việc từ xa hybrid work và khung giờ phối hợp 09:30 toàn công ty tối đa 2 ngày/tuần."
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-dept-leave.pdf",
            "pdf",
            "application/pdf",
            2,
            "Nghỉ phép năm",
            "Nhân viên chính thức được hưởng 14 ngày nghỉ phép năm. Làm đủ 5 năm thâm niên được cộng thêm 1 ngày phép."
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.CONFIDENTIAL,
            "hr-confidential-pvi.pdf",
            "pdf",
            "application/pdf",
            2,
            "Phúc lợi VIP",
            "Gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo có hạn mức nội trú 500 triệu. HR_CONFIDENTIAL_VIP_SECRET"
        );
        seedIndexedDocument(
            SALES_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "sales-dept-commission.docx",
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            null,
            "Hoa hồng Sales",
            "Kỳ chi trả hoa hồng doanh số của phòng Sales được thực hiện vào ngày 15 đầu quý. SALES_DEPT_SECRET"
        );
        seedIndexedDocument(
            SALES_DEPT_ID,
            AccessLevel.CONFIDENTIAL,
            "sales-confidential-hotel.pdf",
            "pdf",
            "application/pdf",
            2,
            "Công tác phí Sales VIP",
            "Định mức khách sạn và công tác phí lưu trú nội địa cấp quản lý Sales áp dụng trần 1.500.000 VNĐ/đêm. SALES_CONFIDENTIAL_HOTEL_SECRET"
        );
        seedIndexedDocument(
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "it-dept-vpn.md",
            "md",
            "text/markdown",
            null,
            "WireGuard VPN",
            "Configure WireGuard VPN tunnel and enforce mandatory screen lock timeout on all engineering devices."
        );
        seedIndexedDocument(
            IT_DEPT_ID,
            AccessLevel.CONFIDENTIAL,
            "it-confidential-canary.pdf",
            "pdf",
            "application/pdf",
            2,
            "Canary Rollout",
            "Canary rollout shifts traffic percentages 10% -> 50% -> 100% after soak checks. IT_CONFIDENTIAL_SECRET"
        );
    }

    private void seedIndexedDocument(
        UUID departmentId,
        AccessLevel accessLevel,
        String fileName,
        String extension,
        String mimeType,
        Integer pageNumber,
        String sectionHeading,
        String content
    ) throws Exception {
        DepartmentEntity department = departmentRepository.findById(departmentId).orElseThrow();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String storageKey = "departments/" + departmentId + "/" + UUID.randomUUID() + "-" + fileName;
        minioStorageService.uploadStream(storageKey, new ByteArrayInputStream(bytes), bytes.length, mimeType);

        String hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest((storageKey + ":" + content).getBytes(StandardCharsets.UTF_8))
        );
        DocumentEntity doc = new DocumentEntity();
        doc.setDepartment(department);
        doc.setOriginalName(fileName);
        doc.setStorageKey(storageKey);
        doc.setFileExtension(extension);
        doc.setMimeType(mimeType);
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
        if (pageNumber != null) {
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, pageNumber);
        }
        if (sectionHeading != null) {
            metadata.put(ChunkMetadataKeys.SECTION_HEADING, sectionHeading);
        }

        String chunkId = ChunkIdGenerator.generate(savedDoc.getId(), 1, 0).toString();
        vectorStore.add(List.of(new Document(chunkId, content, metadata)));
    }

    private record ConcurrentStreamSpec(
        String actorEmail,
        UUID userId,
        UUID departmentId,
        String question,
        String expectedFileName,
        List<String> forbiddenFileNames
    ) {
    }

    private record ConcurrentStreamResult(
        String reqTag,
        ConcurrentStreamSpec spec,
        List<SseFrame> frames
    ) {
    }

    private record OosRbacCase(
        String actorEmail,
        String question,
        String forbiddenSecret,
        String forbiddenFileName
    ) {
    }

    static final class ControlledStreamHandle {
        private final Sinks.Many<ChatResponse> sink = Sinks.many().unicast().onBackpressureBuffer();
        private final CountDownLatch subscribedLatch = new CountDownLatch(1);
        private final CountDownLatch cancelledLatch = new CountDownLatch(1);
        private volatile String resolvedCitation = "[Nguồn: document]";

        Sinks.Many<ChatResponse> sink() {
            return sink;
        }

        CountDownLatch subscribedLatch() {
            return subscribedLatch;
        }

        CountDownLatch cancelledLatch() {
            return cancelledLatch;
        }

        String resolvedCitation() {
            return resolvedCitation;
        }

        void setResolvedCitation(String citation) {
            this.resolvedCitation = citation;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Milestone2StreamingTestConfig {

        @Bean("milestone2GroqStreamingModel")
        DirectiveStreamingChatModel milestone2GroqStreamingModel() {
            return new DirectiveStreamingChatModel("groq");
        }

        @Bean("milestone2GeminiStreamingModel")
        DirectiveStreamingChatModel milestone2GeminiStreamingModel() {
            return new DirectiveStreamingChatModel("gemini");
        }

        @Bean(name = "testChatClientOverridePostProcessor")
        static BeanPostProcessor milestone2StreamingPostProcessor(
            @Qualifier("milestone2GroqStreamingModel") ObjectProvider<DirectiveStreamingChatModel> groqProvider,
            @Qualifier("milestone2GeminiStreamingModel") ObjectProvider<DirectiveStreamingChatModel> geminiProvider
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

    static final class DirectiveStreamingChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private static final Pattern CONTROLLED_KEY_PATTERN = Pattern.compile("\\[CONTROLLED:([^\\]]+)\\]");
        private static final Pattern REQ_TAG_PATTERN = Pattern.compile("\\[REQ:([^\\]]+)\\]");

        private final String providerLabel;
        private final AtomicInteger rewriteCalls = new AtomicInteger();
        private final AtomicInteger answerCalls = new AtomicInteger();
        private final AtomicBoolean txActiveAny = new AtomicBoolean(false);
        private final ConcurrentHashMap<String, ControlledStreamHandle> controlledHandles = new ConcurrentHashMap<>();

        DirectiveStreamingChatModel(String providerLabel) {
            this.providerLabel = Objects.requireNonNull(providerLabel);
        }

        void reset() {
            resetCounts();
            txActiveAny.set(false);
            completeAllControlledHandles();
        }

        void completeAllControlledHandles() {
            for (ControlledStreamHandle handle : controlledHandles.values()) {
                handle.sink().tryEmitComplete();
            }
            controlledHandles.clear();
        }

        void resetCounts() {
            rewriteCalls.set(0);
            answerCalls.set(0);
        }

        ControlledStreamHandle registerControlledStream(String handleKey) {
            ControlledStreamHandle handle = new ControlledStreamHandle();
            controlledHandles.put(handleKey, handle);
            return handle;
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
                String rewritten;
                if (latestUserText.contains("lương VIP mật")) {
                    rewritten = "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care dành cho cấp lãnh đạo là bao nhiêu?";
                } else if (latestUserText.contains("5 năm")) {
                    rewritten = "Nhân viên chính thức làm đủ 5 năm thâm niên được cộng thêm mấy ngày nghỉ phép năm?";
                } else {
                    rewritten = latestUserText;
                }
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

            String systemText = extractFirstByType(prompt, MessageType.SYSTEM);
            String latestUserText = extractLastByType(prompt, MessageType.USER);
            String firstCitation = extractFirstCitation(systemText);

            Matcher controlledMatcher = CONTROLLED_KEY_PATTERN.matcher(latestUserText);
            if (controlledMatcher.find()) {
                String handleKey = controlledMatcher.group(1);
                ControlledStreamHandle handle = controlledHandles.get(handleKey);
                if (handle == null) {
                    return Flux.error(new IllegalStateException("No ControlledStreamHandle registered for key: " + handleKey));
                }
                handle.setResolvedCitation(firstCitation);
                return handle.sink().asFlux()
                    .doOnSubscribe(s -> handle.subscribedLatch().countDown())
                    .doOnCancel(() -> handle.cancelledLatch().countDown());
            }

            if (latestUserText.contains("[SOFT_REFUSAL]")) {
                ChatResponseMetadata usageMeta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(110, 18, 128))
                    .build();
                return Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("Không tìm thấy thông tin ")))),
                    new ChatResponse(List.of(new Generation(new AssistantMessage("trong tài liệu nội bộ.")))),
                    new ChatResponse(List.of(), usageMeta)
                );
            }

            if (latestUserText.contains("[FAIL_MID_502]")) {
                return Flux.concat(
                    Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Đoạn một trước khi đứt kết nối "))))),
                    Flux.error(new RuntimeException("Simulated mid-stream " + providerLabel + " TCP reset"))
                );
            }

            Matcher reqMatcher = REQ_TAG_PATTERN.matcher(latestUserText);
            String reqPrefix = reqMatcher.find() ? "[REQ:" + reqMatcher.group(1) + "] " : "";
            ChatResponseMetadata usageMeta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(130, 42, 172))
                .build();
            return Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage(reqPrefix + "Theo tài liệu nội bộ ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("(" + providerLabel + ") ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(firstCitation + ".")))),
                new ChatResponse(List.of(), usageMeta)
            );
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
