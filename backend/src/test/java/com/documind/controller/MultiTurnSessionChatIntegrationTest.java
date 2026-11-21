package com.documind.controller;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.auth.AuthTokenResponse;
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
import com.documind.service.chat.ChatSessionService;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.ai.embedding.EmbeddingModel;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
 * validating {@code DA-15}: session CRUD, Strict Owner-Only {@code 404} isolation (even for {@code SYSTEM_ADMIN}),
 * Turn-1 rewrite bypass for TTFT, 4-turn follow-up contextualization, 6-message sliding window cap,
 * dynamic RBAC citation re-verification/redaction, FinOps token aggregation, and {@code 502} failure atomicity.
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
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class,
    MultiTurnSessionChatIntegrationTest.ContextualizingChatTestConfig.class
})
class MultiTurnSessionChatIntegrationTest {

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
    private EmbeddingModel embeddingModel;

    @Autowired
    private ContextualizingRecordingTestChatModel chatModel;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @BeforeEach
    void setUp() throws InterruptedException {
        cleanUp();
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        cleanUp();
    }

    private void cleanUp() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            null,
            Duration.ofSeconds(15)
        );
        chatModel.reset();
    }

    @Test
    void fourTurnFollowUpPreservesContextSkipsTurn1RewriteAndEnforcesSixMessageWindow() throws Exception {
        String annualLeaveChunkText =
            "Nhân viên ký hợp đồng chính thức có 14 ngày nghỉ phép năm, mỗi 5 năm thâm niên được cộng thêm 1 ngày.";
        String probationLeaveChunkText =
            "Nhân viên đang trong thời gian thử việc chưa được tính ngày phép năm cho đến khi ký chính thức.";
        String probationDurationChunkText =
            "Thời gian thử việc đối với vị trí chuyên viên kéo dài tối đa 60 ngày.";

        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-annual-leave.pdf",
            "pdf",
            "application/pdf",
            2,
            null,
            annualLeaveChunkText
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-probation-leave.pdf",
            "pdf",
            "application/pdf",
            3,
            null,
            probationLeaveChunkText
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-probation-duration.pdf",
            "pdf",
            "application/pdf",
            1,
            null,
            probationDurationChunkText
        );
        seedIndexedDocument(
            IT_DEPT_ID,
            AccessLevel.PUBLIC,
            "travel-hotel-policy.md",
            "md",
            "text/markdown",
            null,
            "Công tác phí",
            "Hạn mức lưu trú nội địa khách sạn công tác phí tối đa 1.500.000 VNĐ mỗi đêm."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);

        // Turn 1 (sessionId == null): auto-creates session, auto-titles from Turn 1, SKIPS query rewrite for TTFT!
        HttpResponse<String> turn1Raw = askChat(
            hrEmployeeJwt,
            "Chính sách nghỉ phép năm cho nhân viên chính thức như thế nào?",
            null
        );
        assertThat(turn1Raw.statusCode()).isEqualTo(200);
        ChatAskResponse turn1 = objectMapper.readValue(turn1Raw.body(), ChatAskResponse.class);
        assertThat(turn1.sessionId()).isNotNull();
        assertThat(turn1.messageId()).isNotNull();
        assertThat(turn1.rewrittenQuery()).isNull();
        assertThat(turn1.abstained()).isFalse();
        assertThat(turn1.citations())
            .extracting(CitationDto::fileName)
            .contains("hr-annual-leave.pdf");
        assertThat(chatModel.rewriteCallCount()).isZero();
        assertThat(chatModel.answerCallCount()).isEqualTo(1);
        assertThat(chatModel.wasTransactionActiveOnAnyCall()).isFalse();

        UUID sessionId = turn1.sessionId();
        HttpResponse<String> sessionRespRaw = sendAuthed("GET", "/api/v1/chat/sessions/" + sessionId, hrEmployeeJwt, null);
        assertThat(sessionRespRaw.statusCode()).isEqualTo(200);
        ChatSessionResponse sessionInfo = objectMapper.readValue(sessionRespRaw.body(), ChatSessionResponse.class);
        assertThat(sessionInfo.title()).isEqualTo("Chính sách nghỉ phép năm cho nhân viên chính thức như thế nào?");

        // Turn 2: genuine follow-up lacking any embedding trigger -> MUST be rewritten to exceed threshold 0.58
        String turn2RawQuestion = "Nếu gắn bó đủ 5 năm thì được cộng thêm mấy ngày?";
        assertThat(cosineSimilarity(turn2RawQuestion, annualLeaveChunkText)).isLessThan(0.58d);
        HttpResponse<String> turn2Raw = askChat(hrEmployeeJwt, turn2RawQuestion, sessionId);
        assertThat(turn2Raw.statusCode()).isEqualTo(200);
        ChatAskResponse turn2 = objectMapper.readValue(turn2Raw.body(), ChatAskResponse.class);
        assertThat(turn2.sessionId()).isEqualTo(sessionId);
        assertThat(turn2.abstained()).isFalse();
        assertThat(turn2.rewrittenQuery()).contains("nghỉ phép năm").contains("thâm niên");
        assertThat(turn2.citations()).extracting(CitationDto::fileName).contains("hr-annual-leave.pdf");
        assertThat(turn2.promptTokens()).isEqualTo(165); // 45 rewrite + 120 answer
        assertThat(turn2.completionTokens()).isEqualTo(50); // 14 rewrite + 36 answer
        assertThat(chatModel.rewriteCallCount()).isEqualTo(1);
        assertThat(chatModel.lastRewriteHistoryTurnCount()).isEqualTo(2);
        assertThat(chatModel.lastAnswerMessages().size()).isGreaterThanOrEqualTo(4);

        // Turn 3: genuine follow-up lacking any embedding trigger -> rewritten to probation leave query
        String turn3RawQuestion = "Còn người mới vào chưa ký chính thức thì chế độ đó tính sao?";
        assertThat(cosineSimilarity(turn3RawQuestion, probationLeaveChunkText)).isLessThan(0.58d);
        HttpResponse<String> turn3Raw = askChat(hrEmployeeJwt, turn3RawQuestion, sessionId);
        assertThat(turn3Raw.statusCode()).isEqualTo(200);
        ChatAskResponse turn3 = objectMapper.readValue(turn3Raw.body(), ChatAskResponse.class);
        assertThat(turn3.abstained()).isFalse();
        assertThat(turn3.rewrittenQuery()).contains("thử việc").contains("phép năm");
        assertThat(turn3.citations()).extracting(CitationDto::fileName).contains("hr-probation-leave.pdf");
        assertThat(chatModel.lastRewriteHistoryTurnCount()).isEqualTo(4);

        // Turn 4: genuine follow-up lacking any embedding trigger -> rewritten to probation duration query
        String turn4RawQuestion = "Giai đoạn vừa nêu kéo dài tối đa bao lâu?";
        assertThat(cosineSimilarity(turn4RawQuestion, probationDurationChunkText)).isLessThan(0.58d);
        HttpResponse<String> turn4Raw = askChat(hrEmployeeJwt, turn4RawQuestion, sessionId);
        assertThat(turn4Raw.statusCode()).isEqualTo(200);
        ChatAskResponse turn4 = objectMapper.readValue(turn4Raw.body(), ChatAskResponse.class);
        assertThat(turn4.abstained()).isFalse();
        assertThat(turn4.rewrittenQuery()).contains("thử việc").contains("bao nhiêu ngày");
        assertThat(turn4.citations()).extracting(CitationDto::fileName).contains("hr-probation-duration.pdf");
        assertThat(chatModel.lastRewriteHistoryTurnCount()).isEqualTo(6);

        // Turn 5 (session already has 8 messages -> sliding window caps at 6 most recent messages;
        // standalone topic shift returned unchanged -> rewrittenQuery MUST be null per RV-05!)
        HttpResponse<String> turn5Raw = askChat(
            hrEmployeeJwt,
            "Hạn mức khách sạn công tác phí nội địa là bao nhiêu?",
            sessionId
        );
        assertThat(turn5Raw.statusCode()).isEqualTo(200);
        ChatAskResponse turn5 = objectMapper.readValue(turn5Raw.body(), ChatAskResponse.class);
        assertThat(turn5.abstained()).isFalse();
        assertThat(turn5.rewrittenQuery()).isNull();
        assertThat(turn5.citations()).extracting(CitationDto::fileName).contains("travel-hotel-policy.md");
        assertThat(chatModel.lastRewriteHistoryTurnCount()).isEqualTo(6);
        assertThat(chatModel.lastRewriteSystemPrompt())
            .doesNotContain("Chính sách nghỉ phép năm cho nhân viên chính thức như thế nào?")
            .contains(turn2RawQuestion)
            .contains(turn4RawQuestion);
        assertThat(chatModel.wasTransactionActiveOnAnyCall()).isFalse();

        // Verify chronological ASC message history has all 10 messages (5 USER + 5 ASSISTANT) with live presigned URLs
        HttpResponse<String> historyRaw = sendAuthed("GET", "/api/v1/chat/sessions/" + sessionId + "/messages", hrEmployeeJwt, null);
        assertThat(historyRaw.statusCode()).isEqualTo(200);
        List<ChatMessageResponse> history = objectMapper.readValue(historyRaw.body(), new TypeReference<>() {});
        assertThat(history).hasSize(10);
        for (int i = 0; i < 10; i++) {
            assertThat(history.get(i).role()).isEqualTo(i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
            if (i > 0) {
                assertThat(history.get(i).createdAt()).isAfter(history.get(i - 1).createdAt());
            }
        }
        assertThat(history.get(1).rewrittenQuery()).isNull();
        assertThat(history.get(3).rewrittenQuery()).contains("nghỉ phép năm");
        assertThat(history.get(9).rewrittenQuery()).isNull();
        assertThat(history.get(1).citations().getFirst().presignedUrl()).contains("#page=2");
    }

    @Test
    void sessionCrudCustomTitleStrictOwnerOnly404AndCascadeDeletePreserveAuditLogs() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            "company-leave.pdf",
            "pdf",
            "application/pdf",
            1,
            null,
            "Nhân viên toàn công ty hưởng 14 ngày phép năm."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
        String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
        String adminJwt = loginAndGetAccessToken(ADMIN_EMAIL);

        // 1. Create empty default-titled session -> Turn 1 auto-titles it
        HttpResponse<String> createdDefaultRaw = sendAuthed("POST", "/api/v1/chat/sessions", hrEmployeeJwt, null);
        assertThat(createdDefaultRaw.statusCode()).isEqualTo(201);
        ChatSessionResponse defaultSession = objectMapper.readValue(createdDefaultRaw.body(), ChatSessionResponse.class);
        assertThat(defaultSession.title()).isEqualTo(ChatSessionService.DEFAULT_SESSION_TITLE);

        HttpResponse<String> askDefault = askChat(hrEmployeeJwt, "Số ngày nghỉ phép năm?", defaultSession.id());
        assertThat(askDefault.statusCode()).isEqualTo(200);
        ChatSessionResponse autoTitled = objectMapper.readValue(
            sendAuthed("GET", "/api/v1/chat/sessions/" + defaultSession.id(), hrEmployeeJwt, null).body(),
            ChatSessionResponse.class
        );
        assertThat(autoTitled.title()).isEqualTo("Số ngày nghỉ phép năm?");

        // 2. Create custom-titled session -> Turn 1 preserves custom title
        HttpResponse<String> createdCustomRaw = sendAuthed(
            "POST",
            "/api/v1/chat/sessions",
            hrEmployeeJwt,
            Map.of("title", "Tra cứu Phúc lợi 2026")
        );
        assertThat(createdCustomRaw.statusCode()).isEqualTo(201);
        ChatSessionResponse customSession = objectMapper.readValue(createdCustomRaw.body(), ChatSessionResponse.class);
        assertThat(customSession.title()).isEqualTo("Tra cứu Phúc lợi 2026");

        HttpResponse<String> askCustom = askChat(hrEmployeeJwt, "Số ngày nghỉ phép năm?", customSession.id());
        assertThat(askCustom.statusCode()).isEqualTo(200);
        ChatSessionResponse preservedCustom = objectMapper.readValue(
            sendAuthed("GET", "/api/v1/chat/sessions/" + customSession.id(), hrEmployeeJwt, null).body(),
            ChatSessionResponse.class
        );
        assertThat(preservedCustom.title()).isEqualTo("Tra cứu Phúc lợi 2026");

        // 3. Rename via PATCH & verify paginated list ordering (updatedAt DESC)
        HttpResponse<String> renamedRaw = sendAuthed(
            "PATCH",
            "/api/v1/chat/sessions/" + defaultSession.id(),
            hrEmployeeJwt,
            Map.of("title", "Phúc lợi phép năm (đã đổi tên)")
        );
        assertThat(renamedRaw.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readValue(renamedRaw.body(), ChatSessionResponse.class).title())
            .isEqualTo("Phúc lợi phép năm (đã đổi tên)");

        HttpResponse<String> listRaw = sendAuthed("GET", "/api/v1/chat/sessions?page=0&size=10", hrEmployeeJwt, null);
        assertThat(listRaw.statusCode()).isEqualTo(200);
        JsonNode listJson = objectMapper.readTree(listRaw.body());
        assertThat(listJson.get("totalElements").asLong()).isEqualTo(2L);
        assertThat(listJson.get("items").get(0).get("id").asText()).isEqualTo(defaultSession.id().toString());

        // 4. Strict Owner-Only 404 isolation: both Sales Employee AND SYSTEM_ADMIN receive 404!
        for (String otherJwt : List.of(salesEmployeeJwt, adminJwt)) {
            assertThat(sendAuthed("GET", "/api/v1/chat/sessions/" + defaultSession.id(), otherJwt, null).statusCode())
                .isEqualTo(404);
            assertThat(sendAuthed("GET", "/api/v1/chat/sessions/" + defaultSession.id() + "/messages", otherJwt, null).statusCode())
                .isEqualTo(404);
            assertThat(sendAuthed("PATCH", "/api/v1/chat/sessions/" + defaultSession.id(), otherJwt, Map.of("title", "Hack")).statusCode())
                .isEqualTo(404);
            assertThat(sendAuthed("DELETE", "/api/v1/chat/sessions/" + defaultSession.id(), otherJwt, null).statusCode())
                .isEqualTo(404);
            assertThat(askChat(otherJwt, "Hỏi ké phiên người khác?", defaultSession.id()).statusCode())
                .isEqualTo(404);
        }

        // 5. Delete session -> 204, cascades chat_messages, preserves audit_logs with session_id = NULL
        Integer auditsLinkedBeforeDelete = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE session_id = ?",
            Integer.class,
            defaultSession.id()
        );
        assertThat(auditsLinkedBeforeDelete).isEqualTo(1);

        HttpResponse<String> deleteRaw = sendAuthed("DELETE", "/api/v1/chat/sessions/" + defaultSession.id(), hrEmployeeJwt, null);
        assertThat(deleteRaw.statusCode()).isEqualTo(204);
        Integer remainingMsgs = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Integer.class,
            defaultSession.id()
        );
        assertThat(remainingMsgs).isZero();
        Integer preservedAuditsWithNullSession = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND session_id IS NULL",
            Integer.class
        );
        assertThat(preservedAuditsWithNullSession).isEqualTo(1);
    }

    @Test
    void historicalCitationsReverifyRbacAndRedactContentAndExcerptOnConfidentialUpgradeAndHardDelete() throws Exception {
        UUID hrDeptDocId = seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-dept-pvi.pdf",
            "pdf",
            "application/pdf",
            3,
            null,
            "Gói bảo hiểm sức khỏe PVI Care dành cho phòng HR hạn mức 200 triệu."
        );
        UUID publicDocId = seedIndexedDocument(
            IT_DEPT_ID,
            AccessLevel.PUBLIC,
            "public-remote-work.md",
            "md",
            "text/markdown",
            null,
            "Hybrid",
            "Chính sách làm việc từ xa hybrid work cho phép 2 ngày mỗi tuần."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
        String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);
        String adminJwt = loginAndGetAccessToken(ADMIN_EMAIL);

        // Turn 1: cite hr-dept-pvi.pdf
        ChatAskResponse t1 = objectMapper.readValue(
            askChat(hrEmployeeJwt, "Chính sách bảo hiểm sức khỏe PVI Care?", null).body(),
            ChatAskResponse.class
        );
        UUID sessionId = t1.sessionId();
        assertThat(t1.citations().getFirst().presignedUrl()).isNotBlank();
        assertThat(t1.citations().getFirst().excerpt()).contains("PVI Care");

        // Turn 2: cite public-remote-work.md
        ChatAskResponse t2 = objectMapper.readValue(
            askChat(hrEmployeeJwt, "Chính sách làm việc từ xa hybrid work?", sessionId).body(),
            ChatAskResponse.class
        );
        assertThat(t2.citations().getFirst().presignedUrl()).isNotBlank();

        // HR Manager upgrades hr-dept-pvi.pdf to CONFIDENTIAL (now forbidden to STANDARD_EMPLOYEE)
        HttpResponse<String> patchResp = sendAuthed(
            "PATCH",
            "/api/v1/documents/" + hrDeptDocId,
            hrManagerJwt,
            Map.of("accessLevel", "CONFIDENTIAL")
        );
        assertThat(patchResp.statusCode()).isEqualTo(200);

        // Admin deletes public-remote-work.md
        HttpResponse<String> delPublicResp = sendAuthed("DELETE", "/api/v1/documents/" + publicDocId, adminJwt, null);
        assertThat(delPublicResp.statusCode()).isEqualTo(204);

        // Reading session history fails closed for BOTH reclassified CONFIDENTIAL AND deleted docs:
        // presignedUrl = null, excerpt = "", content = REDACTED_ASSISTANT_CONTENT, rewrittenQuery = null!
        HttpResponse<String> historyRaw = sendAuthed("GET", "/api/v1/chat/sessions/" + sessionId + "/messages", hrEmployeeJwt, null);
        assertThat(historyRaw.statusCode()).isEqualTo(200);
        List<ChatMessageResponse> history = objectMapper.readValue(historyRaw.body(), new TypeReference<>() {});
        assertThat(history).hasSize(4);

        assertThat(history.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        assertThat(history.get(1).rewrittenQuery()).isNull();
        CitationDto reclassifiedCitation = history.get(1).citations().getFirst();
        assertThat(reclassifiedCitation.documentId()).isEqualTo(hrDeptDocId);
        assertThat(reclassifiedCitation.presignedUrl()).isNull();
        assertThat(reclassifiedCitation.excerpt()).isEmpty();

        assertThat(history.get(3).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        CitationDto deletedCitation = history.get(3).citations().getFirst();
        assertThat(deletedCitation.documentId()).isEqualTo(publicDocId);
        assertThat(deletedCitation.presignedUrl()).isNull();
        assertThat(deletedCitation.excerpt()).isEmpty();

        // Follow-up ask on the same session MUST exclude revoked/deleted turns from contextualizer & answer prompt
        chatModel.reset();
        HttpResponse<String> followUpRaw = askChat(hrEmployeeJwt, "Hạn mức ở lượt đầu là bao nhiêu?", sessionId);
        assertThat(followUpRaw.statusCode()).isEqualTo(200);
        assertThat(chatModel.lastRewriteSystemPrompt())
            .doesNotContain("PVI Care")
            .doesNotContain("200 triệu");

        // Hard-deleting the CONFIDENTIAL document MUST NOT un-redact its historical excerpt or assistant content!
        HttpResponse<String> delConfidentialResp = sendAuthed("DELETE", "/api/v1/documents/" + hrDeptDocId, adminJwt, null);
        assertThat(delConfidentialResp.statusCode()).isEqualTo(204);
        HttpResponse<String> historyAfterDeleteRaw = sendAuthed("GET", "/api/v1/chat/sessions/" + sessionId + "/messages", hrEmployeeJwt, null);
        assertThat(historyAfterDeleteRaw.statusCode()).isEqualTo(200);
        List<ChatMessageResponse> historyAfterDelete = objectMapper.readValue(historyAfterDeleteRaw.body(), new TypeReference<>() {});
        assertThat(historyAfterDelete.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
        assertThat(historyAfterDelete.get(1).citations().getFirst().presignedUrl()).isNull();
        assertThat(historyAfterDelete.get(1).citations().getFirst().excerpt()).isEmpty();
    }

    @Test
    void turn2AbstentionRecordsRewriteTokensAnd502FailureLeavesNoOrphanUserMessage() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-annual-leave.pdf",
            "pdf",
            "application/pdf",
            2,
            null,
            "Chính sách nghỉ phép năm cho nhân viên chính thức là 14 ngày phép năm."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);

        // Turn 1: grounded answer -> 2 chat_messages persisted
        ChatAskResponse t1 = objectMapper.readValue(
            askChat(hrEmployeeJwt, "Chính sách nghỉ phép năm?", null).body(),
            ChatAskResponse.class
        );
        UUID sessionId = t1.sessionId();
        assertThat(countMessagesInSession(sessionId)).isEqualTo(2);

        // Turn 2: OOS follow-up -> rewrite runs (45/14 tokens), vector search returns 0 chunks -> Answer LLM skipped!
        int answerCallsBeforeOos = chatModel.answerCallCount();
        HttpResponse<String> oosRaw = askChat(hrEmployeeJwt, "Công ty có tài trợ mua du thuyền vũ trụ không?", sessionId);
        assertThat(oosRaw.statusCode()).isEqualTo(200);
        ChatAskResponse oos = objectMapper.readValue(oosRaw.body(), ChatAskResponse.class);
        assertThat(oos.abstained()).isTrue();
        assertThat(oos.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(oos.citations()).isEmpty();
        assertThat(oos.promptTokens()).isEqualTo(45);
        assertThat(oos.completionTokens()).isEqualTo(14);
        assertThat(chatModel.answerCallCount()).isEqualTo(answerCallsBeforeOos);
        assertThat(countMessagesInSession(sessionId)).isEqualTo(4);

        // Turn 3: Answer LLM throws 502 -> abstained Turn 2 excluded from Answer prompt (size == 4: System + Turn1 U/A + Turn3 U),
        // zero orphan USER message added (still 4), 502 audit recorded with session_id
        chatModel.failNextAnswerCall();
        HttpResponse<String> failureRaw = askChat(hrEmployeeJwt, "Nếu làm đủ 5 năm thì được cộng thêm mấy ngày?", sessionId);
        assertThat(failureRaw.statusCode()).isEqualTo(502);
        assertThat(chatModel.lastAnswerMessages()).hasSize(4);
        assertThat(countMessagesInSession(sessionId)).isEqualTo(4);

        Integer failureAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE session_id = ? AND status_code = 502 AND prompt_tokens = 45 AND completion_tokens = 14",
            Integer.class,
            sessionId
        );
        assertThat(failureAuditCount).isEqualTo(1);
    }

    @Test
    void concurrentSameSessionTurnsPreserveAlternatingOrderAndMidFlightDeletePreservesFinOpsAudit() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "hr-annual-leave.pdf",
            "pdf",
            "application/pdf",
            2,
            null,
            "Chính sách nghỉ phép năm cho nhân viên chính thức là 14 ngày phép năm, làm đủ 5 năm cộng thêm 1 ngày."
        );

        String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);

        // Part (a): Mid-flight DELETE while askChat is paused inside Phase-2 Answer LLM -> returns 404,
        // does NOT resurrect deleted session/messages, and STILL commits FinOps audit_logs (165 prompt / 50 completion)!
        ChatAskResponse t1 = objectMapper.readValue(
            askChat(hrEmployeeJwt, "Chính sách nghỉ phép năm?", null).body(),
            ChatAskResponse.class
        );
        UUID deletedMidFlightSessionId = t1.sessionId();

        ContextualizingRecordingTestChatModel.AnswerHoldControl holdControl = chatModel.armSingleAnswerHold();
        CompletableFuture<HttpResponse<String>> inFlightAsk = CompletableFuture.supplyAsync(() -> {
            try {
                return askChat(hrEmployeeJwt, "Nếu làm đủ 5 năm thì cộng thêm mấy ngày?", deletedMidFlightSessionId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        assertThat(holdControl.enteredLatch().await(10, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> deleteResp = sendAuthed(
            "DELETE",
            "/api/v1/chat/sessions/" + deletedMidFlightSessionId,
            hrEmployeeJwt,
            null
        );
        assertThat(deleteResp.statusCode()).isEqualTo(204);
        holdControl.releaseLatch().countDown();

        HttpResponse<String> inFlightResult = inFlightAsk.get(15, TimeUnit.SECONDS);
        assertThat(inFlightResult.statusCode()).isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_sessions WHERE id = ?",
            Integer.class,
            deletedMidFlightSessionId
        )).isZero();
        assertThat(countMessagesInSession(deletedMidFlightSessionId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE session_id IS NULL AND status_code = 404 AND prompt_tokens = 165 AND completion_tokens = 50",
            Integer.class
        )).isEqualTo(1);

        // Part (b): Two concurrent POST /api/v1/chat/ask requests racing into Phase 3 on the same sessionId
        // serialize cleanly under FOR UPDATE and preserve strictly alternating USER -> ASSISTANT pairs
        chatModel.reset();
        HttpResponse<String> freshSessionRaw = sendAuthed("POST", "/api/v1/chat/sessions", hrEmployeeJwt, null);
        assertThat(freshSessionRaw.statusCode()).isEqualTo(201);
        UUID racingSessionId = objectMapper.readValue(freshSessionRaw.body(), ChatSessionResponse.class).id();

        CountDownLatch twoPartyBarrier = chatModel.armAnswerBarrier(2);
        CompletableFuture<HttpResponse<String>> reqA = CompletableFuture.supplyAsync(() -> {
            try {
                return askChat(hrEmployeeJwt, "Chính sách nghỉ phép năm?", racingSessionId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        CompletableFuture<HttpResponse<String>> reqB = CompletableFuture.supplyAsync(() -> {
            try {
                return askChat(hrEmployeeJwt, "Thâm niên 5 năm nghỉ phép năm được cộng gì?", racingSessionId);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });

        HttpResponse<String> respA = reqA.get(15, TimeUnit.SECONDS);
        HttpResponse<String> respB = reqB.get(15, TimeUnit.SECONDS);
        assertThat(twoPartyBarrier.getCount()).isZero();
        assertThat(respA.statusCode()).isEqualTo(200);
        assertThat(respB.statusCode()).isEqualTo(200);

        HttpResponse<String> raceHistoryRaw = sendAuthed(
            "GET",
            "/api/v1/chat/sessions/" + racingSessionId + "/messages",
            hrEmployeeJwt,
            null
        );
        assertThat(raceHistoryRaw.statusCode()).isEqualTo(200);
        List<ChatMessageResponse> raceHistory = objectMapper.readValue(raceHistoryRaw.body(), new TypeReference<>() {});
        assertThat(raceHistory).hasSize(4);
        assertThat(raceHistory)
            .extracting(ChatMessageResponse::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT);
        for (int i = 1; i < raceHistory.size(); i++) {
            assertThat(raceHistory.get(i).createdAt()).isAfter(raceHistory.get(i - 1).createdAt());
        }
    }

    private double cosineSimilarity(String a, String b) {
        float[] va = embeddingModel.embed(a);
        float[] vb = embeddingModel.embed(b);
        double dot = 0.0d;
        double normA = 0.0d;
        double normB = 0.0d;
        for (int i = 0; i < va.length; i++) {
            dot += (double) va[i] * vb[i];
            normA += (double) va[i] * va[i];
            normB += (double) vb[i] * vb[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private int countMessagesInSession(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE session_id = ?",
            Integer.class,
            sessionId
        );
        return count != null ? count : 0;
    }

    private UUID seedIndexedDocument(
        UUID departmentId,
        AccessLevel accessLevel,
        String originalName,
        String extension,
        String mimeType,
        Integer pageNumber,
        String sectionHeading,
        String chunkText
    ) throws Exception {
        DepartmentEntity dept = departmentRepository.findById(departmentId).orElseThrow();
        UUID docId = UUID.randomUUID();
        byte[] storageBytes = chunkText.getBytes(StandardCharsets.UTF_8);
        String storageKey = minioStorageService.buildStorageKey(departmentId, docId, 1, originalName);
        minioStorageService.uploadStream(
            storageKey,
            new ByteArrayInputStream(storageBytes),
            storageBytes.length,
            mimeType
        );

        String hash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest((docId + ":" + chunkText).getBytes(StandardCharsets.UTF_8))
        );

        DocumentEntity entity = new DocumentEntity();
        entity.setId(docId);
        entity.setDepartment(dept);
        entity.setOriginalName(originalName);
        entity.setStorageKey(storageKey);
        entity.setFileExtension(extension);
        entity.setMimeType(mimeType);
        entity.setFileSizeBytes((long) storageBytes.length);
        entity.setFileHash(hash);
        entity.setAccessLevel(accessLevel);
        entity.setStatus(DocumentStatus.COMPLETED);
        entity.setCurrentVersion(1);
        entity.setChunkCount(1);
        documentRepository.saveAndFlush(entity);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChunkMetadataKeys.DOCUMENT_ID, docId.toString());
        metadata.put(ChunkMetadataKeys.DEPARTMENT_ID, departmentId.toString());
        metadata.put(ChunkMetadataKeys.ACCESS_LEVEL, accessLevel.name());
        metadata.put(ChunkMetadataKeys.FILE_NAME, originalName);
        metadata.put(ChunkMetadataKeys.DOC_VERSION, 1);
        metadata.put(ChunkMetadataKeys.CHUNK_INDEX, 0);
        if (pageNumber != null) {
            metadata.put(ChunkMetadataKeys.PAGE_NUMBER, pageNumber);
        }
        if (sectionHeading != null) {
            metadata.put(ChunkMetadataKeys.SECTION_HEADING, sectionHeading);
        }

        Document chunk = Document.builder()
            .id(ChunkIdGenerator.generate(docId, 1, 0).toString())
            .text(chunkText)
            .metadata(metadata)
            .build();
        vectorStore.add(List.of(chunk));
        return docId;
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri("/api/v1/auth/login"))
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", SEED_DEV_PASSWORD
            ))))
            .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return Objects.requireNonNull(objectMapper.readValue(resp.body(), AuthTokenResponse.class).accessToken());
    }

    private HttpResponse<String> askChat(String bearerJwt, String question, UUID sessionId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        if (sessionId != null) {
            payload.put("sessionId", sessionId.toString());
        }
        return sendAuthed("POST", "/api/v1/chat/ask", bearerJwt, payload);
    }

    private HttpResponse<String> sendAuthed(String method, String path, String bearerJwt, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerJwt);
        HttpRequest.BodyPublisher publisher;
        if (body != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            publisher = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body));
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }
        builder.method(method, publisher);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ContextualizingChatTestConfig {

        @Bean
        @Primary
        ContextualizingRecordingTestChatModel contextualizingRecordingTestChatModel() {
            return new ContextualizingRecordingTestChatModel();
        }

        @Bean(name = "testChatClientOverridePostProcessor")
        static BeanPostProcessor contextualizingChatClientOverridePostProcessor(
            ObjectProvider<ContextualizingRecordingTestChatModel> modelProvider
        ) {
            return new BeanPostProcessor() {
                private final Object monitor = new Object();
                private volatile ContextualizingRecordingTestChatModel resolved;

                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                    if (bean instanceof ChatClient) {
                        return ChatClient.create(resolveModel());
                    }
                    if (bean instanceof ChatClient.Builder) {
                        return ChatClient.builder(resolveModel());
                    }
                    return bean;
                }

                private ContextualizingRecordingTestChatModel resolveModel() {
                    ContextualizingRecordingTestChatModel current = resolved;
                    if (current != null) {
                        return current;
                    }
                    synchronized (monitor) {
                        if (resolved == null) {
                            resolved = modelProvider.getObject();
                        }
                        return resolved;
                    }
                }
            };
        }
    }

    static final class ContextualizingRecordingTestChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private static final Pattern TURN_TAG_PATTERN = Pattern.compile("<turn\\s+index=");

        private final AtomicInteger rewriteCalls = new AtomicInteger();
        private final AtomicInteger answerCalls = new AtomicInteger();
        private final AtomicInteger lastRewriteTurnCount = new AtomicInteger();
        private final AtomicReference<String> lastRewriteSystem = new AtomicReference<>("");
        private final AtomicReference<List<Message>> lastAnswerMsgs = new AtomicReference<>(List.of());
        private final AtomicBoolean txActiveAny = new AtomicBoolean(false);
        private final AtomicBoolean failNextAnswer = new AtomicBoolean(false);
        private final AtomicReference<AnswerHoldControl> singleHoldRef = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> answerBarrierRef = new AtomicReference<>();

        record AnswerHoldControl(CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
        }

        void reset() {
            rewriteCalls.set(0);
            answerCalls.set(0);
            lastRewriteTurnCount.set(0);
            lastRewriteSystem.set("");
            lastAnswerMsgs.set(List.of());
            txActiveAny.set(false);
            failNextAnswer.set(false);
            AnswerHoldControl staleHold = singleHoldRef.getAndSet(null);
            if (staleHold != null) {
                staleHold.enteredLatch().countDown();
                staleHold.releaseLatch().countDown();
            }
            CountDownLatch staleBarrier = answerBarrierRef.getAndSet(null);
            if (staleBarrier != null) {
                while (staleBarrier.getCount() > 0) {
                    staleBarrier.countDown();
                }
            }
        }

        AnswerHoldControl armSingleAnswerHold() {
            AnswerHoldControl control = new AnswerHoldControl(new CountDownLatch(1), new CountDownLatch(1));
            singleHoldRef.set(control);
            return control;
        }

        CountDownLatch armAnswerBarrier(int parties) {
            CountDownLatch barrier = new CountDownLatch(parties);
            answerBarrierRef.set(barrier);
            return barrier;
        }

        void failNextAnswerCall() {
            failNextAnswer.set(true);
        }

        int rewriteCallCount() {
            return rewriteCalls.get();
        }

        int answerCallCount() {
            return answerCalls.get();
        }

        int lastRewriteHistoryTurnCount() {
            return lastRewriteTurnCount.get();
        }

        String lastRewriteSystemPrompt() {
            return lastRewriteSystem.get();
        }

        List<Message> lastAnswerMessages() {
            return lastAnswerMsgs.get();
        }

        boolean wasTransactionActiveOnAnyCall() {
            return txActiveAny.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                txActiveAny.set(true);
            }
            String systemText = prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElse("");
            String latestUserText = prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.USER)
                .map(Message::getText)
                .reduce((first, second) -> second)
                .orElse("");

            if (systemText.contains("<conversation_history>")) {
                rewriteCalls.incrementAndGet();
                lastRewriteSystem.set(systemText);
                int turns = 0;
                Matcher m = TURN_TAG_PATTERN.matcher(systemText);
                while (m.find()) {
                    turns++;
                }
                lastRewriteTurnCount.set(turns);

                String rewritten = resolveDeterministicRewrite(systemText, latestUserText);
                ChatResponseMetadata meta = ChatResponseMetadata.builder()
                    .usage(new DefaultUsage(45, 14, 59))
                    .build();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(rewritten))), meta);
            }

            answerCalls.incrementAndGet();
            lastAnswerMsgs.set(List.copyOf(prompt.getInstructions()));
            if (failNextAnswer.compareAndSet(true, false)) {
                throw new RuntimeException("Simulated Groq 503 outage");
            }
            AnswerHoldControl hold = singleHoldRef.getAndSet(null);
            if (hold != null) {
                hold.enteredLatch().countDown();
                try {
                    if (!hold.releaseLatch().await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for AnswerHoldControl release");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for AnswerHoldControl release", ex);
                }
            }
            CountDownLatch barrier = answerBarrierRef.get();
            if (barrier != null) {
                barrier.countDown();
                try {
                    if (!barrier.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting at concurrent answer barrier");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting at concurrent answer barrier", ex);
                }
            }
            Matcher citationMatcher = CITATION_ATTR_PATTERN.matcher(systemText);
            String firstCitation = citationMatcher.find() ? citationMatcher.group(1) : "[Nguồn: document]";
            String answer = "Theo tài liệu nội bộ " + firstCitation + ".";
            ChatResponseMetadata meta = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 36, 156))
                .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), meta);
        }

        private static String resolveDeterministicRewrite(String historyXml, String latestUserText) {
            if (latestUserText.contains("5 năm") && historyXml.contains("nghỉ phép năm")) {
                return "Nhân viên chính thức làm đủ 5 năm thâm niên được cộng thêm mấy ngày nghỉ phép năm?";
            }
            if (latestUserText.contains("chế độ đó") && historyXml.contains("nghỉ phép năm")) {
                return "Nhân viên đang trong thời gian thử việc có được tính ngày phép năm không?";
            }
            if (latestUserText.contains("Giai đoạn vừa nêu")
                && (historyXml.contains("thử việc")
                    || (historyXml.contains("chưa ký chính thức") && historyXml.contains("hr-probation-leave.pdf")))) {
                return "Thời gian thử việc kéo dài tối đa bao nhiêu ngày?";
            }
            return latestUserText;
        }
    }
}
