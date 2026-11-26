package com.documind.integration;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentPreviewUrlResponse;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.controller.dto.chat.ChatMessageResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.audit.AuditLogService;
import com.documind.service.chat.ChatSessionService;
import com.documind.service.rag.RagConstants;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.AbstractIntegrationTest.RecordingTestChatModel;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.documind.support.Week2SseTestClient;
import com.documind.support.Week2SseTestClient.SseFrame;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * Milestone 2 ({@code DA-18}) Morning Suite: End-to-end Testcontainers security verification over the real
 * asynchronous ingestion pipeline ({@code worker.enabled=true}) and fail-closed Bearer JWT authentication
 * ({@code dev-caller-fallback=false}), proving zero cross-department leakage simultaneously across all 3 gates:
 * Document API, Vector Search Chat ({@code /ask}, {@code /stream}, {@code /sessions}), and Presigned Preview URL.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true",
        "documind.security.dev-caller-fallback.enabled=false",
        "documind.rate-limit.chat.viewer-limit=500",
        "documind.rate-limit.chat.manager-limit=500",
        "documind.rate-limit.chat.admin-limit=500",
        "documind.rate-limit.upload.manager-limit=200",
        "documind.rate-limit.upload.admin-limit=200"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
class Week2CrossGateRbacIntegrationTest {

    private static final UUID IT_DEPT_ID = AbstractIntegrationTest.IT_DEPT_ID;
    private static final UUID HR_DEPT_ID = AbstractIntegrationTest.HR_DEPT_ID;
    private static final UUID SALES_DEPT_ID = AbstractIntegrationTest.SALES_DEPT_ID;
    private static final UUID ADMIN_USER_ID = AbstractIntegrationTest.ADMIN_USER_ID;
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
    private RecordingTestChatModel recordingChatModel;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private void cleanUpTestState() throws InterruptedException {
        if (auditLogService != null) {
            auditLogService.awaitPendingWrites(Duration.ofSeconds(5));
        }
        AbstractIntegrationTest.cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            recordingChatModel,
            Duration.ofSeconds(15)
        );
    }

    private String queryStorageKey(UUID documentId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_key FROM documents WHERE id = ?",
            String.class,
            documentId
        );
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UnifiedThreeGateReadMatrixTests {

        private Week2SseTestClient client;
        private final Map<String, String> jwtByEmail = new HashMap<>();
        private final Map<CorpusDocKey, IngestedDocFixture> corpusByKey = new LinkedHashMap<>();

        @BeforeAll
        void ingestRealMultiFormatCorpusAndAuthenticateAllActors() throws Exception {
            cleanUpTestState();
            AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
            client = new Week2SseTestClient(objectMapper);

            jwtByEmail.clear();
            jwtByEmail.putAll(client.loginAllSevenActors(port));

            String adminJwt = jwtByEmail.get(ADMIN_EMAIL);
            corpusByKey.clear();

            // Each of the 9 (Department x AccessLevel) documents maps to a distinct orthogonal axis in SemanticCorpusTestEmbeddingModel
            corpusByKey.put(
                CorpusDocKey.HR_PUBLIC,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.HR_PUBLIC,
                    "hr-public-hybrid.txt",
                    "text/plain",
                    ("Chính sách làm việc từ xa hybrid work và khung giờ phối hợp 09:30 toàn công ty. "
                        + "Nhân viên được phép làm việc từ xa tối đa 2 ngày mỗi tuần. HR_PUBLIC_HYBRID_MARKER")
                        .getBytes(StandardCharsets.UTF_8),
                    HR_DEPT_ID,
                    AccessLevel.PUBLIC,
                    false,
                    null,
                    "Quy định làm việc từ xa hybrid work và khung giờ phối hợp 09:30 như thế nào?",
                    "HR_PUBLIC_HYBRID_MARKER"
                )
            );

            corpusByKey.put(
                CorpusDocKey.HR_DEPT,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.HR_DEPT,
                    "hr-dept-leave-2026.pdf",
                    "application/pdf",
                    SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                        List.of("Sổ tay nội bộ phòng Hành chính Nhân sự — Trang giới thiệu tổng quan."),
                        List.of("Chính sách nghỉ phép năm nội bộ HR: nhân viên chính thức được hưởng 14 ngày phép năm "
                            + "và cộng thêm 1 ngày mỗi 5 năm thâm niên. HR_DEPT_LEAVE_SECRET")
                    )),
                    HR_DEPT_ID,
                    AccessLevel.DEPARTMENT,
                    true,
                    2,
                    "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm theo chính sách HR?",
                    "HR_DEPT_LEAVE_SECRET"
                )
            );

            corpusByKey.put(
                CorpusDocKey.HR_CONFIDENTIAL,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.HR_CONFIDENTIAL,
                    "hr-confidential-pvi-exec.pdf",
                    "application/pdf",
                    SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                        List.of("Tài liệu mật phòng Hành chính Nhân sự — Trang bìa phân loại CONFIDENTIAL."),
                        List.of("Gói bảo hiểm sức khỏe PVI Care dành riêng cấp lãnh đạo có hạn mức nội trú VIP 500 triệu VNĐ. "
                            + "HR_CONFIDENTIAL_VIP_SECRET")
                    )),
                    HR_DEPT_ID,
                    AccessLevel.CONFIDENTIAL,
                    true,
                    2,
                    "Hạn mức nội trú gói bảo hiểm sức khỏe PVI Care cấp lãnh đạo là bao nhiêu?",
                    "HR_CONFIDENTIAL_VIP_SECRET"
                )
            );

            corpusByKey.put(
                CorpusDocKey.IT_PUBLIC,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.IT_PUBLIC,
                    "it-public-passphrase.md",
                    "text/markdown",
                    ("# Company Passphrase & FIDO2 MFA Standard\n\n"
                        + "Every employee account requires a 14 characters passphrase enrolled with FIDO2 WebAuthn MFA. "
                        + "IT_PUBLIC_MFA_MARKER\n")
                        .getBytes(StandardCharsets.UTF_8),
                    IT_DEPT_ID,
                    AccessLevel.PUBLIC,
                    false,
                    null,
                    "What are the company passphrase length and FIDO2 WebAuthn MFA requirements?",
                    "IT_PUBLIC_MFA_MARKER"
                )
            );

            corpusByKey.put(
                CorpusDocKey.IT_DEPT,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.IT_DEPT,
                    "it-dept-vpn-guide.md",
                    "text/markdown",
                    ("# WireGuard VPN Operations Standard\n\n"
                        + "All engineers must configure WireGuard VPN tunnels and enable mandatory screen lock timeout. "
                        + "IT_DEPT_VPN_SECRET\n")
                        .getBytes(StandardCharsets.UTF_8),
                    IT_DEPT_ID,
                    AccessLevel.DEPARTMENT,
                    false,
                    null,
                    "How should IT engineers configure WireGuard VPN and mandatory screen lock?",
                    "IT_DEPT_VPN_SECRET"
                )
            );

            corpusByKey.put(
                CorpusDocKey.IT_CONFIDENTIAL,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.IT_CONFIDENTIAL,
                    "it-confidential-canary-runbook.pdf",
                    "application/pdf",
                    SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                        List.of("CONFIDENTIAL IT SRE Operations Header."),
                        List.of("Progressive canary rollout shifts traffic percentages 10% -> 50% -> 100% after soak checks. "
                            + "IT_CONFIDENTIAL_CANARY_SECRET")
                    )),
                    IT_DEPT_ID,
                    AccessLevel.CONFIDENTIAL,
                    true,
                    2,
                    "What traffic percentages are used during the canary rollout soak stage?",
                    "IT_CONFIDENTIAL_CANARY_SECRET"
                )
            );

            corpusByKey.put(
                CorpusDocKey.SALES_PUBLIC,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.SALES_PUBLIC,
                    "sales-public-culture.txt",
                    "text/plain",
                    ("Sổ tay văn hóa doanh nghiệp và 4 giá trị cốt lõi định hướng khách hàng toàn công ty. "
                        + "SALES_PUBLIC_CULTURE_MARKER")
                        .getBytes(StandardCharsets.UTF_8),
                    SALES_DEPT_ID,
                    AccessLevel.PUBLIC,
                    false,
                    null,
                    "Tài liệu văn hóa doanh nghiệp quy định những giá trị cốt lõi nào?",
                    "SALES_PUBLIC_CULTURE_MARKER"
                )
            );

            corpusByKey.put(
                CorpusDocKey.SALES_DEPT,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.SALES_DEPT,
                    "sales-dept-commission.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    SampleEnterpriseCorpusFactory.createStructuredDocx(List.of(
                        new SampleEnterpriseCorpusFactory.DocxBlock("Heading1", "Quy chế hoa hồng phòng Kinh doanh"),
                        new SampleEnterpriseCorpusFactory.DocxBlock(
                            null,
                            "Chu kỳ đối soát và chi trả hoa hồng doanh số của phòng Sales thực hiện vào ngày 15 đầu quý. "
                                + "SALES_DEPT_COMMISSION_SECRET"
                        )
                    )),
                    SALES_DEPT_ID,
                    AccessLevel.DEPARTMENT,
                    false,
                    null,
                    "Ngày chi trả hoa hồng doanh số hàng quý của phòng Sales là ngày nào?",
                    "SALES_DEPT_COMMISSION_SECRET"
                )
            );

            corpusByKey.put(
                CorpusDocKey.SALES_CONFIDENTIAL,
                ingestFixture(
                    adminJwt,
                    CorpusDocKey.SALES_CONFIDENTIAL,
                    "sales-confidential-hotel-cap.pdf",
                    "application/pdf",
                    SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                        List.of("Tài liệu mật phòng Kinh doanh — Hạn mức phê duyệt ban giám đốc Sales."),
                        List.of("Định mức khách sạn và công tác phí cấp quản lý Sales áp dụng trần 1.500.000 VNĐ/đêm "
                            + "cho lưu trú nội địa đặc biệt. SALES_CONFIDENTIAL_HOTEL_SECRET")
                    )),
                    SALES_DEPT_ID,
                    AccessLevel.CONFIDENTIAL,
                    true,
                    2,
                    "Hạn mức khách sạn công tác phí lưu trú nội địa 1.500.000 của cấp quản lý Sales là bao nhiêu?",
                    "SALES_CONFIDENTIAL_HOTEL_SECRET"
                )
            );
        }

        @AfterAll
        void cleanupReadMatrix() throws InterruptedException {
            if (client != null) {
                client.close();
            }
            jwtByEmail.clear();
            corpusByKey.clear();
            cleanUpTestState();
        }

        private IngestedDocFixture ingestFixture(
            String adminJwt,
            CorpusDocKey key,
            String filename,
            String contentType,
            byte[] rawBytes,
            UUID departmentId,
            AccessLevel accessLevel,
            boolean isPdf,
            Integer expectedPage,
            String query,
            String secretMarker
        ) throws Exception {
            DocumentResponse doc = client.uploadAndAwaitCompleted(
                port,
                filename,
                contentType,
                rawBytes,
                accessLevel,
                departmentId,
                adminJwt
            );

            // Verify V7 metadata invariants: all 6 mandatory keys present, page_number omitted on non-PDFs, 0 JSON null values
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT metadata->>'document_id' AS document_id,
                       metadata->>'department_id' AS department_id,
                       metadata->>'file_name' AS file_name,
                       metadata->>'access_level' AS access_level,
                       metadata->>'doc_version' AS doc_version,
                       (metadata->>'chunk_index')::int AS chunk_index,
                       jsonb_exists(metadata, 'page_number') AS has_page_key,
                       CASE WHEN jsonb_exists(metadata, 'page_number')
                            THEN (metadata->>'page_number')::int ELSE NULL END AS page_number,
                       EXISTS (SELECT 1 FROM jsonb_each(metadata) WHERE value = 'null'::jsonb) AS has_json_null
                FROM document_chunks
                WHERE document_id = ?
                """,
                doc.id()
            );
            assertThat(rows).isNotEmpty();
            for (Map<String, Object> row : rows) {
                assertThat(row.get("document_id")).isEqualTo(doc.id().toString());
                assertThat(row.get("department_id")).isEqualTo(departmentId.toString());
                assertThat(row.get("file_name")).isEqualTo(filename);
                assertThat(row.get("access_level")).isEqualTo(accessLevel.name());
                assertThat(row.get("doc_version")).isEqualTo("1");
                assertThat((Integer) row.get("chunk_index")).isGreaterThanOrEqualTo(0);
                assertThat(row.get("has_page_key")).isEqualTo(isPdf);
                if (isPdf) {
                    assertThat((Integer) row.get("page_number")).isGreaterThanOrEqualTo(1);
                }
                assertThat(row.get("has_json_null")).isEqualTo(false);
            }

            return new IngestedDocFixture(
                key,
                doc,
                queryStorageKey(doc.id()),
                rawBytes,
                isPdf,
                expectedPage,
                query,
                secretMarker
            );
        }

        @ParameterizedTest(name = "{0} ({1} in {2}) verified identically across all 3 gates")
        @MethodSource("com.documind.integration.Week2CrossGateRbacIntegrationTest#allSevenActorsReadMatrix")
        void enforcesZeroCrossDepartmentLeakageSynchronouslyAcrossAllThreeGates(
            String actorEmail,
            Role role,
            String deptCode,
            Set<CorpusDocKey> expectedReadableKeys
        ) throws Exception {
            String jwt = Objects.requireNonNull(jwtByEmail.get(actorEmail), "Missing JWT for " + actorEmail);

            HttpResponse<String> meResp = client.sendJson(port, "GET", "/api/v1/auth/me", null, jwt);
            assertThat(meResp.statusCode()).isEqualTo(200);
            JsonNode meJson = objectMapper.readTree(meResp.body());
            assertThat(meJson.path("role").asText()).isEqualTo(role.name());
            assertThat(meJson.path("departmentCode").asText()).isEqualTo(deptCode);

            Set<UUID> expectedVisibleIds = expectedReadableKeys.stream()
                .map(k -> corpusByKey.get(k).doc().id())
                .collect(Collectors.toSet());

            HttpResponse<String> listResp = client.sendJson(port, "GET", "/api/v1/documents?page=0&size=50", null, jwt);
            assertThat(listResp.statusCode()).isEqualTo(200);
            PageResponse<DocumentResponse> visiblePage = objectMapper.readValue(
                listResp.body(),
                new TypeReference<>() {}
            );
            Set<UUID> actualVisibleIds = visiblePage.items().stream()
                .map(DocumentResponse::id)
                .collect(Collectors.toSet());
            assertThat(actualVisibleIds).containsExactlyInAnyOrderElementsOf(expectedVisibleIds);

            for (UUID probedDeptId : List.of(IT_DEPT_ID, HR_DEPT_ID, SALES_DEPT_ID)) {
                for (AccessLevel probedLevel : Arrays.asList(null, AccessLevel.PUBLIC, AccessLevel.DEPARTMENT, AccessLevel.CONFIDENTIAL)) {
                    String path = "/api/v1/documents?departmentId=" + probedDeptId + "&page=0&size=50"
                        + (probedLevel != null ? "&accessLevel=" + probedLevel.name() : "");
                    HttpResponse<String> probedResp = client.sendJson(port, "GET", path, null, jwt);
                    assertThat(probedResp.statusCode()).isEqualTo(200);
                    PageResponse<DocumentResponse> probedPage = objectMapper.readValue(
                        probedResp.body(),
                        new TypeReference<>() {}
                    );
                    Set<UUID> probedIds = probedPage.items().stream()
                        .map(DocumentResponse::id)
                        .collect(Collectors.toSet());
                    Set<UUID> expectedSubset = expectedReadableKeys.stream()
                        .map(corpusByKey::get)
                        .filter(f -> f.doc().departmentId().equals(probedDeptId)
                            && (probedLevel == null || f.doc().accessLevel() == probedLevel))
                        .map(f -> f.doc().id())
                        .collect(Collectors.toSet());
                    assertThat(probedIds)
                        .as("Actor %s probing dept=%s level=%s", actorEmail, probedDeptId, probedLevel)
                        .containsExactlyInAnyOrderElementsOf(expectedSubset);
                }
            }

            for (IngestedDocFixture fixture : corpusByKey.values()) {
                boolean readable = expectedReadableKeys.contains(fixture.key());
                UUID docId = fixture.doc().id();

                HttpResponse<String> getDocResp = client.sendJson(port, "GET", "/api/v1/documents/" + docId, null, jwt);
                HttpResponse<String> statusResp = client.sendJson(port, "GET", "/api/v1/documents/" + docId + "/status", null, jwt);
                if (readable) {
                    assertThat(getDocResp.statusCode()).isEqualTo(200);
                    assertThat(statusResp.statusCode()).isEqualTo(200);
                } else {
                    assertForbiddenWithoutLeakage(getDocResp, fixture);
                    assertForbiddenWithoutLeakage(statusResp, fixture);
                }

                recordingChatModel.reset();
                HttpResponse<String> askResp = client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/ask",
                    Map.of("question", fixture.query()),
                    jwt
                );
                assertThat(askResp.statusCode()).isEqualTo(200);
                ChatAskResponse askBody = objectMapper.readValue(askResp.body(), ChatAskResponse.class);
                if (readable) {
                    assertThat(askBody.abstained())
                        .as("Expected grounded /ask for %s on %s", actorEmail, fixture.doc().originalName())
                        .isFalse();
                    assertThat(askBody.citations())
                        .isNotEmpty()
                        .extracting(CitationDto::fileName)
                        .contains(fixture.doc().originalName());
                    CitationDto matched = askBody.citations().stream()
                        .filter(c -> c.documentId().equals(docId))
                        .findFirst()
                        .orElseThrow();
                    if (fixture.isPdf()) {
                        assertThat(matched.pageNumber()).isEqualTo(fixture.expectedPage());
                        assertThat(matched.presignedUrl()).endsWith("#page=" + fixture.expectedPage());
                    } else {
                        assertThat(matched.pageNumber()).isNull();
                        assertThat(matched.presignedUrl()).doesNotContain("#page=");
                    }
                    assertThat(recordingChatModel.callCount()).isEqualTo(1);
                } else {
                    assertThat(askBody.abstained()).isTrue();
                    assertThat(askBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
                    assertThat(askBody.citations()).isEmpty();
                    assertThat(recordingChatModel.callCount()).isZero();
                    assertThat(askResp.body())
                        .doesNotContain(fixture.secretMarker())
                        .doesNotContain(fixture.doc().originalName());
                }

                // Flush L1/L2 cache between /ask and /stream so /stream still verifies live RBAC vector retrieval
                AbstractIntegrationTest.flushRedis(stringRedisTemplate);
                jdbcTemplate.update("DELETE FROM semantic_cache");

                recordingChatModel.reset();
                List<SseFrame> sseFrames = client.collectAllSseFrames(
                    port,
                    Map.of("question", fixture.query()),
                    jwt
                );
                if (readable) {
                    assertThat(sseFrames.size()).isGreaterThanOrEqualTo(3);
                    assertThat(sseFrames.getFirst().event()).isEqualTo("citations");
                    JsonNode citationsArray = objectMapper.readTree(sseFrames.getFirst().data()).path("citations");
                    assertThat(citationsArray.size()).isGreaterThan(0);
                    boolean citesExpectedDoc = false;
                    for (JsonNode node : citationsArray) {
                        if (docId.toString().equals(node.path("documentId").asText())) {
                            citesExpectedDoc = true;
                            assertThat(node.path("fileName").asText()).isEqualTo(fixture.doc().originalName());
                            if (fixture.isPdf()) {
                                assertThat(node.path("pageNumber").asInt()).isEqualTo(fixture.expectedPage());
                                assertThat(node.path("presignedUrl").asText()).endsWith("#page=" + fixture.expectedPage());
                            } else {
                                assertThat(node.path("pageNumber").isNull()).isTrue();
                            }
                        }
                    }
                    assertThat(citesExpectedDoc).isTrue();
                    assertThat(sseFrames.getLast().event()).isEqualTo("done");
                    JsonNode sseDone = objectMapper.readTree(sseFrames.getLast().data());
                    assertThat(sseDone.path("abstained").asBoolean()).isFalse();
                    assertThat(sseDone.path("cacheHit").asBoolean()).isFalse();
                    assertThat(sseDone.path("cacheTier").asText()).isEqualTo("NONE");
                    assertThat(recordingChatModel.callCount()).isEqualTo(1);

                    // Repeat on same scope immediately hits caller-scoped L1 cache with 0 LLM calls
                    recordingChatModel.reset();
                    List<SseFrame> l1RepeatFrames = client.collectAllSseFrames(
                        port,
                        Map.of("question", fixture.query()),
                        jwt
                    );
                    JsonNode repeatDone = objectMapper.readTree(l1RepeatFrames.getLast().data());
                    assertThat(repeatDone.path("abstained").asBoolean()).isFalse();
                    assertThat(repeatDone.path("cacheHit").asBoolean()).isTrue();
                    assertThat(repeatDone.path("cacheTier").asText()).isEqualTo("L1");
                    assertThat(recordingChatModel.callCount()).isZero();
                } else {
                    client.assertDeterministicSseRefusal(
                        sseFrames,
                        fixture.secretMarker(),
                        fixture.doc().originalName(),
                        true
                    );
                    assertThat(recordingChatModel.callCount()).isZero();
                }

                HttpResponse<String> previewResp = client.sendJson(
                    port,
                    "GET",
                    "/api/v1/documents/" + docId + "/preview-url?page=2",
                    null,
                    jwt
                );
                if (readable) {
                    assertThat(previewResp.statusCode()).isEqualTo(200);
                    DocumentPreviewUrlResponse preview = objectMapper.readValue(
                        previewResp.body(),
                        DocumentPreviewUrlResponse.class
                    );
                    assertThat(preview.documentId()).isEqualTo(docId);
                    assertThat(preview.originalName()).isEqualTo(fixture.doc().originalName());
                    assertThat(preview.currentVersion()).isEqualTo(1);
                    assertThat(preview.expiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));
                    if (fixture.isPdf()) {
                        assertThat(preview.pageNumber()).isEqualTo(2);
                        assertThat(preview.previewUrl()).endsWith("#page=2");
                    } else {
                        assertThat(preview.pageNumber()).isNull();
                        assertThat(preview.previewUrl()).doesNotContain("#page=");
                    }

                    HttpResponse<byte[]> minioFetch = client.fetchRawBytes(
                        URI.create(Week2SseTestClient.stripFragment(preview.previewUrl()))
                    );
                    assertThat(minioFetch.statusCode()).isEqualTo(200);
                    assertThat(minioFetch.headers().firstValue("Content-Disposition"))
                        .isPresent()
                        .get()
                        .asString()
                        .startsWith("inline;");
                    assertThat(minioFetch.body()).isEqualTo(fixture.rawBytes());
                } else {
                    assertForbiddenWithoutLeakage(previewResp, fixture);
                }
            }
        }

        @Test
        void blocksParameterAndHeaderSpoofingAcrossAllThreeGatesAndEnforcesAdminScopedSse() throws Exception {
            String adminJwt = jwtByEmail.get(ADMIN_EMAIL);
            String hrManagerJwt = jwtByEmail.get(HR_MANAGER_EMAIL);
            String salesEmpJwt = jwtByEmail.get(SALES_EMPLOYEE_EMAIL);
            IngestedDocFixture hrConf = corpusByKey.get(CorpusDocKey.HR_CONFIDENTIAL);
            IngestedDocFixture salesDept = corpusByKey.get(CorpusDocKey.SALES_DEPT);

            recordingChatModel.reset();
            for (String nonAdminJwt : List.of(hrManagerJwt, salesEmpJwt)) {
                HttpResponse<String> askSpoof = client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/ask",
                    Map.of("question", hrConf.query(), "departmentId", HR_DEPT_ID.toString()),
                    nonAdminJwt
                );
                assertThat(askSpoof.statusCode()).isEqualTo(403);
                assertThat(objectMapper.readTree(askSpoof.body()).path("errorCode").asText())
                    .isEqualTo("DOCUMENT_ACCESS_DENIED");

                HttpResponse<String> streamSpoof = client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/stream",
                    Map.of("question", hrConf.query(), "departmentId", IT_DEPT_ID.toString()),
                    nonAdminJwt
                );
                assertThat(streamSpoof.statusCode()).isEqualTo(403);
                assertThat(objectMapper.readTree(streamSpoof.body()).path("errorCode").asText())
                    .isEqualTo("DOCUMENT_ACCESS_DENIED");
            }
            assertThat(recordingChatModel.callCount()).isZero();

            recordingChatModel.reset();
            List<SseFrame> adminSalesOk = client.collectAllSseFrames(
                port,
                Map.of("question", salesDept.query(), "departmentId", SALES_DEPT_ID.toString()),
                adminJwt
            );
            assertThat(objectMapper.readTree(adminSalesOk.getLast().data()).path("abstained").asBoolean()).isFalse();
            assertThat(recordingChatModel.callCount()).isEqualTo(1);

            recordingChatModel.reset();
            List<SseFrame> adminSalesBlocksHrConf = client.collectAllSseFrames(
                port,
                Map.of("question", hrConf.query(), "departmentId", SALES_DEPT_ID.toString()),
                adminJwt
            );
            client.assertDeterministicSseRefusal(
                adminSalesBlocksHrConf,
                hrConf.secretMarker(),
                hrConf.doc().originalName(),
                true
            );
            assertThat(recordingChatModel.callCount()).isZero();

            Map<String, String> spoofedHeaders = Map.of(
                "X-User-Role", "SYSTEM_ADMIN",
                "X-Department-Id", HR_DEPT_ID.toString(),
                "X-User-Id", ADMIN_USER_ID.toString()
            );
            List<HttpResponse<String>> spoofResponses = List.of(
                client.sendJsonWithHeaders(port, "GET", "/api/v1/documents", null, spoofedHeaders),
                client.sendJsonWithHeaders(port, "GET", "/api/v1/documents/" + hrConf.doc().id(), null, spoofedHeaders),
                client.sendJsonWithHeaders(port, "GET", "/api/v1/documents/" + hrConf.doc().id() + "/preview-url", null, spoofedHeaders),
                client.sendJsonWithHeaders(port, "POST", "/api/v1/chat/ask", Map.of("question", hrConf.query()), spoofedHeaders),
                client.sendJsonWithHeaders(port, "POST", "/api/v1/chat/stream", Map.of("question", hrConf.query()), spoofedHeaders)
            );
            for (HttpResponse<String> resp : spoofResponses) {
                assertThat(resp.statusCode()).isEqualTo(401);
                assertThat(resp.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse("")).contains("Bearer");
                assertThat(objectMapper.readTree(resp.body()).path("errorCode").asText())
                    .isEqualTo("AUTHENTICATION_REQUIRED");
            }
            assertThat(recordingChatModel.callCount()).isZero();
        }

        @Test
        void milestone2CoreBackendOpenApiAndHealthContractsAreComplete() throws Exception {
            HttpResponse<String> healthResp = client.sendJson(port, "GET", "/actuator/health", null, null);
            assertThat(healthResp.statusCode()).isEqualTo(200);
            JsonNode health = objectMapper.readTree(healthResp.body());
            assertThat(health.path("status").asText()).isEqualTo("UP");
            assertThat(health.path("components").path("db").path("status").asText()).isEqualTo("UP");
            assertThat(health.path("components").path("redis").path("status").asText()).isEqualTo("UP");
            assertThat(health.path("components").path("minio").path("status").asText()).isEqualTo("UP");

            HttpResponse<String> openApiResp = client.sendJson(port, "GET", "/v3/api-docs", null, null);
            assertThat(openApiResp.statusCode()).isEqualTo(200);
            JsonNode spec = objectMapper.readTree(openApiResp.body());

            JsonNode bearerScheme = spec.path("components").path("securitySchemes").path("bearerAuth");
            assertThat(bearerScheme.path("type").asText()).isEqualTo("http");
            assertThat(bearerScheme.path("scheme").asText()).isEqualTo("bearer");
            assertThat(bearerScheme.path("bearerFormat").asText()).isEqualTo("JWT");

            JsonNode paths = spec.path("paths");
            Map<String, List<String>> requiredMilestone2Operations = new LinkedHashMap<>();
            requiredMilestone2Operations.put("/api/v1/auth/login", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/auth/refresh", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/auth/logout", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/auth/me", List.of("get"));
            requiredMilestone2Operations.put("/api/v1/admin/departments", List.of("get", "post"));
            requiredMilestone2Operations.put("/api/v1/admin/departments/{id}", List.of("get", "patch", "delete"));
            requiredMilestone2Operations.put("/api/v1/admin/users", List.of("get", "post"));
            requiredMilestone2Operations.put("/api/v1/admin/users/{id}", List.of("get", "patch"));
            requiredMilestone2Operations.put("/api/v1/admin/users/{id}/password", List.of("put"));
            requiredMilestone2Operations.put("/api/v1/documents/upload", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/documents", List.of("get"));
            requiredMilestone2Operations.put("/api/v1/documents/{id}", List.of("get", "put", "patch", "delete"));
            requiredMilestone2Operations.put("/api/v1/documents/{id}/status", List.of("get"));
            requiredMilestone2Operations.put("/api/v1/documents/{id}/preview-url", List.of("get"));
            requiredMilestone2Operations.put("/api/v1/chat/ask", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/chat/stream", List.of("post"));
            requiredMilestone2Operations.put("/api/v1/chat/sessions", List.of("get", "post"));
            requiredMilestone2Operations.put("/api/v1/chat/sessions/{id}", List.of("get", "patch", "delete"));
            requiredMilestone2Operations.put("/api/v1/chat/sessions/{id}/messages", List.of("get"));

            for (Map.Entry<String, List<String>> entry : requiredMilestone2Operations.entrySet()) {
                JsonNode pathItem = paths.path(entry.getKey());
                assertThat(paths.has(entry.getKey()))
                    .as("Expected Milestone 2 OpenAPI path %s to be registered", entry.getKey())
                    .isTrue();
                for (String method : entry.getValue()) {
                    assertThat(pathItem.has(method))
                        .as("Expected HTTP %s on OpenAPI path %s", method, entry.getKey())
                        .isTrue();
                }
            }
        }

        private void assertForbiddenWithoutLeakage(HttpResponse<String> resp, IngestedDocFixture fixture) throws Exception {
            assertThat(resp.statusCode()).isEqualTo(403);
            JsonNode problem = objectMapper.readTree(resp.body());
            assertThat(problem.path("errorCode").asText()).isIn("DOCUMENT_ACCESS_DENIED", "ACCESS_DENIED");
            assertThat(resp.body())
                .doesNotContain(fixture.doc().departmentId().toString())
                .doesNotContain(fixture.storageKey())
                .doesNotContain("X-Amz-Signature");
        }
    }

    @Nested
    class DynamicCrossGateTransitionsAndWriteRbacTests {

        private Week2SseTestClient client;

        @BeforeEach
        void setUp() throws InterruptedException {
            cleanUpTestState();
            AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
            client = new Week2SseTestClient(objectMapper);
        }

        @AfterEach
        void tearDown() throws InterruptedException {
            if (client != null) {
                client.close();
            }
            cleanUpTestState();
        }

        @Test
        void departmentTransferRevokesOldJwtAcrossAllThreeGatesAndRedactsSessionCarryover() throws Exception {
            String adminJwt = client.login(port, ADMIN_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String hrManagerJwt = client.login(port, HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String oldHrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

            byte[] hrDeptPdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                List.of("Chính sách nghỉ phép năm nội bộ HR: nhân viên chính thức được 14 ngày phép. HR_TRANSFER_SECRET_14DAYS")
            ));
            DocumentResponse hrDeptDoc = client.uploadAndAwaitCompleted(
                port,
                "hr-transfer-leave.pdf",
                "application/pdf",
                hrDeptPdf,
                AccessLevel.DEPARTMENT,
                HR_DEPT_ID,
                hrManagerJwt
            );

            DocumentResponse hrPublicDoc = client.uploadAndAwaitCompleted(
                port,
                "company-hybrid-public.txt",
                "text/plain",
                "Quy định làm việc từ xa hybrid work khung giờ phối hợp 09:30 toàn công ty.".getBytes(StandardCharsets.UTF_8),
                AccessLevel.PUBLIC,
                HR_DEPT_ID,
                hrManagerJwt
            );

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + hrDeptDoc.id(), null, oldHrEmployeeJwt).statusCode())
                .isEqualTo(200);
            HttpResponse<String> preTransferPreviewResp = client.sendJson(
                port,
                "GET",
                "/api/v1/documents/" + hrDeptDoc.id() + "/preview-url?page=1",
                null,
                oldHrEmployeeJwt
            );
            assertThat(preTransferPreviewResp.statusCode()).isEqualTo(200);
            DocumentPreviewUrlResponse preTransferPreview = objectMapper.readValue(
                preTransferPreviewResp.body(),
                DocumentPreviewUrlResponse.class
            );
            URI preTransferPreviewUri = URI.create(Week2SseTestClient.stripFragment(preTransferPreview.previewUrl()));
            assertThat(client.fetchRawBytes(preTransferPreviewUri).statusCode()).isEqualTo(200);

            String leaveQuestion = "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?";
            List<SseFrame> preTransferFrames = client.collectAllSseFrames(
                port,
                Map.of("question", leaveQuestion),
                oldHrEmployeeJwt
            );
            JsonNode preTransferDone = objectMapper.readTree(preTransferFrames.getLast().data());
            assertThat(preTransferDone.path("abstained").asBoolean()).isFalse();
            UUID carriedSessionId = UUID.fromString(preTransferDone.path("sessionId").asText());

            // Separate pre-transfer session citing BOTH restricted hrDeptDoc AND company-wide hrPublicDoc on Turn 1
            List<SseFrame> mixedPreTransferFrames = client.collectAllSseFrames(
                port,
                Map.of("question", "Nhân viên chính thức nghỉ phép năm và làm việc từ xa hybrid work khung giờ phối hợp nào?"),
                oldHrEmployeeJwt
            );
            JsonNode mixedPreTransferDone = objectMapper.readTree(mixedPreTransferFrames.getLast().data());
            assertThat(mixedPreTransferDone.path("abstained").asBoolean()).isFalse();
            UUID mixedSessionId = UUID.fromString(mixedPreTransferDone.path("sessionId").asText());

            HttpResponse<String> preHistoryResp = client.sendJson(
                port,
                "GET",
                "/api/v1/chat/sessions/" + carriedSessionId + "/messages",
                null,
                oldHrEmployeeJwt
            );
            assertThat(preHistoryResp.statusCode()).isEqualTo(200);
            List<ChatMessageResponse> preHistory = objectMapper.readValue(preHistoryResp.body(), new TypeReference<>() {});
            assertThat(preHistory).hasSize(2);
            assertThat(preHistory.get(1).citations().getFirst().presignedUrl()).isNotBlank();
            assertThat(preHistory.get(1).citations().getFirst().excerpt()).contains("HR_TRANSFER_SECRET_14DAYS");

            List<ChatMessageResponse> preMixedHistory = objectMapper.readValue(
                client.sendJson(port, "GET", "/api/v1/chat/sessions/" + mixedSessionId + "/messages", null, oldHrEmployeeJwt).body(),
                new TypeReference<>() {}
            );
            assertThat(preMixedHistory.get(1).citations())
                .extracting(c -> c.documentId())
                .contains(hrDeptDoc.id(), hrPublicDoc.id());

            HttpResponse<String> transferResp = client.sendJson(
                port,
                "PATCH",
                "/api/v1/admin/users/" + HR_EMPLOYEE_USER_ID,
                Map.of("departmentId", SALES_DEPT_ID.toString()),
                adminJwt
            );
            assertThat(transferResp.statusCode()).isEqualTo(200);

            for (HttpResponse<String> staleResp : List.of(
                client.sendJson(port, "GET", "/api/v1/documents/" + hrDeptDoc.id(), null, oldHrEmployeeJwt),
                client.sendJson(port, "GET", "/api/v1/documents/" + hrDeptDoc.id() + "/preview-url?page=1", null, oldHrEmployeeJwt),
                client.sendJson(port, "POST", "/api/v1/chat/ask", Map.of("question", leaveQuestion), oldHrEmployeeJwt),
                client.sendJson(port, "POST", "/api/v1/chat/stream", Map.of("question", leaveQuestion), oldHrEmployeeJwt),
                client.sendJson(port, "GET", "/api/v1/chat/sessions/" + carriedSessionId + "/messages", null, oldHrEmployeeJwt)
            )) {
                assertThat(staleResp.statusCode()).isEqualTo(401);
                assertThat(staleResp.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE).orElse(""))
                    .contains("Bearer")
                    .contains("error=\"invalid_token\"");
                assertThat(objectMapper.readTree(staleResp.body()).path("errorCode").asText()).isEqualTo("TOKEN_REVOKED");
            }

            // Already-issued HR preview URL immediately loses read access (403) upon user department transfer
            assertThat(client.fetchRawBytes(preTransferPreviewUri).statusCode()).isEqualTo(403);

            AuthTokenResponse freshSalesLogin = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD);
            assertThat(freshSalesLogin.user().departmentCode()).isEqualTo("SALES");
            String newSalesJwt = freshSalesLogin.accessToken();

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + hrDeptDoc.id(), null, newSalesJwt).statusCode())
                .isEqualTo(403);
            HttpResponse<String> listAfterTransfer = client.sendJson(port, "GET", "/api/v1/documents?page=0&size=20", null, newSalesJwt);
            PageResponse<DocumentResponse> visibleAfterTransfer = objectMapper.readValue(
                listAfterTransfer.body(),
                new TypeReference<>() {}
            );
            assertThat(visibleAfterTransfer.items())
                .extracting(DocumentResponse::id)
                .containsExactly(hrPublicDoc.id());

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + hrDeptDoc.id() + "/preview-url?page=1", null, newSalesJwt).statusCode())
                .isEqualTo(403);
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + hrPublicDoc.id() + "/preview-url", null, newSalesJwt).statusCode())
                .isEqualTo(200);

            HttpResponse<String> postHistoryResp = client.sendJson(
                port,
                "GET",
                "/api/v1/chat/sessions/" + carriedSessionId + "/messages",
                null,
                newSalesJwt
            );
            assertThat(postHistoryResp.statusCode()).isEqualTo(200);
            List<ChatMessageResponse> postHistory = objectMapper.readValue(postHistoryResp.body(), new TypeReference<>() {});
            assertThat(postHistory).hasSize(2);
            ChatMessageResponse redactedAssistant = postHistory.get(1);
            assertThat(redactedAssistant.content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(redactedAssistant.rewrittenQuery()).isNull();
            assertThat(redactedAssistant.citations()).isEmpty();
            assertThat(postHistoryResp.body())
                .doesNotContain("HR_TRANSFER_SECRET_14DAYS")
                .doesNotContain("hr-transfer-leave.pdf")
                .doesNotContain(hrDeptDoc.id().toString());

            // Mixed session omits ALL restricted hrDeptDoc metadata while retaining still-authorized hrPublicDoc citation!
            HttpResponse<String> postMixedHistoryResp = client.sendJson(
                port,
                "GET",
                "/api/v1/chat/sessions/" + mixedSessionId + "/messages",
                null,
                newSalesJwt
            );
            assertThat(postMixedHistoryResp.statusCode()).isEqualTo(200);
            List<ChatMessageResponse> postMixedHistory = objectMapper.readValue(postMixedHistoryResp.body(), new TypeReference<>() {});
            ChatMessageResponse mixedRedactedAssistant = postMixedHistory.get(1);
            assertThat(mixedRedactedAssistant.content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(mixedRedactedAssistant.rewrittenQuery()).isNull();
            assertThat(mixedRedactedAssistant.citations())
                .hasSize(1)
                .allSatisfy(c -> {
                    assertThat(c.documentId()).isEqualTo(hrPublicDoc.id());
                    assertThat(c.fileName()).isEqualTo("company-hybrid-public.txt");
                    assertThat(c.presignedUrl()).isNotBlank();
                });
            assertThat(postMixedHistoryResp.body())
                .doesNotContain("HR_TRANSFER_SECRET_14DAYS")
                .doesNotContain("hr-transfer-leave.pdf")
                .doesNotContain(hrDeptDoc.id().toString());

            recordingChatModel.reset();
            List<SseFrame> exfilAttemptFrames = client.collectAllSseFrames(
                port,
                Map.of("question", leaveQuestion, "sessionId", carriedSessionId.toString()),
                newSalesJwt
            );
            client.assertDeterministicSseRefusal(
                exfilAttemptFrames,
                "HR_TRANSFER_SECRET_14DAYS",
                "hr-transfer-leave.pdf",
                true
            );
            assertThat(recordingChatModel.callCount()).isZero();

            Map<String, Object> postTransferAudit = jdbcTemplate.queryForMap(
                """
                SELECT user_id, department_id, abstained, status_code
                FROM audit_logs
                WHERE action_type = 'CHAT_QUERY' AND session_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """,
                carriedSessionId
            );
            assertThat(postTransferAudit.get("user_id")).isEqualTo(HR_EMPLOYEE_USER_ID);
            assertThat(postTransferAudit.get("department_id")).isEqualTo(SALES_DEPT_ID);
            assertThat(postTransferAudit.get("abstained")).isEqualTo(true);
            assertThat(postTransferAudit.get("status_code")).isEqualTo(200);

            for (String otherJwt : List.of(adminJwt, hrManagerJwt)) {
                assertThat(client.sendJson(port, "GET", "/api/v1/chat/sessions/" + carriedSessionId, null, otherJwt).statusCode())
                    .isEqualTo(404);
                assertThat(client.sendJson(port, "GET", "/api/v1/chat/sessions/" + carriedSessionId + "/messages", null, otherJwt).statusCode())
                    .isEqualTo(404);
                assertThat(client.sendJson(
                    port,
                    "PATCH",
                    "/api/v1/chat/sessions/" + carriedSessionId,
                    Map.of("title", "Hijacked"),
                    otherJwt
                ).statusCode()).isEqualTo(404);
                assertThat(client.sendJson(port, "DELETE", "/api/v1/chat/sessions/" + carriedSessionId, null, otherJwt).statusCode())
                    .isEqualTo(404);
                assertThat(client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/ask",
                    Map.of("question", "Probe foreign session", "sessionId", carriedSessionId.toString()),
                    otherJwt
                ).statusCode()).isEqualTo(404);
                assertThat(client.sendJson(
                    port,
                    "POST",
                    "/api/v1/chat/stream",
                    Map.of("question", "Probe foreign session", "sessionId", carriedSessionId.toString()),
                    otherJwt
                ).statusCode()).isEqualTo(404);
            }
            assertThat(client.sendJson(port, "GET", "/api/v1/chat/sessions/" + carriedSessionId, null, newSalesJwt).statusCode())
                .isEqualTo(200);
        }

        @Test
        void liveReclassificationTransferReindexAndHardDeleteCutOverAllThreeGatesImmediately() throws Exception {
            String adminJwt = client.login(port, ADMIN_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String hrManagerJwt = client.login(port, HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String itManagerJwt = client.login(port, IT_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String hrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String salesEmployeeJwt = client.login(port, SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

            byte[] v1Pdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                List.of("Trang bìa quy chế hoàn ứng công tác phí v1."),
                List.of("Quy chế hoàn ứng công tác phí v1: nộp chứng từ kế toán trong 7 ngày làm việc. LIVE_V1_CUTOVER_MARKER")
            ));
            DocumentResponse doc = client.uploadAndAwaitCompleted(
                port,
                "policy-live-v1.pdf",
                "application/pdf",
                v1Pdf,
                AccessLevel.PUBLIC,
                HR_DEPT_ID,
                hrManagerJwt
            );
            String question = "Thời hạn hoàn ứng công tác phí là bao nhiêu ngày làm việc?";

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id(), null, salesEmployeeJwt).statusCode())
                .isEqualTo(200);
            DocumentPreviewUrlResponse v1Preview = objectMapper.readValue(
                client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, salesEmployeeJwt).body(),
                DocumentPreviewUrlResponse.class
            );
            URI v1MinioUri = URI.create(Week2SseTestClient.stripFragment(v1Preview.previewUrl()));
            assertThat(client.fetchRawBytes(v1MinioUri).statusCode()).isEqualTo(200);

            List<SseFrame> salesTurn1 = client.collectAllSseFrames(port, Map.of("question", question), salesEmployeeJwt);
            JsonNode salesTurn1Done = objectMapper.readTree(salesTurn1.getLast().data());
            assertThat(salesTurn1Done.path("abstained").asBoolean()).isFalse();
            UUID salesSessionId = UUID.fromString(salesTurn1Done.path("sessionId").asText());

            assertThat(client.sendJson(
                port,
                "PATCH",
                "/api/v1/documents/" + doc.id(),
                Map.of("accessLevel", "CONFIDENTIAL"),
                hrManagerJwt
            ).statusCode()).isEqualTo(200);

            // Already-issued Sales preview URL immediately loses read access (403) when ACL is tightened to CONFIDENTIAL!
            assertThat(client.fetchRawBytes(v1MinioUri).statusCode()).isEqualTo(403);

            for (String blockedJwt : List.of(salesEmployeeJwt, hrEmployeeJwt)) {
                assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id(), null, blockedJwt).statusCode())
                    .isEqualTo(403);
                assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, blockedJwt).statusCode())
                    .isEqualTo(403);
                recordingChatModel.reset();
                List<SseFrame> refusedFrames = client.collectAllSseFrames(port, Map.of("question", question), blockedJwt);
                client.assertDeterministicSseRefusal(refusedFrames, "LIVE_V1_CUTOVER_MARKER", "policy-live-v1.pdf", true);
                assertThat(recordingChatModel.callCount()).isZero();
            }

            HttpResponse<String> redactedSalesHistoryRaw = client.sendJson(
                port,
                "GET",
                "/api/v1/chat/sessions/" + salesSessionId + "/messages",
                null,
                salesEmployeeJwt
            );
            List<ChatMessageResponse> redactedSalesHistory = objectMapper.readValue(
                redactedSalesHistoryRaw.body(),
                new TypeReference<>() {}
            );
            assertThat(redactedSalesHistory.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(redactedSalesHistory.get(1).citations()).isEmpty();
            assertThat(redactedSalesHistoryRaw.body())
                .doesNotContain("policy-live-v1.pdf")
                .doesNotContain(doc.id().toString());
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, hrManagerJwt).statusCode())
                .isEqualTo(200);

            assertThat(client.sendJson(
                port,
                "PATCH",
                "/api/v1/documents/" + doc.id(),
                Map.of("departmentId", IT_DEPT_ID.toString()),
                adminJwt
            ).statusCode()).isEqualTo(200);

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id(), null, hrManagerJwt).statusCode())
                .isEqualTo(403);
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, hrManagerJwt).statusCode())
                .isEqualTo(403);
            recordingChatModel.reset();
            client.assertDeterministicSseRefusal(
                client.collectAllSseFrames(port, Map.of("question", question), hrManagerJwt),
                "LIVE_V1_CUTOVER_MARKER",
                "policy-live-v1.pdf",
                true
            );
            assertThat(recordingChatModel.callCount()).isZero();

            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id(), null, itManagerJwt).statusCode())
                .isEqualTo(200);
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, itManagerJwt).statusCode())
                .isEqualTo(200);
            List<SseFrame> itMgrStream = client.collectAllSseFrames(port, Map.of("question", question), itManagerJwt);
            assertThat(objectMapper.readTree(itMgrStream.getLast().data()).path("abstained").asBoolean()).isFalse();

            byte[] v2Pdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                List.of("Trang bìa quy chế hoàn ứng công tác phí v2."),
                List.of("Quy chế hoàn ứng công tác phí v2: nộp chứng từ kế toán trong 5 ngày làm việc. LIVE_V2_CUTOVER_MARKER")
            ));
            HttpResponse<String> putV2Resp = client.sendMultipart(
                port,
                "PUT",
                "/api/v1/documents/" + doc.id(),
                "policy-live-v2.pdf",
                "application/pdf",
                v2Pdf,
                AccessLevel.PUBLIC,
                null,
                itManagerJwt
            );
            assertThat(putV2Resp.statusCode()).isEqualTo(202);
            DocumentStatusResponse v2Done = client.awaitTerminalDocumentStatus(port, doc.id(), itManagerJwt, Duration.ofSeconds(20));
            assertThat(v2Done.status()).isEqualTo(DocumentStatus.COMPLETED);
            assertThat(v2Done.currentVersion()).isEqualTo(2);

            assertThat(client.fetchRawBytes(v1MinioUri).statusCode()).isEqualTo(404);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE document_id = ? AND doc_version < 2",
                Integer.class,
                doc.id()
            )).isZero();

            // Even after v2 becomes PUBLIC, salesSessionId Turn 1 (which cited v1) MUST remain redacted & excluded from Turn 2 rewrite!
            HttpResponse<String> historyAfterV2PublicRaw = client.sendJson(
                port,
                "GET",
                "/api/v1/chat/sessions/" + salesSessionId + "/messages",
                null,
                salesEmployeeJwt
            );
            List<ChatMessageResponse> historyAfterV2Public = objectMapper.readValue(
                historyAfterV2PublicRaw.body(),
                new TypeReference<>() {}
            );
            assertThat(historyAfterV2Public.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(historyAfterV2Public.get(1).citations()).isEmpty();
            assertThat(historyAfterV2PublicRaw.body())
                .doesNotContain("LIVE_V1_CUTOVER_MARKER")
                .doesNotContain("policy-live-v1.pdf");

            List<SseFrame> salesV2Stream = client.collectAllSseFrames(
                port,
                Map.of("question", question, "sessionId", salesSessionId.toString()),
                salesEmployeeJwt
            );
            JsonNode v2Citations = objectMapper.readTree(salesV2Stream.getFirst().data()).path("citations");
            assertThat(v2Citations.size()).isGreaterThan(0);
            assertThat(salesV2Stream.getFirst().data())
                .contains("policy-live-v2.pdf")
                .contains("LIVE_V2_CUTOVER_MARKER")
                .doesNotContain("LIVE_V1_CUTOVER_MARKER");

            DocumentPreviewUrlResponse v2Preview = objectMapper.readValue(
                client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url?page=2", null, salesEmployeeJwt).body(),
                DocumentPreviewUrlResponse.class
            );
            URI v2MinioUri = URI.create(Week2SseTestClient.stripFragment(v2Preview.previewUrl()));
            assertThat(client.fetchRawBytes(v2MinioUri).body()).isEqualTo(v2Pdf);

            assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + doc.id(), null, itManagerJwt).statusCode())
                .isEqualTo(204);
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id(), null, itManagerJwt).statusCode())
                .isEqualTo(404);
            assertThat(client.sendJson(port, "GET", "/api/v1/documents/" + doc.id() + "/preview-url", null, itManagerJwt).statusCode())
                .isEqualTo(404);
            assertThat(client.fetchRawBytes(v2MinioUri).statusCode()).isEqualTo(404);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
                Integer.class,
                doc.id()
            )).isZero();

            recordingChatModel.reset();
            client.assertDeterministicSseRefusal(
                client.collectAllSseFrames(port, Map.of("question", question), salesEmployeeJwt),
                "LIVE_V2_CUTOVER_MARKER",
                "policy-live-v2.pdf",
                true
            );
            assertThat(recordingChatModel.callCount()).isZero();

            List<ChatMessageResponse> postDeleteHistory = objectMapper.readValue(
                client.sendJson(port, "GET", "/api/v1/chat/sessions/" + salesSessionId + "/messages", null, salesEmployeeJwt).body(),
                new TypeReference<>() {}
            );
            assertThat(postDeleteHistory.get(1).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(postDeleteHistory.get(1).citations()).isEmpty();
            assertThat(postDeleteHistory.get(3).content()).isEqualTo(ChatSessionService.REDACTED_ASSISTANT_CONTENT);
            assertThat(postDeleteHistory.get(3).citations()).isEmpty();
        }

        @Test
        void writeGateCrossDepartmentRbacMatrixBlocksUnauthorizedUploadsPatchesReindexesAndDeletes() throws Exception {
            String adminJwt = client.login(port, ADMIN_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String hrManagerJwt = client.login(port, HR_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String salesManagerJwt = client.login(port, SALES_MANAGER_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String hrEmployeeJwt = client.login(port, HR_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();
            String salesEmployeeJwt = client.login(port, SALES_EMPLOYEE_EMAIL, SEED_DEV_PASSWORD).accessToken();

            DocumentResponse hrPublicDoc = client.uploadAndAwaitCompleted(
                port,
                "hr-write-public.txt",
                "text/plain",
                "Tài liệu công khai do phòng HR sở hữu.".getBytes(StandardCharsets.UTF_8),
                AccessLevel.PUBLIC,
                HR_DEPT_ID,
                hrManagerJwt
            );
            DocumentResponse salesDeptDoc = client.uploadAndAwaitCompleted(
                port,
                "sales-write-dept.txt",
                "text/plain",
                "Tài liệu nội bộ phòng Kinh doanh.".getBytes(StandardCharsets.UTF_8),
                AccessLevel.DEPARTMENT,
                SALES_DEPT_ID,
                salesManagerJwt
            );

            byte[] dummyPayload = "Replacement payload content.".getBytes(StandardCharsets.UTF_8);

            for (String empJwt : List.of(hrEmployeeJwt, salesEmployeeJwt)) {
                assertThat(client.sendMultipart(port, "POST", "/api/v1/documents/upload", "emp.txt", "text/plain",
                    dummyPayload, AccessLevel.DEPARTMENT, null, empJwt).statusCode()).isEqualTo(403);
                assertThat(client.sendMultipart(port, "PUT", "/api/v1/documents/" + hrPublicDoc.id(), "emp-v2.txt", "text/plain",
                    dummyPayload, null, null, empJwt).statusCode()).isEqualTo(403);
                assertThat(client.sendJson(port, "PATCH", "/api/v1/documents/" + hrPublicDoc.id(),
                    Map.of("originalName", "hacked.txt"), empJwt).statusCode()).isEqualTo(403);
                assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + hrPublicDoc.id(), null, empJwt).statusCode())
                    .isEqualTo(403);
            }

            assertThat(client.sendMultipart(port, "POST", "/api/v1/documents/upload", "cross-upload.txt", "text/plain",
                dummyPayload, AccessLevel.DEPARTMENT, HR_DEPT_ID, salesManagerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendMultipart(port, "PUT", "/api/v1/documents/" + hrPublicDoc.id(), "cross-put.txt", "text/plain",
                dummyPayload, null, null, salesManagerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendJson(port, "PATCH", "/api/v1/documents/" + hrPublicDoc.id(),
                Map.of("originalName", "cross-patch.txt"), salesManagerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendJson(port, "PATCH", "/api/v1/documents/" + salesDeptDoc.id(),
                Map.of("departmentId", HR_DEPT_ID.toString()), salesManagerJwt).statusCode()).isEqualTo(403);
            assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + hrPublicDoc.id(), null, salesManagerJwt).statusCode())
                .isEqualTo(403);

            assertThat(client.sendJson(port, "PATCH", "/api/v1/documents/" + salesDeptDoc.id(),
                Map.of("originalName", "sales-write-dept-renamed.txt"), salesManagerJwt).statusCode()).isEqualTo(200);
            assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + hrPublicDoc.id(), null, adminJwt).statusCode())
                .isEqualTo(204);
            assertThat(client.sendJson(port, "DELETE", "/api/v1/documents/" + salesDeptDoc.id(), null, salesManagerJwt).statusCode())
                .isEqualTo(204);
        }
    }

    static Stream<Arguments> allSevenActorsReadMatrix() {
        return Stream.of(
            Arguments.of(ADMIN_EMAIL, Role.SYSTEM_ADMIN, "IT", EnumSet.allOf(CorpusDocKey.class)),
            Arguments.of(
                HR_MANAGER_EMAIL,
                Role.DEPARTMENT_MANAGER,
                "HR",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.HR_DEPT,
                    CorpusDocKey.HR_CONFIDENTIAL
                )
            ),
            Arguments.of(
                IT_MANAGER_EMAIL,
                Role.DEPARTMENT_MANAGER,
                "IT",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.IT_DEPT,
                    CorpusDocKey.IT_CONFIDENTIAL
                )
            ),
            Arguments.of(
                SALES_MANAGER_EMAIL,
                Role.DEPARTMENT_MANAGER,
                "SALES",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.SALES_DEPT,
                    CorpusDocKey.SALES_CONFIDENTIAL
                )
            ),
            Arguments.of(
                HR_EMPLOYEE_EMAIL,
                Role.STANDARD_EMPLOYEE,
                "HR",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.HR_DEPT
                )
            ),
            Arguments.of(
                IT_EMPLOYEE_EMAIL,
                Role.STANDARD_EMPLOYEE,
                "IT",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.IT_DEPT
                )
            ),
            Arguments.of(
                SALES_EMPLOYEE_EMAIL,
                Role.STANDARD_EMPLOYEE,
                "SALES",
                EnumSet.of(
                    CorpusDocKey.HR_PUBLIC,
                    CorpusDocKey.IT_PUBLIC,
                    CorpusDocKey.SALES_PUBLIC,
                    CorpusDocKey.SALES_DEPT
                )
            )
        );
    }

    enum CorpusDocKey {
        HR_PUBLIC,
        HR_DEPT,
        HR_CONFIDENTIAL,
        IT_PUBLIC,
        IT_DEPT,
        IT_CONFIDENTIAL,
        SALES_PUBLIC,
        SALES_DEPT,
        SALES_CONFIDENTIAL
    }

    private record IngestedDocFixture(
        CorpusDocKey key,
        DocumentResponse doc,
        String storageKey,
        byte[] rawBytes,
        boolean isPdf,
        Integer expectedPage,
        String query,
        String secretMarker
    ) {
    }
}
