package com.documind.controller;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkIdGenerator;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagChatService;
import com.documind.service.rag.RagConstants;
import com.documind.service.rag.RbacVectorFilterFactory;
import com.documind.service.rag.RbacVectorScope;
import com.documind.service.rag.VectorRetrievalService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.AbstractIntegrationTest.RecordingTestChatModel;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers integration suite ({@code pgvector/pgvector:pg16}, {@code redis:7-alpine}, {@code minio})
 * validating Document-Level Metadata RBAC Vector Filtering, SQL Top-K crowding pushdown, Java Layer 2a Defense-in-Depth,
 * and Spring AOP {@code @PreAuthorize} enforcement over real Bearer JWT authentication
 * with {@code documind.security.dev-caller-fallback.enabled=false}.
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
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
class VectorRbacRetrievalIntegrationTest {

    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    private static final String ADMIN_EMAIL = "admin@documind.ai";
    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String HR_EMPLOYEE_EMAIL = "employee.hr@documind.ai";
    private static final String SALES_MANAGER_EMAIL = "manager.sales@documind.ai";
    private static final String SALES_EMPLOYEE_EMAIL = "employee.sales@documind.ai";
    private static final String IT_EMPLOYEE_EMAIL = "employee.it@documind.ai";

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID SALES_DEPT_ID = AbstractIntegrationTest.SALES_DEPT_ID;
    private static final UUID HR_EMPLOYEE_USER_ID = AbstractIntegrationTest.HR_EMPLOYEE_USER_ID;
    private static final UUID IT_EMPLOYEE_USER_ID = AbstractIntegrationTest.IT_EMPLOYEE_USER_ID;

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
    private RecordingTestChatModel recordingChatModel;

    @Autowired
    private RagChatService ragChatService;

    @Autowired
    private VectorRetrievalService vectorRetrievalService;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private void cleanUpTestState() throws InterruptedException {
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            recordingChatModel,
            Duration.ofSeconds(15)
        );
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MandatoryRbacTruthMatrixTests {

        private final Map<String, String> jwtByEmail = new HashMap<>();

        @BeforeAll
        void seedReadOnlyMatrixAndLogins() throws Exception {
            cleanUpTestState();
            AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
            seedFourDocumentMatrix();
            jwtByEmail.clear();
            for (String email : List.of(
                ADMIN_EMAIL,
                HR_MANAGER_EMAIL,
                HR_EMPLOYEE_EMAIL,
                SALES_MANAGER_EMAIL,
                SALES_EMPLOYEE_EMAIL
            )) {
                jwtByEmail.put(email, loginAndGetAccessToken(email));
            }
        }

        @AfterAll
        void cleanupMatrix() throws InterruptedException {
            jwtByEmail.clear();
            cleanUpTestState();
        }

        @ParameterizedTest(name = "{0} -> {1} (expectedVisible={3})")
        @MethodSource("com.documind.controller.VectorRbacRetrievalIntegrationTest#mandatoryRbacTruthMatrix")
        void mandatoryRbacTruthMatrixParameterizedOverRealPgVectorAndJwt(
            String callerEmail,
            String targetFileName,
            String question,
            boolean expectedVisible
        ) throws Exception {
            String jwt = Objects.requireNonNull(jwtByEmail.get(callerEmail), "Missing JWT for " + callerEmail);

            recordingChatModel.reset();
            HttpResponse<String> resp = askChat(jwt, question, null);
            assertThat(resp.statusCode()).isEqualTo(200);

            ChatAskResponse body = objectMapper.readValue(resp.body(), ChatAskResponse.class);
            if (expectedVisible) {
                assertThat(body.abstained()).isFalse();
                assertThat(body.citations())
                    .isNotEmpty()
                    .extracting(CitationDto::fileName)
                    .contains(targetFileName);
                assertThat(recordingChatModel.callCount()).isEqualTo(1);
            } else {
                assertThat(body.abstained()).isTrue();
                assertThat(body.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
                assertThat(body.citations()).isEmpty();
                assertThat(recordingChatModel.callCount()).isZero();
            }
        }
    }

    @Nested
    class LifecycleAndDefenseInDepthTests {

        @BeforeEach
        void setUp() throws InterruptedException {
            cleanUpTestState();
            AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
        }

        @AfterEach
        void tearDown() throws InterruptedException {
            SecurityContextHolder.clearContext();
            cleanUpTestState();
        }

        @Test
        void jiraAcHrInternalDocumentVisibleToHrEmployeeAndHiddenFromSalesEmployeeWithZeroLlmCalls() throws Exception {
            byte[] hrPayload = "Chính sách nghỉ phép năm nội bộ phòng HR: nhân viên chính thức được hưởng 14 ngày phép nguyên lương."
                .getBytes(StandardCharsets.UTF_8);
            UUID hrDocId = seedIndexedDocument(
                HR_DEPT_ID,
                AccessLevel.DEPARTMENT,
                DocumentStatus.COMPLETED,
                "hr-leave-policy-2026.pdf",
                "pdf",
                "application/pdf",
                2,
                null,
                new String(hrPayload, StandardCharsets.UTF_8),
                hrPayload
            );

            String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
            String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
            String itEmployeeJwt = loginAndGetAccessToken(IT_EMPLOYEE_EMAIL);

            String question = "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm theo chính sách HR?";

            // 1. HR employee receives grounded answer + valid MinIO citation
            recordingChatModel.reset();
            HttpResponse<String> hrResp = askChat(hrEmployeeJwt, question, null);
            assertThat(hrResp.statusCode()).isEqualTo(200);
            ChatAskResponse hrBody = objectMapper.readValue(hrResp.body(), ChatAskResponse.class);
            assertThat(hrBody.abstained()).isFalse();
            assertThat(hrBody.answer()).contains("[Nguồn: hr-leave-policy-2026.pdf - Trang 2]");
            assertThat(hrBody.citations()).hasSize(1);
            CitationDto citation = hrBody.citations().getFirst();
            assertThat(citation.documentId()).isEqualTo(hrDocId);
            assertThat(citation.fileName()).isEqualTo("hr-leave-policy-2026.pdf");
            assertThat(citation.pageNumber()).isEqualTo(2);
            assertThat(citation.presignedUrl()).isNotBlank().endsWith("#page=2");
            assertThat(recordingChatModel.callCount()).isEqualTo(1);

            String rawMinioUrl = citation.presignedUrl().substring(0, citation.presignedUrl().indexOf('#'));
            HttpResponse<byte[]> minioDownload = httpClient.send(
                HttpRequest.newBuilder(URI.create(rawMinioUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(minioDownload.statusCode()).isEqualTo(200);
            assertThat(minioDownload.body()).isEqualTo(hrPayload);

            // 2. SALES employee receives deterministic refusal with 0 LLM tokens spent
            recordingChatModel.reset();
            HttpResponse<String> salesResp = askChat(salesEmployeeJwt, question, null);
            assertThat(salesResp.statusCode()).isEqualTo(200);
            ChatAskResponse salesBody = objectMapper.readValue(salesResp.body(), ChatAskResponse.class);
            assertThat(salesBody.abstained()).isTrue();
            assertThat(salesBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(salesBody.citations()).isEmpty();
            assertThat(salesBody.promptTokens()).isZero();
            assertThat(salesBody.completionTokens()).isZero();
            assertThat(recordingChatModel.callCount()).isZero();

            // 3. IT employee is also isolated with 0 LLM calls
            recordingChatModel.reset();
            HttpResponse<String> itResp = askChat(itEmployeeJwt, question, null);
            assertThat(itResp.statusCode()).isEqualTo(200);
            ChatAskResponse itBody = objectMapper.readValue(itResp.body(), ChatAskResponse.class);
            assertThat(itBody.abstained()).isTrue();
            assertThat(itBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(itBody.citations()).isEmpty();
            assertThat(recordingChatModel.callCount()).isZero();

            // 4. Unauthenticated request carrying spoofed legacy headers fails closed with 401 AUTHENTICATION_REQUIRED
            HttpRequest spoofedReq = HttpRequest.newBuilder(uri("/api/v1/chat/ask"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-User-Role", "SYSTEM_ADMIN")
                .header("X-Department-Id", HR_DEPT_ID.toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("question", question))))
                .build();
            HttpResponse<String> spoofedResp = httpClient.send(spoofedReq, HttpResponse.BodyHandlers.ofString());
            assertThat(spoofedResp.statusCode()).isEqualTo(401);
            assertThat(objectMapper.readTree(spoofedResp.body()).path("errorCode").asText())
                .isEqualTo("AUTHENTICATION_REQUIRED");
        }

        @Test
        void systemAdminGlobalVsScopedDepartmentFilterAndNonAdminAntiSpoofingWithAuditAttribution() throws Exception {
            seedFourDocumentMatrix();
            seedIndexedDocument(
                SALES_DEPT_ID,
                AccessLevel.PUBLIC,
                DocumentStatus.COMPLETED,
                "sales-public-hybrid.md",
                "md",
                "text/markdown",
                null,
                "Hybrid Policy",
                "Chính sách làm việc từ xa và khung giờ phối hợp 09:30 toàn công ty.",
                "Chính sách làm việc từ xa".getBytes(StandardCharsets.UTF_8)
            );

            String adminJwt = loginAndGetAccessToken(ADMIN_EMAIL);
            String salesEmpJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
            String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);

            String qHrConfidential = "Quy định thử việc 60 ngày và bậc lương mật của HR là gì?";
            String qSalesDept = "Ngày chi trả hoa hồng doanh số hàng tháng của phòng Sales là ngày nào?";
            String qSalesPublic = "Khung giờ phối hợp 09:30 khi làm việc từ xa là gì?";

            // 1. Admin global query (departmentId = null) accesses HR CONFIDENTIAL and SALES DEPARTMENT
            recordingChatModel.reset();
            HttpResponse<String> globalHr = askChat(adminJwt, qHrConfidential, null);
            assertThat(globalHr.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readValue(globalHr.body(), ChatAskResponse.class).abstained()).isFalse();

            // 2. Admin scoped strictly to SALES_DEPT_ID sees SALES docs (DEPARTMENT + PUBLIC), hides HR CONFIDENTIAL & HR PUBLIC
            recordingChatModel.reset();
            HttpResponse<String> adminSalesDept = askChat(adminJwt, qSalesDept, SALES_DEPT_ID);
            assertThat(adminSalesDept.statusCode()).isEqualTo(200);
            ChatAskResponse adminSalesDeptBody = objectMapper.readValue(adminSalesDept.body(), ChatAskResponse.class);
            assertThat(adminSalesDeptBody.abstained()).isFalse();
            assertThat(adminSalesDeptBody.citations()).extracting(CitationDto::fileName).contains("sales-dept-commission.docx");

            recordingChatModel.reset();
            HttpResponse<String> adminSalesPublic = askChat(adminJwt, qSalesPublic, SALES_DEPT_ID);
            assertThat(adminSalesPublic.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readValue(adminSalesPublic.body(), ChatAskResponse.class).abstained()).isFalse();

            recordingChatModel.reset();
            HttpResponse<String> adminSalesAskingHr = askChat(adminJwt, qHrConfidential, SALES_DEPT_ID);
            assertThat(adminSalesAskingHr.statusCode()).isEqualTo(200);
            ChatAskResponse adminScopedRefusal = objectMapper.readValue(adminSalesAskingHr.body(), ChatAskResponse.class);
            assertThat(adminScopedRefusal.abstained()).isTrue();
            assertThat(adminScopedRefusal.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(recordingChatModel.callCount()).isZero();

            // Audit logs attribute Admin's scoped queries to Admin's own department (IT_DEPT_ID) for FinOps
            List<UUID> adminAuditDepts = jdbcTemplate.queryForList(
                "SELECT department_id FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND user_id = ?",
                UUID.class,
                AbstractIntegrationTest.ADMIN_USER_ID
            );
            assertThat(adminAuditDepts).hasSize(4).containsOnly(IT_DEPT_ID);

            // 3. Admin passing unknown departmentId -> 404 RESOURCE_NOT_FOUND
            recordingChatModel.reset();
            HttpResponse<String> adminUnknownDept = askChat(adminJwt, qSalesDept, UUID.randomUUID());
            assertThat(adminUnknownDept.statusCode()).isEqualTo(404);
            JsonNode notFoundJson = objectMapper.readTree(adminUnknownDept.body());
            assertThat(notFoundJson.path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
            assertThat(recordingChatModel.callCount()).isZero();

            // 4. Non-admin passing departmentId -> 403 DOCUMENT_ACCESS_DENIED (anti-spoofing)
            recordingChatModel.reset();
            HttpResponse<String> salesSpoofHr = askChat(salesEmpJwt, qHrConfidential, HR_DEPT_ID);
            assertThat(salesSpoofHr.statusCode()).isEqualTo(403);
            assertThat(objectMapper.readTree(salesSpoofHr.body()).path("errorCode").asText())
                .isEqualTo("DOCUMENT_ACCESS_DENIED");

            HttpResponse<String> hrManagerSpoofSales = askChat(hrManagerJwt, qSalesDept, SALES_DEPT_ID);
            assertThat(hrManagerSpoofSales.statusCode()).isEqualTo(403);
            assertThat(objectMapper.readTree(hrManagerSpoofSales.body()).path("errorCode").asText())
                .isEqualTo("DOCUMENT_ACCESS_DENIED");

            HttpResponse<String> hrManagerOwnDeptParam = askChat(hrManagerJwt, qHrConfidential, HR_DEPT_ID);
            assertThat(hrManagerOwnDeptParam.statusCode()).isEqualTo(403);
            assertThat(recordingChatModel.callCount()).isZero();
        }

        @Test
        void liveReclassificationProcessingIsolationInactiveUserAndInstantRoleTransferEnforcedImmediately() throws Exception {
            UUID hrReclassifiedDocId = seedIndexedDocument(
                HR_DEPT_ID,
                AccessLevel.PUBLIC,
                DocumentStatus.COMPLETED,
                "hr-vpn-guidelines.pdf",
                "pdf",
                "application/pdf",
                1,
                null,
                "Hướng dẫn cấu hình WireGuard VPN và khóa màn hình bắt buộc cho toàn công ty.",
                "WireGuard VPN".getBytes(StandardCharsets.UTF_8)
            );
            UUID processingDocId = seedIndexedDocument(
                HR_DEPT_ID,
                AccessLevel.DEPARTMENT,
                DocumentStatus.PROCESSING,
                "hr-draft-canary.pdf",
                "pdf",
                "application/pdf",
                1,
                null,
                "Tài liệu bản nháp canary rollout traffic percentages đang trong trạng thái xử lý.",
                "Draft canary rollout".getBytes(StandardCharsets.UTF_8)
            );
            try {
                String adminJwt = loginAndGetAccessToken(ADMIN_EMAIL);
                String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);
                String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
                String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
                String itEmployeeJwt = loginAndGetAccessToken(IT_EMPLOYEE_EMAIL);

                String vpnQuestion = "Hướng dẫn cấu hình WireGuard VPN và khóa màn hình như thế nào?";

                // 1. While PUBLIC, SALES employee can query it
                recordingChatModel.reset();
                HttpResponse<String> beforePatch = askChat(salesEmployeeJwt, vpnQuestion, null);
                assertThat(beforePatch.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(beforePatch.body(), ChatAskResponse.class).abstained()).isFalse();

                // 2. HR Manager upgrades accessLevel PUBLIC -> CONFIDENTIAL via PATCH (V6 trigger propagates chunks)
                HttpRequest patchReq = HttpRequest.newBuilder(uri("/api/v1/documents/" + hrReclassifiedDocId))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrManagerJwt)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"accessLevel\":\"CONFIDENTIAL\"}"))
                    .build();
                HttpResponse<String> patchResp = httpClient.send(patchReq, HttpResponse.BodyHandlers.ofString());
                assertThat(patchResp.statusCode()).isEqualTo(200);

                // 3. Immediately hidden from SALES employee and HR standard employee (0 LLM calls), still visible to HR Manager
                recordingChatModel.reset();
                HttpResponse<String> salesAfterPatch = askChat(salesEmployeeJwt, vpnQuestion, null);
                assertThat(salesAfterPatch.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(salesAfterPatch.body(), ChatAskResponse.class).abstained()).isTrue();
                assertThat(recordingChatModel.callCount()).isZero();

                HttpResponse<String> hrEmpAfterPatch = askChat(hrEmployeeJwt, vpnQuestion, null);
                assertThat(hrEmpAfterPatch.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(hrEmpAfterPatch.body(), ChatAskResponse.class).abstained()).isTrue();
                assertThat(recordingChatModel.callCount()).isZero();

                HttpResponse<String> hrMgrAfterPatch = askChat(hrManagerJwt, vpnQuestion, null);
                assertThat(hrMgrAfterPatch.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(hrMgrAfterPatch.body(), ChatAskResponse.class).abstained()).isFalse();
                assertThat(recordingChatModel.callCount()).isEqualTo(1);

                // 4. PROCESSING document chunks are blocked by Java Defense-in-Depth even for HR Manager & SYSTEM_ADMIN
                recordingChatModel.reset();
                String processingQuestion = "Canary rollout traffic percentages quy định như thế nào?";
                HttpResponse<String> adminProcessingResp = askChat(adminJwt, processingQuestion, null);
                assertThat(adminProcessingResp.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(adminProcessingResp.body(), ChatAskResponse.class).abstained()).isTrue();
                assertThat(recordingChatModel.callCount()).isZero();

                // 5. Inactive user in DB (before/without Redis revocation) is blocked with 403 DOCUMENT_ACCESS_DENIED
                jdbcTemplate.update("UPDATE users SET is_active = FALSE WHERE id = ?", IT_EMPLOYEE_USER_ID);
                recordingChatModel.reset();
                HttpResponse<String> inactiveAsk = askChat(itEmployeeJwt, vpnQuestion, null);
                assertThat(inactiveAsk.statusCode()).isEqualTo(403);
                assertThat(objectMapper.readTree(inactiveAsk.body()).path("errorCode").asText())
                    .isEqualTo("DOCUMENT_ACCESS_DENIED");
                assertThat(recordingChatModel.callCount()).isZero();

                // 6. Instant JWT revocation synergy (DA-13 -> DA-14): Admin transfers HR employee to SALES
                seedIndexedDocument(
                    HR_DEPT_ID,
                    AccessLevel.DEPARTMENT,
                    DocumentStatus.COMPLETED,
                    "hr-pvi-care.pdf",
                    "pdf",
                    "application/pdf",
                    1,
                    null,
                    "Gói bảo hiểm sức khỏe PVI Care có hạn mức nội trú 200 triệu VNĐ.",
                    "PVI Care".getBytes(StandardCharsets.UTF_8)
                );
                HttpRequest transferReq = HttpRequest.newBuilder(uri("/api/v1/admin/users/" + HR_EMPLOYEE_USER_ID))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminJwt)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("departmentId", SALES_DEPT_ID.toString()))
                    ))
                    .build();
                HttpResponse<String> transferResp = httpClient.send(transferReq, HttpResponse.BodyHandlers.ofString());
                assertThat(transferResp.statusCode()).isEqualTo(200);

                // Old HR JWT is revoked immediately (401 TOKEN_REVOKED)
                HttpResponse<String> oldTokenAsk = askChat(hrEmployeeJwt, "Gói bảo hiểm sức khỏe PVI Care hạn mức bao nhiêu?", null);
                assertThat(oldTokenAsk.statusCode()).isEqualTo(401);
                assertThat(objectMapper.readTree(oldTokenAsk.body()).path("errorCode").asText()).isEqualTo("TOKEN_REVOKED");

                // Fresh JWT after re-login carries SALES scope -> cannot see HR DEPARTMENT document
                String transferredJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
                recordingChatModel.reset();
                HttpResponse<String> newTokenAsk = askChat(transferredJwt, "Gói bảo hiểm sức khỏe PVI Care hạn mức bao nhiêu?", null);
                assertThat(newTokenAsk.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(newTokenAsk.body(), ChatAskResponse.class).abstained()).isTrue();
                assertThat(recordingChatModel.callCount()).isZero();
            } finally {
                // Remove synthetic non-worker PROCESSING document before cleanSharedInfrastructure awaits active workers
                jdbcTemplate.update("DELETE FROM documents WHERE id = ?", processingDocId);
            }
        }

        /**
         * RV-03 regression: proves SQL-level {@code metadata::jsonb @@ ...::jsonpath} pushdown +
         * {@code SET hnsw.iterative_scan = 'relaxed_order'} when 16 unauthorized higher-similarity chunks
         * (> candidateFetchLimit = 10) crowd the exact same query concept above 1 authorized lower-similarity chunk.
         */
        @Test
        void sqlRbacFilterPreventsTopKCrowdingWhenMoreThanCandidateFetchLimitUnauthorizedChunksMatchSameTopic() throws Exception {
            // 12 HR CONFIDENTIAL chunks (~0.995 cosine similarity to annual leave query)
            for (int i = 1; i <= 12; i++) {
                String secretText = "Chính sách nghỉ phép năm bí mật cấp điều hành CONFIDENTIAL_SECRET_MARKER_" + i;
                seedIndexedDocument(
                    HR_DEPT_ID,
                    AccessLevel.CONFIDENTIAL,
                    DocumentStatus.COMPLETED,
                    "hr-secret-leave-" + i + ".pdf",
                    "pdf",
                    "application/pdf",
                    1,
                    null,
                    secretText,
                    secretText.getBytes(StandardCharsets.UTF_8)
                );
            }
            // 4 SALES DEPARTMENT chunks (~0.995 cosine similarity to annual leave query)
            for (int i = 1; i <= 4; i++) {
                String salesText = "Quy định nghỉ phép năm riêng phòng kinh doanh SALES_ONLY_MARKER_" + i;
                seedIndexedDocument(
                    SALES_DEPT_ID,
                    AccessLevel.DEPARTMENT,
                    DocumentStatus.COMPLETED,
                    "sales-leave-" + i + ".pdf",
                    "pdf",
                    "application/pdf",
                    1,
                    null,
                    salesText,
                    salesText.getBytes(StandardCharsets.UTF_8)
                );
            }
            // 1 authorized HR DEPARTMENT chunk blending 2 concepts ("nghỉ phép" + "khách sạn") so cosine similarity (~0.704)
            // exceeds threshold 0.58 yet ranks strictly BELOW all 16 unauthorized chunks (~0.995).
            String authorizedText = "Chính sách nghỉ phép năm cho nhân viên HR: được 14 ngày phép và hỗ trợ khách sạn công tác.";
            UUID authorizedDocId = seedIndexedDocument(
                HR_DEPT_ID,
                AccessLevel.DEPARTMENT,
                DocumentStatus.COMPLETED,
                "hr-authorized-leave.pdf",
                "pdf",
                "application/pdf",
                4,
                null,
                authorizedText,
                authorizedText.getBytes(StandardCharsets.UTF_8)
            );

            String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
            String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);
            String query = "Chính sách nghỉ phép năm quy định như thế nào?";

            // HR STANDARD_EMPLOYEE must still retrieve hr-authorized-leave.pdf despite 16 higher-ranked unauthorized chunks
            recordingChatModel.reset();
            HttpResponse<String> empResp = askChat(hrEmployeeJwt, query, null);
            assertThat(empResp.statusCode()).isEqualTo(200);
            ChatAskResponse empBody = objectMapper.readValue(empResp.body(), ChatAskResponse.class);
            assertThat(empBody.abstained()).isFalse();
            assertThat(empBody.citations()).hasSize(1);
            assertThat(empBody.citations().getFirst().documentId()).isEqualTo(authorizedDocId);
            assertThat(empBody.citations().getFirst().fileName()).isEqualTo("hr-authorized-leave.pdf");
            assertThat(recordingChatModel.callCount()).isEqualTo(1);
            assertThat(recordingChatModel.lastSystemPrompt())
                .contains("hr-authorized-leave.pdf")
                .doesNotContain("CONFIDENTIAL_SECRET_MARKER_")
                .doesNotContain("SALES_ONLY_MARKER_");

            // HR DEPARTMENT_MANAGER receives HR CONFIDENTIAL chunks, never SALES chunks
            recordingChatModel.reset();
            HttpResponse<String> mgrResp = askChat(hrManagerJwt, query, null);
            assertThat(mgrResp.statusCode()).isEqualTo(200);
            ChatAskResponse mgrBody = objectMapper.readValue(mgrResp.body(), ChatAskResponse.class);
            assertThat(mgrBody.abstained()).isFalse();
            assertThat(mgrBody.citations())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.fileName()).startsWith("hr-"));
            assertThat(recordingChatModel.lastSystemPrompt())
                .contains("CONFIDENTIAL_SECRET_MARKER_")
                .doesNotContain("SALES_ONLY_MARKER_");
        }

        /**
         * RV-03 regression: exercises Java Layer 2a Defense-in-Depth directly over real PostgreSQL
         * when {@code document_chunks.metadata} drifts stale ({@code PUBLIC}) behind {@code documents.access_level}
         * ({@code CONFIDENTIAL}) — ensuring both unauthorized viewers and authorized managers fail closed with 0 LLM calls.
         */
        @Test
        void layer2aJavaDefenseInDepthBlocksUnauthorizedAndDriftedChunksReturnedByRealPgVector() throws Exception {
            UUID driftedDocId = seedIndexedDocument(
                HR_DEPT_ID,
                AccessLevel.PUBLIC,
                DocumentStatus.COMPLETED,
                "hr-drifted-policy.pdf",
                "pdf",
                "application/pdf",
                1,
                null,
                "Chính sách hoàn ứng công tác phí và phụ cấp lưu trú 7 ngày làm việc.",
                "Hoàn ứng công tác phí".getBytes(StandardCharsets.UTF_8)
            );

            String salesEmployeeJwt = loginAndGetAccessToken(SALES_EMPLOYEE_EMAIL);
            String hrEmployeeJwt = loginAndGetAccessToken(HR_EMPLOYEE_EMAIL);
            String hrManagerJwt = loginAndGetAccessToken(HR_MANAGER_EMAIL);
            String question = "Thời hạn hoàn ứng công tác phí là bao nhiêu ngày làm việc?";

            // Simulate trigger-bypassed/stale chunk metadata where SQL Layer 1 still sees PUBLIC while parent DB is CONFIDENTIAL
            jdbcTemplate.execute("ALTER TABLE documents DISABLE TRIGGER trg_propagate_document_rbac_to_chunks");
            try {
                jdbcTemplate.update("UPDATE documents SET access_level = 'CONFIDENTIAL' WHERE id = ?", driftedDocId);

                String chunkAccessLevelInDb = jdbcTemplate.queryForObject(
                    "SELECT metadata->>'access_level' FROM document_chunks WHERE document_id = ?",
                    String.class,
                    driftedDocId
                );
                assertThat(chunkAccessLevelInDb).isEqualTo("PUBLIC");

                // 1. SALES STANDARD_EMPLOYEE -> PgVectorStore returns stale PUBLIC chunk, Layer 2a drops (PARENT_UNAUTHORIZED)
                recordingChatModel.reset();
                HttpResponse<String> salesResp = askChat(salesEmployeeJwt, question, null);
                assertThat(salesResp.statusCode()).isEqualTo(200);
                ChatAskResponse salesBody = objectMapper.readValue(salesResp.body(), ChatAskResponse.class);
                assertThat(salesBody.abstained()).isTrue();
                assertThat(salesBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
                assertThat(salesBody.citations()).isEmpty();
                assertThat(recordingChatModel.callCount()).isZero();

                // 2. HR STANDARD_EMPLOYEE -> PgVectorStore returns stale PUBLIC chunk, Layer 2a drops (PARENT_UNAUTHORIZED)
                HttpResponse<String> hrEmpResp = askChat(hrEmployeeJwt, question, null);
                assertThat(hrEmpResp.statusCode()).isEqualTo(200);
                assertThat(objectMapper.readValue(hrEmpResp.body(), ChatAskResponse.class).abstained()).isTrue();
                assertThat(recordingChatModel.callCount()).isZero();

                // 3. HR DEPARTMENT_MANAGER -> parentAuthorized=true, metadataDrifted=true -> Fail-Closed (CHUNK_PARENT_METADATA_DRIFT)
                HttpResponse<String> hrMgrResp = askChat(hrManagerJwt, question, null);
                assertThat(hrMgrResp.statusCode()).isEqualTo(200);
                ChatAskResponse hrMgrBody = objectMapper.readValue(hrMgrResp.body(), ChatAskResponse.class);
                assertThat(hrMgrBody.abstained()).isTrue();
                assertThat(hrMgrBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
                assertThat(hrMgrBody.citations()).isEmpty();
                assertThat(recordingChatModel.callCount()).isZero();
            } finally {
                jdbcTemplate.execute("ALTER TABLE documents ENABLE TRIGGER trg_propagate_document_rbac_to_chunks");
            }
        }

        /**
         * RV-04 regression: verifies real Spring AOP {@code @EnableMethodSecurity} {@code @PreAuthorize} proxies
         * on {@link RagChatService} and {@link VectorRetrievalService} reject unauthorized authorities before body execution.
         */
        @Test
        void springMethodSecurityProxyRejectsUnauthorizedAuthorityOnRagChatAndVectorRetrievalServices() {
            CallerContext dummyCaller = new CallerContext(HR_EMPLOYEE_USER_ID, HR_DEPT_ID, Role.STANDARD_EMPLOYEE, "127.0.0.1");
            RbacVectorScope dummyScope = new RbacVectorScope(
                dummyCaller,
                null,
                RbacVectorFilterFactory.buildFilterExpression(dummyCaller, null),
                RbacVectorFilterFactory.buildCacheScopeKey(dummyCaller, null)
            );

            for (List<SimpleGrantedAuthority> unauthorizedAuthorities : List.of(
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")),
                List.<SimpleGrantedAuthority>of()
            )) {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("guest-principal", null, unauthorizedAuthorities)
                );

                assertThatThrownBy(() -> ragChatService.ask("Chính sách công ty?", dummyCaller))
                    .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> ragChatService.ask("Chính sách công ty?", null, dummyCaller))
                    .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> vectorRetrievalService.retrieve("Chính sách công ty?", dummyCaller))
                    .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> vectorRetrievalService.retrieve("Chính sách công ty?", dummyCaller, null))
                    .isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(() -> vectorRetrievalService.retrieve("Chính sách công ty?", dummyScope))
                    .isInstanceOf(AccessDeniedException.class);
            }

            assertThat(recordingChatModel.callCount()).isZero();
        }
    }

    static Stream<Arguments> mandatoryRbacTruthMatrix() {
        String qHrPublic = "Văn hóa doanh nghiệp và giá trị cốt lõi của công ty là gì?";
        String qHrDept = "Gói bảo hiểm sức khỏe PVI Care có hạn mức nội trú bao nhiêu?";
        String qHrConfidential = "Quy định thử việc 60 ngày và bậc lương mật của HR là gì?";
        String qSalesDept = "Ngày chi trả hoa hồng doanh số hàng tháng của phòng Sales là ngày nào?";

        return Stream.of(
            // SYSTEM_ADMIN (IT): full visibility across all 4 documents
            Arguments.of(ADMIN_EMAIL, "hr-public-culture.md", qHrPublic, true),
            Arguments.of(ADMIN_EMAIL, "hr-dept-pvi.pdf", qHrDept, true),
            Arguments.of(ADMIN_EMAIL, "hr-confidential-probation.pdf", qHrConfidential, true),
            Arguments.of(ADMIN_EMAIL, "sales-dept-commission.docx", qSalesDept, true),

            // DEPARTMENT_MANAGER (HR): PUBLIC + HR DEPARTMENT + HR CONFIDENTIAL; hidden SALES DEPARTMENT
            Arguments.of(HR_MANAGER_EMAIL, "hr-public-culture.md", qHrPublic, true),
            Arguments.of(HR_MANAGER_EMAIL, "hr-dept-pvi.pdf", qHrDept, true),
            Arguments.of(HR_MANAGER_EMAIL, "hr-confidential-probation.pdf", qHrConfidential, true),
            Arguments.of(HR_MANAGER_EMAIL, "sales-dept-commission.docx", qSalesDept, false),

            // STANDARD_EMPLOYEE (HR): PUBLIC + HR DEPARTMENT; NEVER HR CONFIDENTIAL or SALES DEPARTMENT
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-public-culture.md", qHrPublic, true),
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-dept-pvi.pdf", qHrDept, true),
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-confidential-probation.pdf", qHrConfidential, false),
            Arguments.of(HR_EMPLOYEE_EMAIL, "sales-dept-commission.docx", qSalesDept, false),

            // DEPARTMENT_MANAGER (SALES): PUBLIC + SALES DEPARTMENT; hidden HR DEPARTMENT & HR CONFIDENTIAL
            Arguments.of(SALES_MANAGER_EMAIL, "hr-public-culture.md", qHrPublic, true),
            Arguments.of(SALES_MANAGER_EMAIL, "hr-dept-pvi.pdf", qHrDept, false),
            Arguments.of(SALES_MANAGER_EMAIL, "hr-confidential-probation.pdf", qHrConfidential, false),
            Arguments.of(SALES_MANAGER_EMAIL, "sales-dept-commission.docx", qSalesDept, true),

            // STANDARD_EMPLOYEE (SALES): PUBLIC + SALES DEPARTMENT; hidden HR DEPARTMENT & HR CONFIDENTIAL
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-public-culture.md", qHrPublic, true),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-dept-pvi.pdf", qHrDept, false),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-confidential-probation.pdf", qHrConfidential, false),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "sales-dept-commission.docx", qSalesDept, true)
        );
    }

    private void seedFourDocumentMatrix() throws Exception {
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.PUBLIC,
            DocumentStatus.COMPLETED,
            "hr-public-culture.md",
            "md",
            "text/markdown",
            null,
            "Giá trị cốt lõi",
            "Văn hóa doanh nghiệp và giá trị cốt lõi DocuMind AI đề cao sự minh bạch và bảo mật.",
            "Văn hóa doanh nghiệp".getBytes(StandardCharsets.UTF_8)
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            DocumentStatus.COMPLETED,
            "hr-dept-pvi.pdf",
            "pdf",
            "application/pdf",
            3,
            null,
            "Gói bảo hiểm sức khỏe PVI Care dành cho nội bộ HR có hạn mức nội trú 200 triệu VNĐ.",
            "PVI Care HR".getBytes(StandardCharsets.UTF_8)
        );
        seedIndexedDocument(
            HR_DEPT_ID,
            AccessLevel.CONFIDENTIAL,
            DocumentStatus.COMPLETED,
            "hr-confidential-probation.pdf",
            "pdf",
            "application/pdf",
            1,
            null,
            "Tài liệu mật HR: thời gian thử việc 60 ngày và khung lương thưởng điều hành.",
            "HR Confidential".getBytes(StandardCharsets.UTF_8)
        );
        seedIndexedDocument(
            SALES_DEPT_ID,
            AccessLevel.DEPARTMENT,
            DocumentStatus.COMPLETED,
            "sales-dept-commission.docx",
            "docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            null,
            "Hoa hồng",
            "Chính sách hoa hồng doanh số phòng Sales được chi trả vào ngày 15 hàng tháng.",
            "Sales Commission".getBytes(StandardCharsets.UTF_8)
        );
    }

    private UUID seedIndexedDocument(
        UUID departmentId,
        AccessLevel accessLevel,
        DocumentStatus status,
        String originalName,
        String extension,
        String mimeType,
        Integer pageNumber,
        String sectionHeading,
        String chunkText,
        byte[] storageBytes
    ) throws Exception {
        DepartmentEntity dept = departmentRepository.findById(departmentId).orElseThrow();
        UUID docId = UUID.randomUUID();
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
        entity.setStatus(status);
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
        assertThat(resp.statusCode())
            .as("Login must succeed for %s (body=%s)", email, resp.body())
            .isEqualTo(200);
        AuthTokenResponse tokenResponse = objectMapper.readValue(resp.body(), AuthTokenResponse.class);
        return Objects.requireNonNull(tokenResponse.accessToken());
    }

    private HttpResponse<String> askChat(String bearerJwt, String question, UUID departmentId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        if (departmentId != null) {
            payload.put("departmentId", departmentId.toString());
        }
        HttpRequest req = HttpRequest.newBuilder(uri("/api/v1/chat/ask"))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerJwt)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
