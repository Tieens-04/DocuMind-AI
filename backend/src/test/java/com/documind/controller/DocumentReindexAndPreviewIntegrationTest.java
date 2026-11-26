package com.documind.controller;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentPreviewUrlResponse;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.auth.AuthTokenResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.storage.MinioConfig;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.service.audit.AuditLogService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end Testcontainers integration suite ({@code pgvector/pgvector:pg16}, {@code redis:7-alpine}, {@code minio})
 * validating {@code DA-17}: safe versioned document re-indexing ({@code PUT /api/v1/documents/{id}}), immediate RAG
 * cutover across {@code /api/v1/chat/ask} and {@code /api/v1/chat/stream} with zero stale chunks or S3 orphans,
 * failed re-index cleanup + same-hash retry recovery, concurrent Virtual Thread updates, and RBAC-enforced
 * {@code GET /api/v1/documents/{id}/preview-url} verified with live MinIO HTTP GET round-trips.
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
class DocumentReindexAndPreviewIntegrationTest {

    private static final String SEED_DEV_PASSWORD = "DocuMind@2026!";
    private static final String ADMIN_EMAIL = "admin@documind.ai";
    private static final String HR_MANAGER_EMAIL = "manager.hr@documind.ai";
    private static final String IT_MANAGER_EMAIL = "manager.it@documind.ai";
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
    private AbstractIntegrationTest.RecordingTestChatModel recordingChatModel;

    @Autowired(required = false)
    private AuditLogService auditLogService;

    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    @AfterAll
    static void closeHttpClient() {
        httpClient.close();
    }

    @BeforeEach
    void setUp() throws InterruptedException {
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
        AbstractIntegrationTest.seedSupplementalTestUsers(jdbcTemplate);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
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

    @Test
    void putReplaceDocumentSwapsChunksCleansOldMinioObjectAndUpdatesAskAndStreamCitationsImmediately() throws Exception {
        String hrManagerToken = login(HR_MANAGER_EMAIL).accessToken();
        String hrEmployeeToken = login(HR_EMPLOYEE_EMAIL).accessToken();

        byte[] v1Pdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of("Quy chế nghỉ phép năm phiên bản 2025: nhân viên chính thức được hưởng 12 ngày phép năm cơ bản.")
        ));
        DocumentResponse v1Doc = uploadAndAwaitCompleted(
            "chinh-sach-nghi-phep-v1.pdf",
            "application/pdf",
            v1Pdf,
            AccessLevel.DEPARTMENT,
            null,
            hrManagerToken
        );
        String v1StorageKey = queryStorageKey(v1Doc.id());
        long hrCacheVersionBeforePut = queryDepartmentCacheVersion(HR_DEPT_ID);

        // Verify v1 is retrieved and cited before replacement
        ChatAskResponse v1Answer = askQuestion("Chính sách nghỉ phép năm được bao nhiêu ngày?", hrEmployeeToken);
        assertThat(v1Answer.abstained()).isFalse();
        assertThat(v1Answer.citations()).isNotEmpty();
        assertThat(v1Answer.citations())
            .allSatisfy(c -> {
                assertThat(c.documentId()).isEqualTo(v1Doc.id());
                assertThat(c.fileName()).isEqualTo("chinh-sach-nghi-phep-v1.pdf");
                assertThat(c.pageNumber()).isEqualTo(1);
                assertThat(c.excerpt()).contains("12 ngày phép năm");
            });

        // Replace with v2 multi-page PDF where the updated annual leave rule lives on page 2
        byte[] v2Pdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of("Trang bìa cập nhật văn bản hành chính nội bộ doanh nghiệp."),
            List.of("Quy chế nghỉ phép năm phiên bản 2026: nhân viên chính thức được hưởng 18 ngày phép năm cơ bản cùng thâm niên.")
        ));
        HttpResponse<String> putResp = sendMultipartPut(
            "/api/v1/documents/" + v1Doc.id(),
            "chinh-sach-nghi-phep-v2.pdf",
            "application/pdf",
            v2Pdf,
            null,
            hrManagerToken
        );
        assertThat(putResp.statusCode()).isEqualTo(202);
        DocumentResponse putBody = objectMapper.readValue(putResp.body(), DocumentResponse.class);
        assertThat(putBody.id()).isEqualTo(v1Doc.id());
        assertThat(putBody.currentVersion()).isEqualTo(2);
        assertThat(putBody.originalName()).isEqualTo("chinh-sach-nghi-phep-v2.pdf");
        assertThat(putBody.status()).isEqualTo(DocumentStatus.PENDING);

        DocumentStatusResponse v2Terminal = awaitTerminalStatus(v1Doc.id(), hrManagerToken, Duration.ofSeconds(15));
        assertThat(v2Terminal.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(v2Terminal.currentVersion()).isEqualTo(2);
        assertThat(v2Terminal.chunkCount()).isGreaterThan(0);

        // Verify zero v1 chunks remain in pgvector and all chunks belong exclusively to v2
        Integer v1ChunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_id = ? AND doc_version = 1",
            Integer.class,
            v1Doc.id()
        );
        Integer v2ChunkCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_id = ? AND doc_version = 2",
            Integer.class,
            v1Doc.id()
        );
        assertThat(v1ChunkCount).isZero();
        assertThat(v2ChunkCount).isEqualTo(v2Terminal.chunkCount());

        // Verify old v1 object was deleted from MinIO after commit while v2 object exists
        String v2StorageKey = queryStorageKey(v1Doc.id());
        assertThat(v2StorageKey).isNotEqualTo(v1StorageKey).contains("/v2/");
        assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(v1StorageKey)
            .build()))
            .isInstanceOf(NoSuchKeyException.class);
        assertThat(s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(v2StorageKey)
            .build()).contentLength()).isEqualTo(v2Pdf.length);

        // Verify DOC_REINDEX audit record and exact +2 cache_version progression (+1 from V6 trigger on rename + +1 from markCompleted)
        Integer reindexAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'DOC_REINDEX' AND status_code = 202 AND query_text = ?",
            Integer.class,
            "chinh-sach-nghi-phep-v2.pdf"
        );
        assertThat(reindexAuditCount).isEqualTo(1);
        assertThat(queryDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(hrCacheVersionBeforePut + 2L);

        // Immediately ask via synchronous /api/v1/chat/ask -> cites ONLY v2 page 2, never v1
        ChatAskResponse v2Answer = askQuestion("Chính sách nghỉ phép năm được bao nhiêu ngày?", hrEmployeeToken);
        assertThat(v2Answer.abstained()).isFalse();
        assertThat(v2Answer.citations()).isNotEmpty();
        assertThat(v2Answer.citations())
            .anySatisfy(c -> {
                assertThat(c.fileName()).isEqualTo("chinh-sach-nghi-phep-v2.pdf");
                assertThat(c.pageNumber()).isEqualTo(2);
                assertThat(c.excerpt()).contains("18 ngày phép năm");
                assertThat(c.presignedUrl()).endsWith("#page=2");
            })
            .noneSatisfy(c -> assertThat(c.excerpt()).contains("12 ngày phép năm"))
            .noneSatisfy(c -> assertThat(c.fileName()).isEqualTo("chinh-sach-nghi-phep-v1.pdf"));

        // Immediately ask via real-time SSE /api/v1/chat/stream -> citations event reflects ONLY v2
        List<SseEventFrame> sseFrames = streamQuestion("Chính sách nghỉ phép năm được bao nhiêu ngày?", hrEmployeeToken);
        SseEventFrame citationsFrame = sseFrames.stream()
            .filter(f -> "citations".equals(f.event()))
            .findFirst()
            .orElseThrow();
        JsonNode citationsArray = objectMapper.readTree(citationsFrame.data()).path("citations");
        assertThat(citationsArray.size()).isGreaterThan(0);
        boolean foundV2Citation = false;
        for (JsonNode citationNode : citationsArray) {
            assertThat(citationNode.path("fileName").asText()).isEqualTo("chinh-sach-nghi-phep-v2.pdf");
            assertThat(citationNode.path("excerpt").asText()).doesNotContain("12 ngày phép năm");
            if (citationNode.path("pageNumber").asInt() == 2
                && citationNode.path("excerpt").asText().contains("18 ngày phép năm")) {
                foundV2Citation = true;
            }
        }
        assertThat(foundV2Citation).isTrue();
    }

    @Test
    void failedReindexPurgesOldChunksSameHashRetryRecoversDuplicateRejects409AndConcurrentPutsStayConsistent()
        throws Exception {
        String hrManagerToken = login(HR_MANAGER_EMAIL).accessToken();

        byte[] v1Pdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of("Chính sách bảo hiểm sức khỏe PVI Care phiên bản 1 dành cho nhân viên HR.")
        ));
        DocumentResponse doc = uploadAndAwaitCompleted(
            "hr-pvi-care.pdf",
            "application/pdf",
            v1Pdf,
            AccessLevel.DEPARTMENT,
            null,
            hrManagerToken
        );

        // 1. Replace with blank .txt -> worker marks FAILED ([EMPTY_DOCUMENT]), purges obsolete v1 chunks, and V6 bumps cache_version by exact +1
        long cacheVersionBeforeFailPut = queryDepartmentCacheVersion(HR_DEPT_ID);
        byte[] blankTxt = "   \r\n\t   ".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> failPut = sendMultipartPut(
            "/api/v1/documents/" + doc.id(),
            "hr-pvi-care.txt",
            "text/plain",
            blankTxt,
            null,
            hrManagerToken
        );
        assertThat(failPut.statusCode()).isEqualTo(202);
        DocumentStatusResponse failedStatus = awaitTerminalStatus(doc.id(), hrManagerToken, Duration.ofSeconds(15));
        assertThat(failedStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failedStatus.currentVersion()).isEqualTo(2);
        assertThat(failedStatus.chunkCount()).isZero();
        assertThat(failedStatus.errorMessage()).startsWith("[EMPTY_DOCUMENT]");
        assertThat(countChunksForDocument(doc.id())).isZero();
        assertThat(queryDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(cacheVersionBeforeFailPut + 1L);

        // 2. Recover FAILED document by re-uploading valid PDF (v3) -> COMPLETED
        long cacheVersionBeforeSameNamePut = queryDepartmentCacheVersion(HR_DEPT_ID);
        HttpResponse<String> recoverPut = sendMultipartPut(
            "/api/v1/documents/" + doc.id(),
            "hr-pvi-care.pdf",
            "application/pdf",
            v1Pdf,
            null,
            hrManagerToken
        );
        assertThat(recoverPut.statusCode()).isEqualTo(202);
        DocumentStatusResponse v3Status = awaitTerminalStatus(doc.id(), hrManagerToken, Duration.ofSeconds(15));
        assertThat(v3Status.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(v3Status.currentVersion()).isEqualTo(3);
        assertThat(countChunksForDocument(doc.id())).isEqualTo(v3Status.chunkCount());
        assertThat(queryDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(cacheVersionBeforeSameNamePut + 2L);

        // 3. Re-index the SAME document with identical file_hash + identical originalName/accessLevel -> allowed (v4) & exact +2 cache_version (+1 Java + +1 markCompleted)
        long cacheVersionBeforeSameHash = queryDepartmentCacheVersion(HR_DEPT_ID);
        HttpResponse<String> sameHashPut = sendMultipartPut(
            "/api/v1/documents/" + doc.id(),
            "hr-pvi-care.pdf",
            "application/pdf",
            v1Pdf,
            null,
            hrManagerToken
        );
        assertThat(sameHashPut.statusCode()).isEqualTo(202);
        DocumentStatusResponse v4Status = awaitTerminalStatus(doc.id(), hrManagerToken, Duration.ofSeconds(15));
        assertThat(v4Status.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(v4Status.currentVersion()).isEqualTo(4);
        assertThat(queryDepartmentCacheVersion(HR_DEPT_ID)).isEqualTo(cacheVersionBeforeSameHash + 2L);

        // 4. Duplicate file_hash matching ANOTHER document in same dept -> 409 DUPLICATE_DOCUMENT & zero S3 orphan
        byte[] otherDocBytes = "Tài liệu quy trình onboarding nhân sự nội bộ phòng HR.".getBytes(StandardCharsets.UTF_8);
        DocumentResponse otherDoc = uploadAndAwaitCompleted(
            "hr-onboarding.txt",
            "text/plain",
            otherDocBytes,
            AccessLevel.DEPARTMENT,
            null,
            hrManagerToken
        );
        int s3CountBeforeDup = listBucketObjectKeys().size();
        HttpResponse<String> dupPut = sendMultipartPut(
            "/api/v1/documents/" + doc.id(),
            "duplicate-target.txt",
            "text/plain",
            otherDocBytes,
            null,
            hrManagerToken
        );
        assertThat(dupPut.statusCode()).isEqualTo(409);
        JsonNode dupProblem = objectMapper.readTree(dupPut.body());
        assertThat(dupProblem.path("errorCode").asText()).isEqualTo("DUPLICATE_DOCUMENT");
        assertThat(dupProblem.path("existingDocumentId").asText()).isEqualTo(otherDoc.id().toString());
        assertThat(listBucketObjectKeys()).hasSize(s3CountBeforeDup);

        // 5. Concurrent Virtual-Thread PUT race on the same document -> no 500/deadlock, clean winner state, 0 S3 orphans
        byte[] raceBytesA = "Quy chế bảo hiểm sức khỏe PVI Care phiên bản đua A.".getBytes(StandardCharsets.UTF_8);
        byte[] raceBytesB = "Quy chế bảo hiểm sức khỏe PVI Care phiên bản đua B.".getBytes(StandardCharsets.UTF_8);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<HttpResponse<String>> taskA = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return sendMultipartPut("/api/v1/documents/" + doc.id(), "race-a.txt", "text/plain", raceBytesA, null, hrManagerToken);
            };
            Callable<HttpResponse<String>> taskB = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                return sendMultipartPut("/api/v1/documents/" + doc.id(), "race-b.txt", "text/plain", raceBytesB, null, hrManagerToken);
            };
            Future<HttpResponse<String>> futureA = vtPool.submit(taskA);
            Future<HttpResponse<String>> futureB = vtPool.submit(taskB);
            HttpResponse<String> respA = futureA.get(15, TimeUnit.SECONDS);
            HttpResponse<String> respB = futureB.get(15, TimeUnit.SECONDS);
            assertThat(respA.statusCode()).isEqualTo(202);
            assertThat(respB.statusCode()).isEqualTo(202);
        }

        DocumentStatusResponse finalWinner = awaitTerminalStatus(doc.id(), hrManagerToken, Duration.ofSeconds(15));
        assertThat(finalWinner.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(finalWinner.currentVersion()).isEqualTo(6);
        awaitChunkCountEquals(doc.id(), finalWinner.chunkCount(), Duration.ofSeconds(5));
        // Exactly 2 documents exist (doc + otherDoc) -> exactly 2 objects in MinIO bucket (0 orphan versions left behind)
        assertThat(listBucketObjectKeys()).hasSize(2);

        // 6. Concurrent PUT (promoting to PUBLIC) + DELETE race on doc -> zero 500/deadlock, zero orphan MinIO objects
        long itCacheVersionBeforePutDeleteRace = queryDepartmentCacheVersion(IT_DEPT_ID);
        byte[] raceWithDeleteBytes = "Quy chế bảo hiểm nâng cấp PUBLIC đua với DELETE.".getBytes(StandardCharsets.UTF_8);
        CyclicBarrier putDeleteBarrier = new CyclicBarrier(2);
        HttpResponse<String> putRaceResp;
        HttpResponse<String> deleteRaceResp;
        try (ExecutorService vtPool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<HttpResponse<String>> putFuture = vtPool.submit(() -> {
                putDeleteBarrier.await(5, TimeUnit.SECONDS);
                return sendMultipartPut(
                    "/api/v1/documents/" + doc.id(),
                    "race-delete.txt",
                    "text/plain",
                    raceWithDeleteBytes,
                    AccessLevel.PUBLIC,
                    hrManagerToken
                );
            });
            Future<HttpResponse<String>> deleteFuture = vtPool.submit(() -> {
                putDeleteBarrier.await(5, TimeUnit.SECONDS);
                return sendDelete("/api/v1/documents/" + doc.id(), hrManagerToken);
            });
            putRaceResp = putFuture.get(15, TimeUnit.SECONDS);
            deleteRaceResp = deleteFuture.get(15, TimeUnit.SECONDS);
        }
        assertThat(deleteRaceResp.statusCode()).isEqualTo(204);
        assertThat(putRaceResp.statusCode()).isIn(202, 404);
        assertThat(countChunksForDocument(doc.id())).isZero();
        if (putRaceResp.statusCode() == 202) {
            assertThat(queryDepartmentCacheVersion(IT_DEPT_ID)).isGreaterThan(itCacheVersionBeforePutDeleteRace);
        }
        // Only otherDoc remains -> exactly 1 object in MinIO bucket (0 orphan objects leaked from PUT + DELETE race)
        assertThat(listBucketObjectKeys()).hasSize(1);
    }

    @ParameterizedTest(name = "{0} accessing {1} -> canPreview={2}, canReplace={3}")
    @MethodSource("rbacMatrixArguments")
    void previewUrlAndReplaceDocumentEnforceMandatoryRbacTruthMatrixAndLiveMinioInlineFetch(
        String callerEmail,
        String targetDocKey,
        boolean expectedCanPreview,
        boolean expectedCanReplace
    ) throws Exception {
        String ownerEmail = "it-dept".equals(targetDocKey) ? IT_MANAGER_EMAIL : HR_MANAGER_EMAIL;
        String ownerToken = login(ownerEmail).accessToken();
        SeededFixture target = seedFixtureForKey(targetDocKey, ownerToken);
        String callerToken = callerEmail.equals(ownerEmail) ? ownerToken : login(callerEmail).accessToken();

        // 1. Verify GET /api/v1/documents/{id}/preview-url?page=2
        HttpResponse<String> previewResp = sendGet(
            "/api/v1/documents/" + target.doc().id() + "/preview-url?page=2",
            callerToken
        );
        if (expectedCanPreview) {
            assertThat(previewResp.statusCode()).isEqualTo(200);
            DocumentPreviewUrlResponse preview = objectMapper.readValue(previewResp.body(), DocumentPreviewUrlResponse.class);
            assertThat(preview.documentId()).isEqualTo(target.doc().id());
            assertThat(preview.originalName()).isEqualTo(target.doc().originalName());
            assertThat(preview.currentVersion()).isEqualTo(1);
            assertThat(preview.expiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(10));

            String fetchUrl;
            if (target.isPdf()) {
                assertThat(preview.pageNumber()).isEqualTo(2);
                assertThat(preview.previewUrl()).endsWith("#page=2");
                fetchUrl = preview.previewUrl().substring(0, preview.previewUrl().indexOf("#page="));
            } else {
                assertThat(preview.pageNumber()).isNull();
                assertThat(preview.previewUrl()).doesNotContain("#page=");
                fetchUrl = preview.previewUrl();
            }

            // Real HTTP GET directly against MinIO container using the signed presigned URL
            HttpResponse<byte[]> minioResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(fetchUrl)).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(minioResp.statusCode()).isEqualTo(200);
            assertThat(minioResp.headers().firstValue("Content-Disposition"))
                .isPresent()
                .get()
                .asString()
                .startsWith("inline;");
            assertThat(minioResp.body()).isEqualTo(target.rawBytes());
        } else {
            assertThat(previewResp.statusCode()).isEqualTo(403);
            JsonNode problem = objectMapper.readTree(previewResp.body());
            assertThat(problem.path("errorCode").asText()).isEqualTo("DOCUMENT_ACCESS_DENIED");
            assertThat(problem.path("detail").asText())
                .doesNotContain(target.doc().id().toString())
                .doesNotContain(target.doc().departmentId().toString());
            assertThat(previewResp.body()).doesNotContain(target.doc().departmentId().toString());
        }

        // 2. Verify PUT /api/v1/documents/{id} RBAC enforcement
        byte[] replacementBytes = ("Updated content by " + callerEmail + " for " + targetDocKey + " " + UUID.randomUUID())
            .getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> putResp = sendMultipartPut(
            "/api/v1/documents/" + target.doc().id(),
            "updated-" + targetDocKey + ".txt",
            "text/plain",
            replacementBytes,
            null,
            callerToken
        );
        if (expectedCanReplace) {
            assertThat(putResp.statusCode()).isEqualTo(202);
            DocumentStatusResponse completed = awaitTerminalStatus(target.doc().id(), callerToken, Duration.ofSeconds(15));
            assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
            assertThat(completed.currentVersion()).isEqualTo(2);
        } else {
            assertThat(putResp.statusCode()).isEqualTo(403);
            String errorCode = objectMapper.readTree(putResp.body()).path("errorCode").asText();
            assertThat(errorCode).isIn("ACCESS_DENIED", "DOCUMENT_ACCESS_DENIED");
        }
    }

    @Test
    void previewUrlValidatesAuthPageBoundsAndNotFound() throws Exception {
        String hrManagerToken = login(HR_MANAGER_EMAIL).accessToken();
        byte[] pdfBytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(List.of("Nội dung trang 1")));
        DocumentResponse doc = uploadAndAwaitCompleted("bounds.pdf", "application/pdf", pdfBytes, AccessLevel.DEPARTMENT, null, hrManagerToken);

        // Unauthenticated -> 401
        HttpResponse<String> unauth = sendGet("/api/v1/documents/" + doc.id() + "/preview-url", null);
        assertThat(unauth.statusCode()).isEqualTo(401);

        // Invalid page <= 0 or non-numeric -> 400
        HttpResponse<String> zeroPage = sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=0", hrManagerToken);
        assertThat(zeroPage.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(zeroPage.body()).path("errorCode").asText()).isEqualTo("INVALID_DOCUMENT_REQUEST");

        HttpResponse<String> negPage = sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=-5", hrManagerToken);
        assertThat(negPage.statusCode()).isEqualTo(400);

        HttpResponse<String> nonNumericPage = sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=abc", hrManagerToken);
        assertThat(nonNumericPage.statusCode()).isEqualTo(400);

        // Unknown documentId -> 404 RESOURCE_NOT_FOUND
        HttpResponse<String> missing = sendGet("/api/v1/documents/" + UUID.randomUUID() + "/preview-url?page=1", hrManagerToken);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(missing.body()).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");

        // PUT /api/v1/documents/{id} HTTP error contracts: 401, 404, 400 (0-byte & missing part), 415 (spoofed magic bytes)
        String originalStorageKey = queryStorageKey(doc.id());
        int originalChunkCount = countChunksForDocument(doc.id());

        HttpResponse<String> putUnauth = sendMultipartPut(
            "/api/v1/documents/" + doc.id(), "v2.pdf", "application/pdf", pdfBytes, null, null
        );
        assertThat(putUnauth.statusCode()).isEqualTo(401);

        HttpResponse<String> putNotFound = sendMultipartPut(
            "/api/v1/documents/" + UUID.randomUUID(), "v2.pdf", "application/pdf", pdfBytes, null, hrManagerToken
        );
        assertThat(putNotFound.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(putNotFound.body()).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");

        HttpResponse<String> putEmptyFile = sendMultipartPut(
            "/api/v1/documents/" + doc.id(), "empty.txt", "text/plain", new byte[0], null, hrManagerToken
        );
        assertThat(putEmptyFile.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(putEmptyFile.body()).path("errorCode").asText()).isEqualTo("INVALID_DOCUMENT_REQUEST");

        String missingPartBoundary = "----DocuMindMissingFilePart";
        byte[] missingFilePayload = ("--" + missingPartBoundary + "\r\n"
            + "Content-Disposition: form-data; name=\"accessLevel\"\r\n\r\n"
            + "DEPARTMENT\r\n"
            + "--" + missingPartBoundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> putMissingFilePart = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/documents/" + doc.id()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrManagerToken)
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + missingPartBoundary)
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(missingFilePayload))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(putMissingFilePart.statusCode()).isEqualTo(400);

        byte[] spoofedPngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
        HttpResponse<String> putSpoofedType = sendMultipartPut(
            "/api/v1/documents/" + doc.id(), "spoof.pdf", "application/pdf", spoofedPngBytes, null, hrManagerToken
        );
        assertThat(putSpoofedType.statusCode()).isEqualTo(415);
        assertThat(objectMapper.readTree(putSpoofedType.body()).path("errorCode").asText()).isEqualTo("UNSUPPORTED_DOCUMENT_TYPE");

        // Verify v1 state, chunks, and MinIO object remained completely untouched after rejected PUTs
        DocumentStatusResponse intactStatus = awaitTerminalStatus(doc.id(), hrManagerToken, Duration.ofSeconds(5));
        assertThat(intactStatus.currentVersion()).isEqualTo(1);
        assertThat(intactStatus.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(countChunksForDocument(doc.id())).isEqualTo(originalChunkCount);
        assertThat(queryStorageKey(doc.id())).isEqualTo(originalStorageKey);
        assertThat(listBucketObjectKeys()).containsExactly(originalStorageKey);

        // Live MinIO SigV4 verification with distinct internal endpoint (127.0.0.1) vs publicEndpoint (localhost)
        URI containerEndpoint = URI.create(storageProperties.endpoint());
        String internalIpEndpoint = containerEndpoint.getScheme() + "://127.0.0.1:" + containerEndpoint.getPort();
        String publicHostEndpoint = containerEndpoint.getScheme() + "://localhost:" + containerEndpoint.getPort();
        StorageProperties splitEndpointProps = new StorageProperties(
            internalIpEndpoint,
            storageProperties.accessKey(),
            storageProperties.secretKey(),
            storageProperties.bucket(),
            storageProperties.region(),
            storageProperties.uploadTimeout(),
            storageProperties.presignedExpiry(),
            publicHostEndpoint
        );
        try (S3Presigner splitPresigner = new MinioConfig().s3Presigner(splitEndpointProps)) {
            MinioStorageService splitStorageService = new MinioStorageService(s3Client, splitPresigner, splitEndpointProps);
            URL publicSignedUrl = splitStorageService.generatePresignedGetUrl(originalStorageKey, doc.originalName());
            assertThat(publicSignedUrl.getHost()).isEqualTo("localhost");
            HttpResponse<byte[]> livePublicResp = httpClient.send(
                HttpRequest.newBuilder(publicSignedUrl.toURI()).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(livePublicResp.statusCode()).isEqualTo(200);
            assertThat(livePublicResp.headers().firstValue("Content-Disposition"))
                .isPresent()
                .get()
                .asString()
                .startsWith("inline;");
            assertThat(livePublicResp.body()).isEqualTo(pdfBytes);
        }
    }

    @Test
    void alreadyIssuedPreviewAndCitationUrlsLoseReadAccessImmediatelyOnAclTighteningAndUserTransfer() throws Exception {
        String adminToken = login(ADMIN_EMAIL).accessToken();
        String hrManagerToken = login(HR_MANAGER_EMAIL).accessToken();
        String hrEmployeeToken = login(HR_EMPLOYEE_EMAIL).accessToken();
        String salesEmployeeToken = login(SALES_EMPLOYEE_EMAIL).accessToken();

        byte[] policyPdf = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of("Trang bìa văn bản hành chính nội bộ."),
            List.of("Quy chế nghỉ phép năm toàn công ty: nhân viên chính thức được hưởng 12 ngày phép năm cơ bản. ACL_INSTANT_REVOKE_MARKER")
        ));
        DocumentResponse doc = uploadAndAwaitCompleted(
            "hr-leave-acl.pdf",
            "application/pdf",
            policyPdf,
            AccessLevel.PUBLIC,
            null,
            hrManagerToken
        );

        // 1. Issue preview URLs to Sales Employee, HR Employee, and HR Manager while document is PUBLIC
        DocumentPreviewUrlResponse salesPreview = objectMapper.readValue(
            sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=2", salesEmployeeToken).body(),
            DocumentPreviewUrlResponse.class
        );
        DocumentPreviewUrlResponse hrEmpPreview = objectMapper.readValue(
            sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=2", hrEmployeeToken).body(),
            DocumentPreviewUrlResponse.class
        );
        DocumentPreviewUrlResponse hrMgrPreview = objectMapper.readValue(
            sendGet("/api/v1/documents/" + doc.id() + "/preview-url?page=2", hrManagerToken).body(),
            DocumentPreviewUrlResponse.class
        );
        URI salesPreviewFetchUri = URI.create(salesPreview.previewUrl().substring(0, salesPreview.previewUrl().indexOf("#page=")));
        URI hrEmpPreviewFetchUri = URI.create(hrEmpPreview.previewUrl().substring(0, hrEmpPreview.previewUrl().indexOf("#page=")));
        URI hrMgrPreviewFetchUri = URI.create(hrMgrPreview.previewUrl().substring(0, hrMgrPreview.previewUrl().indexOf("#page=")));

        // Also issue a RAG citation URL to Sales Employee via /api/v1/chat/ask while PUBLIC
        ChatAskResponse salesAsk = askQuestion("Chính sách nghỉ phép năm được bao nhiêu ngày?", salesEmployeeToken);
        assertThat(salesAsk.citations()).isNotEmpty();
        String rawCitationUrl = salesAsk.citations().getFirst().presignedUrl();
        URI salesCitationFetchUri = URI.create(rawCitationUrl.substring(0, rawCitationUrl.indexOf("#page=")));

        // All issued URLs work while PUBLIC (200 OK + exact bytes + inline Content-Disposition + no-store Cache-Control)
        HttpResponse<byte[]> initialSalesFetch = httpClient.send(
            HttpRequest.newBuilder(salesPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(initialSalesFetch.statusCode()).isEqualTo(200);
        assertThat(initialSalesFetch.body()).isEqualTo(policyPdf);
        assertThat(initialSalesFetch.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(httpClient.send(
            HttpRequest.newBuilder(salesCitationFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(200);

        // Supports HTTP Range requests for PDF viewers (206 Partial Content)
        HttpResponse<byte[]> rangeFetch = httpClient.send(
            HttpRequest.newBuilder(hrMgrPreviewFetchUri)
                .header("Range", "bytes=0-4")
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
        assertThat(rangeFetch.statusCode()).isEqualTo(206);
        assertThat(new String(rangeFetch.body(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // 2. Tighten ACL from PUBLIC -> DEPARTMENT (HR): Sales Employee's ALREADY-ISSUED preview & citation URLs immediately lose read access (403)!
        HttpResponse<String> patchToDept = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/documents/" + doc.id()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrManagerToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"accessLevel\":\"DEPARTMENT\"}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(patchToDept.statusCode()).isEqualTo(200);

        assertThat(httpClient.send(
            HttpRequest.newBuilder(salesPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(403);
        assertThat(httpClient.send(
            HttpRequest.newBuilder(salesCitationFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(403);
        // HR Employee & HR Manager still have DEPARTMENT access in HR -> 200 OK
        assertThat(httpClient.send(
            HttpRequest.newBuilder(hrEmpPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(200);

        // 3. Tighten ACL from DEPARTMENT -> CONFIDENTIAL: HR Employee's ALREADY-ISSUED URL immediately loses read access (403), while HR Manager retains 200!
        HttpResponse<String> patchToConf = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/documents/" + doc.id()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrManagerToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"accessLevel\":\"CONFIDENTIAL\"}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(patchToConf.statusCode()).isEqualTo(200);

        assertThat(httpClient.send(
            HttpRequest.newBuilder(hrEmpPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(403);
        assertThat(httpClient.send(
            HttpRequest.newBuilder(hrMgrPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(200);

        // 4. Transfer document ownership from HR -> IT: HR Manager's ALREADY-ISSUED URL immediately loses read access (403)!
        HttpResponse<String> patchTransferDept = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/documents/" + doc.id()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"departmentId\":\"" + IT_DEPT_ID + "\"}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(patchTransferDept.statusCode()).isEqualTo(200);

        assertThat(httpClient.send(
            HttpRequest.newBuilder(hrMgrPreviewFetchUri).GET().timeout(Duration.ofSeconds(10)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).statusCode()).isEqualTo(403);
    }

    private SeededFixture seedFixtureForKey(String targetDocKey, String ownerToken) throws Exception {
        return switch (targetDocKey) {
            case "hr-public" -> {
                byte[] bytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                    List.of("Trang 1 tài liệu công khai toàn công ty."),
                    List.of("Trang 2 tài liệu công khai toàn công ty: văn hóa doanh nghiệp và giá trị cốt lõi.")
                ));
                yield new SeededFixture(
                    uploadAndAwaitCompleted("hr-public.pdf", "application/pdf", bytes, AccessLevel.PUBLIC, null, ownerToken),
                    bytes,
                    true
                );
            }
            case "hr-dept" -> {
                byte[] bytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                    List.of("Trang 1 tài liệu nội bộ phòng HR."),
                    List.of("Trang 2 tài liệu nội bộ phòng HR: hướng dẫn nghỉ phép năm.")
                ));
                yield new SeededFixture(
                    uploadAndAwaitCompleted("hr-dept.pdf", "application/pdf", bytes, AccessLevel.DEPARTMENT, null, ownerToken),
                    bytes,
                    true
                );
            }
            case "hr-confidential" -> {
                byte[] bytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
                    List.of("Trang 1 tài liệu mật phòng HR."),
                    List.of("Trang 2 tài liệu mật phòng HR: bảng ngân sách thưởng năm.")
                ));
                yield new SeededFixture(
                    uploadAndAwaitCompleted("hr-confidential.pdf", "application/pdf", bytes, AccessLevel.CONFIDENTIAL, null, ownerToken),
                    bytes,
                    true
                );
            }
            case "it-dept" -> {
                byte[] bytes = SampleEnterpriseCorpusFactory.createStructuredDocx(List.of(
                    new SampleEnterpriseCorpusFactory.DocxBlock("Heading1", "Quy chuẩn vận hành IT"),
                    new SampleEnterpriseCorpusFactory.DocxBlock(null, "Tài liệu nội bộ phòng IT về xoay vòng khóa API và bảo mật hệ thống.")
                ));
                yield new SeededFixture(
                    uploadAndAwaitCompleted(
                        "it-dept.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        bytes,
                        AccessLevel.DEPARTMENT,
                        null,
                        ownerToken
                    ),
                    bytes,
                    false
                );
            }
            default -> throw new IllegalArgumentException("Unknown targetDocKey: " + targetDocKey);
        };
    }

    static Stream<Arguments> rbacMatrixArguments() {
        return Stream.of(
            // SYSTEM_ADMIN (IT): full read & write visibility across all departments and access levels
            Arguments.of(ADMIN_EMAIL, "hr-public", true, true),
            Arguments.of(ADMIN_EMAIL, "hr-dept", true, true),
            Arguments.of(ADMIN_EMAIL, "hr-confidential", true, true),
            Arguments.of(ADMIN_EMAIL, "it-dept", true, true),

            // DEPARTMENT_MANAGER (HR): reads PUBLIC + own HR (all levels), writes ONLY own HR
            Arguments.of(HR_MANAGER_EMAIL, "hr-public", true, true),
            Arguments.of(HR_MANAGER_EMAIL, "hr-dept", true, true),
            Arguments.of(HR_MANAGER_EMAIL, "hr-confidential", true, true),
            Arguments.of(HR_MANAGER_EMAIL, "it-dept", false, false),

            // DEPARTMENT_MANAGER (IT): reads PUBLIC + own IT; blocked 403 on HR internal/confidential & cannot overwrite HR PUBLIC
            Arguments.of(IT_MANAGER_EMAIL, "hr-public", true, false),
            Arguments.of(IT_MANAGER_EMAIL, "hr-dept", false, false),
            Arguments.of(IT_MANAGER_EMAIL, "hr-confidential", false, false),
            Arguments.of(IT_MANAGER_EMAIL, "it-dept", true, true),

            // STANDARD_EMPLOYEE (HR): reads PUBLIC + own HR DEPARTMENT; blocked 403 on CONFIDENTIAL & IT; never writes
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-public", true, false),
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-dept", true, false),
            Arguments.of(HR_EMPLOYEE_EMAIL, "hr-confidential", false, false),
            Arguments.of(HR_EMPLOYEE_EMAIL, "it-dept", false, false),

            // STANDARD_EMPLOYEE (SALES): reads PUBLIC only; blocked 403 on HR & IT internal docs; never writes
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-public", true, false),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-dept", false, false),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "hr-confidential", false, false),
            Arguments.of(SALES_EMPLOYEE_EMAIL, "it-dept", false, false)
        );
    }

    private AuthTokenResponse login(String email) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("email", email, "password", SEED_DEV_PASSWORD));
        HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(resp.statusCode()).as("Login failed for %s: %s", email, resp.body()).isEqualTo(200);
        return objectMapper.readValue(resp.body(), AuthTokenResponse.class);
    }

    private DocumentResponse uploadAndAwaitCompleted(
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID departmentId,
        String bearerToken
    ) throws Exception {
        HttpResponse<String> resp = sendMultipartUpload(
            "/api/v1/documents/upload",
            "POST",
            filename,
            contentType,
            content,
            accessLevel,
            departmentId,
            bearerToken
        );
        assertThat(resp.statusCode()).as("Upload failed for %s: %s", filename, resp.body()).isEqualTo(202);
        DocumentResponse doc = objectMapper.readValue(resp.body(), DocumentResponse.class);
        DocumentStatusResponse terminal = awaitTerminalStatus(doc.id(), bearerToken, Duration.ofSeconds(15));
        assertThat(terminal.status())
            .as("Expected COMPLETED for %s but got %s (error=%s)", filename, terminal.status(), terminal.errorMessage())
            .isEqualTo(DocumentStatus.COMPLETED);
        return doc;
    }

    private HttpResponse<String> sendMultipartPut(
        String path,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        String bearerToken
    ) throws Exception {
        return sendMultipartUpload(path, "PUT", filename, contentType, content, accessLevel, null, bearerToken);
    }

    private HttpResponse<String> sendMultipartUpload(
        String path,
        String method,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID departmentId,
        String bearerToken
    ) throws Exception {
        String boundary = "----DocuMindBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] payload = buildMultipartBody(boundary, filename, contentType, content, accessLevel, departmentId);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofSeconds(15))
            .method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] buildMultipartBody(
        String boundary,
        String filename,
        String contentType,
        byte[] content,
        AccessLevel accessLevel,
        UUID departmentId
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (accessLevel != null) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"accessLevel\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((accessLevel.name() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (departmentId != null) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Disposition: form-data; name=\"departmentId\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write((departmentId + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private DocumentStatusResponse awaitTerminalStatus(UUID docId, String bearerToken, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        DocumentStatusResponse last = null;
        while (System.nanoTime() < deadline) {
            HttpResponse<String> resp = sendGet("/api/v1/documents/" + docId + "/status", bearerToken);
            assertThat(resp.statusCode()).isEqualTo(200);
            last = objectMapper.readValue(resp.body(), DocumentStatusResponse.class);
            if (last.status() == DocumentStatus.COMPLETED || last.status() == DocumentStatus.FAILED) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out awaiting terminal status for " + docId + "; last=" + last);
    }

    private ChatAskResponse askQuestion(String question, String bearerToken) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("question", question));
        HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/chat/ask"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(resp.statusCode()).as("Chat ask failed: %s", resp.body()).isEqualTo(200);
        return objectMapper.readValue(resp.body(), ChatAskResponse.class);
    }

    private List<SseEventFrame> streamQuestion(String question, String bearerToken) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("question", question));
        HttpResponse<String> resp = httpClient.send(
            HttpRequest.newBuilder(uri("/api/v1/chat/stream"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        assertThat(resp.statusCode()).as("Chat stream failed: %s", resp.body()).isEqualTo(200);
        return parseSseFrames(resp.body());
    }

    private static List<SseEventFrame> parseSseFrames(String rawBody) {
        List<SseEventFrame> frames = new ArrayList<>();
        String currentEvent = null;
        StringBuilder currentData = new StringBuilder();
        for (String rawLine : rawBody.split("\\r?\\n", -1)) {
            if (rawLine.isEmpty()) {
                if (currentEvent != null || !currentData.isEmpty()) {
                    frames.add(new SseEventFrame(currentEvent, currentData.toString()));
                    currentEvent = null;
                    currentData.setLength(0);
                }
            } else if (rawLine.startsWith("event:")) {
                currentEvent = rawLine.substring("event:".length()).trim();
            } else if (rawLine.startsWith("data:")) {
                if (!currentData.isEmpty()) {
                    currentData.append('\n');
                }
                currentData.append(rawLine.substring("data:".length()).trim());
            }
        }
        return frames;
    }

    private HttpResponse<String> sendGet(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET().timeout(Duration.ofSeconds(10));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendDelete(String path, String bearerToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).DELETE().timeout(Duration.ofSeconds(10));
        if (bearerToken != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String queryStorageKey(UUID documentId) {
        return jdbcTemplate.queryForObject(
            "SELECT storage_key FROM documents WHERE id = ?",
            String.class,
            documentId
        );
    }

    private long queryDepartmentCacheVersion(UUID departmentId) {
        Long value = jdbcTemplate.queryForObject(
            "SELECT cache_version FROM departments WHERE id = ?",
            Long.class,
            departmentId
        );
        return value != null ? value : 0L;
    }

    private int countChunksForDocument(UUID documentId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
            Integer.class,
            documentId
        );
        return count != null ? count : 0;
    }

    private void awaitChunkCountEquals(UUID documentId, int expectedCount, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        int lastCount = -1;
        while (System.nanoTime() < deadline) {
            lastCount = countChunksForDocument(documentId);
            if (lastCount == expectedCount) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(lastCount).isEqualTo(expectedCount);
    }

    private List<String> listBucketObjectKeys() {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(storageProperties.bucket())
            .build())
            .contents()
            .stream()
            .map(S3Object::key)
            .toList();
    }

    private record SeededFixture(DocumentResponse doc, byte[] rawBytes, boolean isPdf) {
    }

    private record SseEventFrame(String event, String data) {
    }
}
