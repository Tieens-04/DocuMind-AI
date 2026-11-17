package com.documind.controller;

import com.documind.controller.advice.GlobalExceptionHandler;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.controller.dto.PageResponse;
import com.documind.domain.entity.AuditLogEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.AuditActionType;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.event.DocumentUploadedEvent;
import com.documind.exception.DocumentStorageException;
import com.documind.exception.DocumentTooLargeException;
import com.documind.infrastructure.storage.HashingBoundedInputStream;
import com.documind.infrastructure.storage.MinioStorageService;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.repository.AuditLogRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.StreamingMultipartTestSupport;
import com.documind.support.StreamingMultipartTestSupport.RepeatingPdfInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "documind.ingestion.worker.enabled=false"
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    DocumentCrudAndMinioStreamingIntegrationTest.UploadedEventCollectorConfig.class
})
class DocumentCrudAndMinioStreamingIntegrationTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID HR_MANAGER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALES_EMPLOYEE_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final long ONE_MIB_BYTES = 1024L * 1024L;
    private static final long FORTY_MIB_BYTES = 40L * 1024L * 1024L;
    private static final long MAX_BOUNDED_HEAP_DELTA_BYTES = 20L * 1024L * 1024L;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private DocumentChunkVectorRepository chunkVectorRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UploadedEventCollector eventCollector;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @BeforeEach
    @AfterEach
    void cleanUpEnvironment() {
        deleteAllBucketObjects();
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
        eventCollector.clear();
    }

    @Test
    void standardUploadCompletesUnder300msEmitsEventsAuditsAndRoundTripsPresignedInlineGet() throws Exception {
        // Warm up servlet, multipart parser, Tika detector, HikariCP, and S3 connection pool
        ResponseEntity<DocumentResponse> warmup = uploadBytes(
            "warmup.txt",
            "text/plain",
            "Warmup payload for JIT and S3 pool".getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(warmup.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        eventCollector.clear();
        long cacheVersionBefore = departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion();

        byte[] markdownBytes = (
            "# Quy chế nghỉ phép năm 2026\n"
                + "- Nhân viên chính thức được hưởng 12 ngày nghỉ phép nguyên lương.\n"
                + "- Mọi tài liệu nội bộ phải được lưu trữ an toàn trên hệ thống.\n"
        ).getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = sha256Hex(markdownBytes);

        long startedNanos = System.nanoTime();
        ResponseEntity<DocumentResponse> uploadResp = uploadBytes(
            "quy-che-nghi-phep.md",
            "text/markdown",
            markdownBytes,
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(elapsedMs).isLessThan(300L);

        DocumentResponse created = uploadResp.getBody();
        assertThat(created).isNotNull();
        assertThat(created.id()).isNotNull();
        assertThat(created.departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(created.originalName()).isEqualTo("quy-che-nghi-phep.md");
        assertThat(created.fileExtension()).isEqualTo("md");
        assertThat(created.mimeType()).isEqualTo("text/markdown");
        assertThat(created.fileSizeBytes()).isEqualTo(markdownBytes.length);
        assertThat(created.fileHash()).isEqualTo(expectedSha256);
        assertThat(created.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(created.currentVersion()).isEqualTo(1);
        assertThat(created.chunkCount()).isZero();

        assertThat(eventCollector.events()).hasSize(1);
        DocumentUploadedEvent publishedEvent = eventCollector.events().getFirst();
        assertThat(publishedEvent.documentId()).isEqualTo(created.id());
        assertThat(publishedEvent.departmentId()).isEqualTo(IT_DEPT_ID);
        assertThat(publishedEvent.originalName()).isEqualTo("quy-che-nghi-phep.md");
        assertThat(publishedEvent.fileExtension()).isEqualTo("md");
        assertThat(publishedEvent.accessLevel()).isEqualTo(AccessLevel.DEPARTMENT);
        assertThat(publishedEvent.version()).isEqualTo(1);

        long cacheVersionAfter = departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion();
        assertThat(cacheVersionAfter).isEqualTo(cacheVersionBefore + 1L);

        List<AuditLogEntity> uploadAudits = auditLogRepository
            .findByActionTypeOrderByCreatedAtDesc(AuditActionType.DOC_UPLOAD, PageRequest.of(0, 10))
            .getContent();
        assertThat(uploadAudits)
            .anySatisfy(audit -> {
                assertThat(audit.getDepartmentId()).isEqualTo(IT_DEPT_ID);
                assertThat(audit.getStatusCode()).isEqualTo(202);
                assertThat(audit.getQueryText()).isEqualTo("quy-che-nghi-phep.md");
            });

        DocumentEntity persisted = documentRepository.findById(created.id()).orElseThrow();
        URL presignedUrl = minioStorageService.generatePresignedGetUrl(persisted.getStorageKey(), persisted.getOriginalName());
        HttpResponse<byte[]> downloaded;
        try (HttpClient httpClient = StreamingMultipartTestSupport.newHttp11Client()) {
            downloaded = httpClient.send(
                HttpRequest.newBuilder(presignedUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
        }
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.headers().firstValue("Content-Disposition"))
            .isPresent()
            .get()
            .satisfies(header -> assertThat(header).startsWith("inline"));
        assertThat(downloaded.body()).isEqualTo(markdownBytes);
    }

    @Test
    void deduplicatesWithinDepartmentCleansOrphanS3ObjectAndAllowsCrossDepartmentUpload() throws Exception {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
        String expectedHash = sha256Hex(pdfBytes);

        ResponseEntity<DocumentResponse> firstUpload = uploadBytes(
            "it-security-policy.pdf",
            "application/pdf",
            pdfBytes,
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(firstUpload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID firstDocId = Objects.requireNonNull(firstUpload.getBody()).id();

        ResponseEntity<ProblemDetail> duplicateResponse = uploadBytesForError(
            "it-security-policy-copy.pdf",
            "application/pdf",
            pdfBytes,
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail duplicateProblem = duplicateResponse.getBody();
        assertThat(duplicateProblem).isNotNull();
        assertThat(duplicateProblem.getStatus()).isEqualTo(409);
        assertThat(duplicateProblem.getProperties()).containsEntry("errorCode", "DUPLICATE_DOCUMENT");
        assertThat(duplicateProblem.getProperties().get("existingDocumentId").toString())
            .isEqualTo(firstDocId.toString());
        assertThat(duplicateProblem.getProperties()).containsEntry("fileHash", expectedHash);
        assertThat(duplicateProblem.getDetail()).doesNotContain(IT_DEPT_ID.toString());

        ListObjectsV2Response itObjects = s3Client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(storageProperties.bucket())
            .prefix("departments/" + IT_DEPT_ID + "/")
            .build());
        assertThat(itObjects.contents()).hasSize(1);

        ResponseEntity<DocumentResponse> crossDeptUpload = uploadBytes(
            "hr-copy.pdf",
            "application/pdf",
            pdfBytes,
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(crossDeptUpload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse hrDoc = crossDeptUpload.getBody();
        assertThat(hrDoc).isNotNull();
        assertThat(hrDoc.id()).isNotEqualTo(firstDocId);
        assertThat(hrDoc.departmentId()).isEqualTo(HR_DEPT_ID);
        assertThat(hrDoc.fileHash()).isEqualTo(expectedHash);
    }

    @Test
    void rejectsSpoofedMimeEmptyFileMissingPartAndInvalidPathParametersWithRfc7807() {
        byte[] pngBytes = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R'
        };
        ResponseEntity<ProblemDetail> spoofedResp = uploadBytesForError(
            "fake.pdf",
            "application/pdf",
            pngBytes,
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(spoofedResp.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        ProblemDetail spoofedProblem = spoofedResp.getBody();
        assertThat(spoofedProblem).isNotNull();
        assertThat(spoofedProblem.getStatus()).isEqualTo(415);
        assertThat(spoofedProblem.getProperties()).containsEntry("errorCode", "UNSUPPORTED_DOCUMENT_TYPE");
        assertThat(spoofedProblem.getProperties()).containsEntry("detectedMimeType", "image/png");

        ResponseEntity<ProblemDetail> emptyResp = uploadBytesForError(
            "empty.pdf",
            "application/pdf",
            new byte[0],
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(emptyResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail emptyProblem = emptyResp.getBody();
        assertThat(emptyProblem).isNotNull();
        assertThat(emptyProblem.getStatus()).isEqualTo(400);
        assertThat(emptyProblem.getProperties()).containsEntry("errorCode", "INVALID_DOCUMENT_REQUEST");

        // Missing "file" multipart part -> MissingServletRequestPartException -> 400
        MultiValueMap<String, Object> missingFileBody = new LinkedMultiValueMap<>();
        missingFileBody.add("departmentId", IT_DEPT_ID.toString());
        HttpHeaders multipartHeaders = new HttpHeaders();
        multipartHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ProblemDetail> missingPartResp = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(missingFileBody, multipartHeaders),
            ProblemDetail.class
        );
        assertThat(missingPartResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Objects.requireNonNull(missingPartResp.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_DOCUMENT_REQUEST");

        // Invalid UUID path variable -> MethodArgumentTypeMismatchException -> 400
        ResponseEntity<ProblemDetail> badUuidResp = restTemplate.getForEntity(
            "/api/v1/documents/not-a-uuid/status",
            ProblemDetail.class
        );
        assertThat(badUuidResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Objects.requireNonNull(badUuidResp.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_DOCUMENT_REQUEST");

        // Unknown document UUID -> DocumentNotFoundException -> 404
        ResponseEntity<ProblemDetail> notFoundResp = restTemplate.getForEntity(
            "/api/v1/documents/" + UUID.randomUUID() + "/status",
            ProblemDetail.class
        );
        assertThat(notFoundResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Objects.requireNonNull(notFoundResp.getBody()).getProperties())
            .containsEntry("errorCode", "RESOURCE_NOT_FOUND");

        // Unknown departmentId -> ResourceNotFoundException -> 404
        ResponseEntity<ProblemDetail> unknownDeptResp = uploadBytesForError(
            "orphan-dept.txt",
            "text/plain",
            "Valid text".getBytes(StandardCharsets.UTF_8),
            UUID.randomUUID(),
            AccessLevel.DEPARTMENT
        );
        assertThat(unknownDeptResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Objects.requireNonNull(unknownDeptResp.getBody()).getProperties())
            .containsEntry("errorCode", "RESOURCE_NOT_FOUND");

        // Malformed multipart request -> MultipartException -> 400
        HttpHeaders malformedHeaders = new HttpHeaders();
        malformedHeaders.set("Content-Type", "multipart/form-data; boundary=invalidboundary");
        ResponseEntity<ProblemDetail> malformedResp = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>("not-a-valid-multipart-payload", malformedHeaders),
            ProblemDetail.class
        );
        assertThat(malformedResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(Objects.requireNonNull(malformedResp.getBody()).getProperties())
            .containsEntry("errorCode", "INVALID_DOCUMENT_REQUEST");

        // Domain 413 & 502 mappings -> ProblemDetail
        MockHttpServletRequest mockReq = new MockHttpServletRequest("POST", "/api/v1/documents/upload");
        ResponseEntity<ProblemDetail> tooLargeDomainResp = globalExceptionHandler.handleDocumentTooLarge(
            new DocumentTooLargeException(HashingBoundedInputStream.MAX_FILE_SIZE_BYTES, "Exceeds 50 MiB limit"),
            mockReq
        );
        assertThat(tooLargeDomainResp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(Objects.requireNonNull(tooLargeDomainResp.getBody()).getProperties())
            .containsEntry("errorCode", "DOCUMENT_TOO_LARGE")
            .containsEntry("maxSizeBytes", HashingBoundedInputStream.MAX_FILE_SIZE_BYTES);

        ResponseEntity<ProblemDetail> storageDownResp = globalExceptionHandler.handleStorageUnavailable(
            new DocumentStorageException("MinIO unreachable", new RuntimeException("connection refused")),
            mockReq
        );
        assertThat(storageDownResp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(Objects.requireNonNull(storageDownResp.getBody()).getProperties())
            .containsEntry("errorCode", "STORAGE_UNAVAILABLE");

        // OpenAPI spec renders cleanly with Documents endpoints
        ResponseEntity<String> openApiResp = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(openApiResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openApiResp.getBody())
            .contains("/api/v1/documents/upload")
            .contains("/api/v1/documents/{id}/status")
            .contains("Documents");

        // Unsupported HTTP method returns 405 RFC 7807 ProblemDetail instead of unformatted framework error
        ResponseEntity<ProblemDetail> methodNotAllowedResp = restTemplate.exchange(
            "/api/v1/documents",
            HttpMethod.PUT,
            HttpEntity.EMPTY,
            ProblemDetail.class
        );
        assertThat(methodNotAllowedResp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(methodNotAllowedResp.getHeaders().getAllow()).contains(HttpMethod.GET);
        ProblemDetail methodProblem = Objects.requireNonNull(methodNotAllowedResp.getBody());
        assertThat(methodProblem.getStatus()).isEqualTo(405);
        assertThat(methodProblem.getProperties()).containsEntry("errorCode", "REQUEST_ERROR");
    }

    @Test
    void rejectsOversizedUploadAbove50MiBWith413ProblemDetail() throws Exception {
        long oversizedBytes = HashingBoundedInputStream.MAX_FILE_SIZE_BYTES + 1L;
        HttpRequest request = StreamingMultipartTestSupport.buildMultipartUploadRequest(
            URI.create("http://localhost:" + port + "/api/v1/documents/upload"),
            "----DocuMindTooLarge",
            "oversized-50mib-plus-1.pdf",
            "application/pdf",
            () -> new RepeatingPdfInputStream(oversizedBytes),
            Duration.ofSeconds(120),
            IT_DEPT_ID
        );

        HttpResponse<String> response;
        try (HttpClient httpClient = StreamingMultipartTestSupport.newHttp11Client()) {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        assertThat(response.statusCode()).isEqualTo(413);

        ProblemDetail problem = objectMapper.readValue(response.body(), ProblemDetail.class);
        assertThat(problem.getStatus()).isEqualTo(413);
        assertThat(problem.getProperties())
            .containsEntry("errorCode", "DOCUMENT_TOO_LARGE");
        assertThat(((Number) problem.getProperties().get("maxSizeBytes")).longValue())
            .isEqualTo(HashingBoundedInputStream.MAX_FILE_SIZE_BYTES);
    }

    @Test
    void filtersPaginatesEnforcesCrossDepartmentAccessAndHardDeletesCascadeVectorsAndMinio() throws Exception {
        DocumentResponse itDeptDoc = Objects.requireNonNull(uploadBytes(
            "it-runbook.txt",
            "text/plain",
            "IT internal runbook content".getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ).getBody());
        Thread.sleep(5);

        DocumentResponse itConfidentialDoc = Objects.requireNonNull(uploadBytes(
            "it-secrets.txt",
            "text/plain",
            "IT confidential topology".getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.CONFIDENTIAL
        ).getBody());
        Thread.sleep(5);

        DocumentResponse hrPublicDoc = Objects.requireNonNull(uploadBytes(
            "hr-holiday.md",
            "text/markdown",
            "# Lịch nghỉ lễ toàn công ty\n- Quốc khánh 2/9".getBytes(StandardCharsets.UTF_8),
            HR_DEPT_ID,
            AccessLevel.PUBLIC
        ).getBody());
        Thread.sleep(5);

        DocumentResponse hrConfidentialDoc = Objects.requireNonNull(uploadBytes(
            "hr-salaries.txt",
            "text/plain",
            "HR confidential compensation matrix".getBytes(StandardCharsets.UTF_8),
            HR_DEPT_ID,
            AccessLevel.CONFIDENTIAL
        ).getBody());

        HttpHeaders itHeaders = new HttpHeaders();
        itHeaders.set("X-Department-Id", IT_DEPT_ID.toString());
        HttpHeaders hrHeaders = new HttpHeaders();
        hrHeaders.set("X-Department-Id", HR_DEPT_ID.toString());

        // 1. Default IT caller listing excludes HR CONFIDENTIAL while keeping HR PUBLIC and own IT docs
        ResponseEntity<PageResponse<DocumentResponse>> itScopePage = restTemplate.exchange(
            "/api/v1/documents?page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(itHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(itScopePage.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<DocumentResponse> itVisibleDocs = Objects.requireNonNull(itScopePage.getBody());
        assertThat(itVisibleDocs.totalElements()).isEqualTo(3L);
        assertThat(itVisibleDocs.items())
            .extracting(DocumentResponse::id)
            .containsExactly(hrPublicDoc.id(), itConfidentialDoc.id(), itDeptDoc.id())
            .doesNotContain(hrConfidentialDoc.id());

        // 2. HR caller cannot widen scope using ?departmentId=<IT_DEPT_ID>
        ResponseEntity<PageResponse<DocumentResponse>> hrCrossDeptFilterAttempt = restTemplate.exchange(
            "/api/v1/documents?departmentId=" + IT_DEPT_ID + "&page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(hrHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(hrCrossDeptFilterAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(hrCrossDeptFilterAttempt.getBody()).totalElements()).isZero();
        assertThat(hrCrossDeptFilterAttempt.getBody().items()).isEmpty();

        // 3. SYSTEM_ADMIN sees all 4 documents across departments
        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.set("X-Department-Id", IT_DEPT_ID.toString());
        adminHeaders.set("X-User-Role", "SYSTEM_ADMIN");
        ResponseEntity<PageResponse<DocumentResponse>> adminPage = restTemplate.exchange(
            "/api/v1/documents?page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(adminHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(adminPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(adminPage.getBody()).totalElements()).isEqualTo(4L);

        // 4. Non-null filters (departmentId + status + accessLevel) intersected with caller visibility
        ResponseEntity<PageResponse<DocumentResponse>> filteredPage = restTemplate.exchange(
            "/api/v1/documents?departmentId=" + IT_DEPT_ID + "&status=PENDING&accessLevel=DEPARTMENT&page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(itHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(filteredPage.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<DocumentResponse> itFiltered = Objects.requireNonNull(filteredPage.getBody());
        assertThat(itFiltered.totalElements()).isEqualTo(1L);
        assertThat(itFiltered.items()).extracting(DocumentResponse::id).containsExactly(itDeptDoc.id());

        // 5. STANDARD_EMPLOYEE RBAC: excludes own-dept CONFIDENTIAL from list & status (403), 403 on upload/delete, 200 on DEPARTMENT/PUBLIC
        HttpHeaders itEmployeeHeaders = new HttpHeaders();
        itEmployeeHeaders.set("X-Department-Id", IT_DEPT_ID.toString());
        itEmployeeHeaders.set("X-User-Role", "STANDARD_EMPLOYEE");

        ResponseEntity<PageResponse<DocumentResponse>> itEmployeeList = restTemplate.exchange(
            "/api/v1/documents?page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(itEmployeeList.getStatusCode()).isEqualTo(HttpStatus.OK);
        PageResponse<DocumentResponse> employeeVisibleDocs = Objects.requireNonNull(itEmployeeList.getBody());
        assertThat(employeeVisibleDocs.totalElements()).isEqualTo(2L);
        assertThat(employeeVisibleDocs.items())
            .extracting(DocumentResponse::id)
            .containsExactly(hrPublicDoc.id(), itDeptDoc.id())
            .doesNotContain(itConfidentialDoc.id(), hrConfidentialDoc.id());

        ResponseEntity<PageResponse<DocumentResponse>> employeeConfidentialFilter = restTemplate.exchange(
            "/api/v1/documents?accessLevel=CONFIDENTIAL&page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            new ParameterizedTypeReference<>() {}
        );
        assertThat(employeeConfidentialFilter.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Objects.requireNonNull(employeeConfidentialFilter.getBody()).totalElements()).isZero();
        assertThat(employeeConfidentialFilter.getBody().items()).isEmpty();

        ResponseEntity<ProblemDetail> employeeOwnConfidentialStatus = restTemplate.exchange(
            "/api/v1/documents/" + itConfidentialDoc.id() + "/status",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            ProblemDetail.class
        );
        assertThat(employeeOwnConfidentialStatus.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail ownConfidentialProblem = Objects.requireNonNull(employeeOwnConfidentialStatus.getBody());
        assertThat(ownConfidentialProblem.getProperties()).containsEntry("errorCode", "DOCUMENT_ACCESS_DENIED");
        assertThat(ownConfidentialProblem.getDetail())
            .doesNotContain(IT_DEPT_ID.toString())
            .doesNotContain(itConfidentialDoc.id().toString());

        ResponseEntity<ProblemDetail> employeeUploadForbidden = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            buildMultipartEntity("emp.txt", "text/plain", "Denied".getBytes(StandardCharsets.UTF_8), IT_DEPT_ID, AccessLevel.DEPARTMENT, "STANDARD_EMPLOYEE", null),
            ProblemDetail.class
        );
        assertThat(employeeUploadForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ProblemDetail> employeeDeleteForbidden = restTemplate.exchange(
            "/api/v1/documents/" + itDeptDoc.id(),
            HttpMethod.DELETE,
            new HttpEntity<>(itEmployeeHeaders),
            ProblemDetail.class
        );
        assertThat(employeeDeleteForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<DocumentStatusResponse> employeeOwnStatusOk = restTemplate.exchange(
            "/api/v1/documents/" + itDeptDoc.id() + "/status",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            DocumentStatusResponse.class
        );
        assertThat(employeeOwnStatusOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<DocumentStatusResponse> employeePublicStatusOk = restTemplate.exchange(
            "/api/v1/documents/" + hrPublicDoc.id() + "/status",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            DocumentStatusResponse.class
        );
        assertThat(employeePublicStatusOk.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<ProblemDetail> employeeCrossConfidentialStatus = restTemplate.exchange(
            "/api/v1/documents/" + hrConfidentialDoc.id() + "/status",
            HttpMethod.GET,
            new HttpEntity<>(itEmployeeHeaders),
            ProblemDetail.class
        );
        assertThat(employeeCrossConfidentialStatus.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ProblemDetail crossStatusProblem = Objects.requireNonNull(employeeCrossConfidentialStatus.getBody());
        assertThat(crossStatusProblem.getProperties()).containsEntry("errorCode", "DOCUMENT_ACCESS_DENIED");
        assertThat(crossStatusProblem.getDetail())
            .doesNotContain(IT_DEPT_ID.toString())
            .doesNotContain(HR_DEPT_ID.toString())
            .doesNotContain(hrConfidentialDoc.id().toString());

        // 5b. Privilege escalation via X-User-Role header above DB user role is rejected (403) on upload and list
        ResponseEntity<ProblemDetail> roleEscalatedUploadForbidden = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            buildMultipartEntity(
                "escalated.txt",
                "text/plain",
                "Denied".getBytes(StandardCharsets.UTF_8),
                SALES_DEPT_ID,
                AccessLevel.DEPARTMENT,
                "DEPARTMENT_MANAGER",
                SALES_EMPLOYEE_USER_ID
            ),
            ProblemDetail.class
        );
        assertThat(roleEscalatedUploadForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders escalatedListHeaders = new HttpHeaders();
        escalatedListHeaders.set("X-Department-Id", SALES_DEPT_ID.toString());
        escalatedListHeaders.set("X-User-Id", SALES_EMPLOYEE_USER_ID.toString());
        escalatedListHeaders.set("X-User-Role", "SYSTEM_ADMIN");
        ResponseEntity<ProblemDetail> roleEscalatedListForbidden = restTemplate.exchange(
            "/api/v1/documents?page=0&size=10",
            HttpMethod.GET,
            new HttpEntity<>(escalatedListHeaders),
            ProblemDetail.class
        );
        assertThat(roleEscalatedListForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // 6. IT manager cannot upload into HR via ?departmentId= or spoof HR manager X-User-Id
        ResponseEntity<ProblemDetail> crossDeptUploadQueryForbidden = restTemplate.postForEntity(
            "/api/v1/documents/upload?departmentId=" + HR_DEPT_ID,
            buildMultipartEntity("cross-target.txt", "text/plain", "Denied".getBytes(StandardCharsets.UTF_8), IT_DEPT_ID, AccessLevel.DEPARTMENT, "DEPARTMENT_MANAGER", null),
            ProblemDetail.class
        );
        assertThat(crossDeptUploadQueryForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<ProblemDetail> spoofedActorUploadForbidden = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            buildMultipartEntity("spoofed-actor.txt", "text/plain", "Denied".getBytes(StandardCharsets.UTF_8), IT_DEPT_ID, AccessLevel.DEPARTMENT, "DEPARTMENT_MANAGER", HR_MANAGER_USER_ID),
            ProblemDetail.class
        );
        assertThat(spoofedActorUploadForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // Cross-department DELETE is forbidden even on PUBLIC documents (and detail leaks no UUIDs)
        ResponseEntity<ProblemDetail> crossDeptDeleteForbidden = restTemplate.exchange(
            "/api/v1/documents/" + hrPublicDoc.id(),
            HttpMethod.DELETE,
            new HttpEntity<>(itHeaders),
            ProblemDetail.class
        );
        assertThat(crossDeptDeleteForbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(Objects.requireNonNull(crossDeptDeleteForbidden.getBody()).getDetail())
            .doesNotContain(IT_DEPT_ID.toString())
            .doesNotContain(HR_DEPT_ID.toString())
            .doesNotContain(hrPublicDoc.id().toString());

        // 7. Insert pgvector chunk -> DELETE returns 204, cascades chunks to 0, records DOC_DELETE, removes S3 object
        Document chunk = new Document(
            "IT internal runbook vector chunk",
            Map.of(
                "document_id", itDeptDoc.id().toString(),
                "department_id", IT_DEPT_ID.toString(),
                "access_level", "DEPARTMENT",
                "file_name", itDeptDoc.originalName(),
                "page_number", 1,
                "chunk_index", 0,
                "doc_version", 1
            )
        );
        vectorStore.add(List.of(chunk));
        assertThat(chunkVectorRepository.countByDocumentId(itDeptDoc.id())).isEqualTo(1L);

        String itStorageKey = documentRepository.findById(itDeptDoc.id()).orElseThrow().getStorageKey();
        long cacheVersionBeforeDelete = departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion();

        HttpHeaders unknownUserItHeaders = new HttpHeaders();
        unknownUserItHeaders.set("X-Department-Id", IT_DEPT_ID.toString());
        unknownUserItHeaders.set("X-User-Id", UUID.randomUUID().toString());
        ResponseEntity<ProblemDetail> unknownUserDeleteResp = restTemplate.exchange(
            "/api/v1/documents/" + itDeptDoc.id(),
            HttpMethod.DELETE,
            new HttpEntity<>(unknownUserItHeaders),
            ProblemDetail.class
        );
        assertThat(unknownUserDeleteResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(Objects.requireNonNull(unknownUserDeleteResp.getBody()).getProperties())
            .containsEntry("errorCode", "RESOURCE_NOT_FOUND");
        assertThat(documentRepository.findById(itDeptDoc.id())).isPresent();
        assertThat(chunkVectorRepository.countByDocumentId(itDeptDoc.id())).isEqualTo(1L);
        assertThat(departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion())
            .isEqualTo(cacheVersionBeforeDelete);
        assertThat(s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(itStorageKey)
            .build()).contentLength()).isPositive();

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
            "/api/v1/documents/" + itDeptDoc.id(),
            HttpMethod.DELETE,
            new HttpEntity<>(itHeaders),
            Void.class
        );
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(documentRepository.findById(itDeptDoc.id())).isEmpty();
        assertThat(chunkVectorRepository.countByDocumentId(itDeptDoc.id())).isZero();
        assertThat(departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion())
            .isEqualTo(cacheVersionBeforeDelete + 1L);

        List<AuditLogEntity> deleteAudits = auditLogRepository
            .findByActionTypeOrderByCreatedAtDesc(AuditActionType.DOC_DELETE, PageRequest.of(0, 10))
            .getContent();
        assertThat(deleteAudits)
            .anySatisfy(audit -> {
                assertThat(audit.getDepartmentId()).isEqualTo(IT_DEPT_ID);
                assertThat(audit.getStatusCode()).isEqualTo(204);
                assertThat(audit.getQueryText()).isEqualTo("it-runbook.txt");
            });

        assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(itStorageKey)
            .build()))
            .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void streams40MiBPdfWithoutMaterializingHeapBufferAndMeetsWarmLatency() throws Exception {
        // Warm up multipart + S3 path before measuring latency and heap peak
        ResponseEntity<DocumentResponse> warmup = uploadBytes(
            "heap-warmup.pdf",
            "application/pdf",
            "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII),
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(warmup.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Warm 1 MiB PDF streaming upload must complete under 300ms
        try (HttpClient httpClient = StreamingMultipartTestSupport.newHttp11Client()) {
            HttpRequest oneMibRequest = StreamingMultipartTestSupport.buildMultipartUploadRequest(
                URI.create("http://localhost:" + port + "/api/v1/documents/upload?accessLevel=DEPARTMENT"),
                "----DocuMind1MiB",
                "warm-1mib.pdf",
                "application/pdf",
                () -> new RepeatingPdfInputStream(ONE_MIB_BYTES, "1mib-warm"),
                Duration.ofSeconds(30),
                HR_DEPT_ID
            );
            long oneMibStartedNanos = System.nanoTime();
            HttpResponse<String> oneMibResponse = httpClient.send(
                oneMibRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            long oneMibElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - oneMibStartedNanos);
            assertThat(oneMibResponse.statusCode()).isEqualTo(202);
            assertThat(oneMibElapsedMs)
                .as("Warm 1 MiB PDF streaming upload latency (ms)")
                .isLessThan(300L);

            long heapBefore = settledUsedHeapBytes();
            List<MemoryPoolMXBean> oldGenPools = oldGenHeapPools();
            long oldGenUsedBefore = sumUsedHeap(oldGenPools);
            oldGenPools.forEach(MemoryPoolMXBean::resetPeakUsage);

            HttpRequest fortyMibRequest = StreamingMultipartTestSupport.buildMultipartUploadRequest(
                URI.create("http://localhost:" + port + "/api/v1/documents/upload?accessLevel=DEPARTMENT"),
                "----DocuMind40MiB",
                "large-40mib.pdf",
                "application/pdf",
                () -> new RepeatingPdfInputStream(FORTY_MIB_BYTES),
                Duration.ofSeconds(120),
                IT_DEPT_ID
            );

            HttpResponse<String> response = httpClient.send(
                fortyMibRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(response.statusCode()).isEqualTo(202);

            DocumentResponse created = objectMapper.readValue(response.body(), DocumentResponse.class);
            assertThat(created.fileSizeBytes()).isEqualTo(FORTY_MIB_BYTES);
            assertThat(created.status()).isEqualTo(DocumentStatus.PENDING);
            assertThat(created.mimeType()).isEqualTo("application/pdf");

            String storageKey = documentRepository.findById(created.id()).orElseThrow().getStorageKey();
            HeadObjectResponse headObject = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(storageKey)
                .build());
            assertThat(headObject.contentLength()).isEqualTo(FORTY_MIB_BYTES);

            long oldGenPeakAfter = sumPeakUsedHeap(oldGenPools);
            long oldGenPeakDelta = oldGenPeakAfter - oldGenUsedBefore;
            assertThat(oldGenPeakDelta)
                .as("Old-Gen peak heap growth during 40 MiB streaming upload")
                .isLessThan(MAX_BOUNDED_HEAP_DELTA_BYTES);

            long heapAfter = settledUsedHeapBytes();
            long postGcHeapDelta = heapAfter - heapBefore;
            assertThat(postGcHeapDelta)
                .as("Post-GC settled heap growth after 40 MiB streaming upload")
                .isLessThan(MAX_BOUNDED_HEAP_DELTA_BYTES);
        }
    }

    @Test
    void concurrentDuplicateUploadsResolveCleanlyWithSingleWinner409LosersAndZeroOrphanObjects() throws Exception {
        final int concurrency = 4;
        final long payloadSizeBytes = 512L * 1024L;
        final String marker = "concurrent-race-" + UUID.randomUUID();
        String expectedSha256;
        try (InputStream stream = new RepeatingPdfInputStream(payloadSizeBytes, marker)) {
            expectedSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes()));
        }

        // Warm servlet + S3 client before barrier race so all threads hit Phase 2 DB uniqueness concurrently
        ResponseEntity<DocumentResponse> warmup = uploadBytes(
            "race-warmup.txt",
            "text/plain",
            "Warmup".getBytes(StandardCharsets.UTF_8),
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        assertThat(warmup.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        eventCollector.clear();

        CyclicBarrier startGate = new CyclicBarrier(concurrency);
        List<HttpResponse<String>> responses = new ArrayList<>(concurrency);
        try (HttpClient httpClient = StreamingMultipartTestSupport.newHttp11Client();
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                final int attempt = i;
                futures.add(executor.submit(() -> {
                    HttpRequest request = StreamingMultipartTestSupport.buildMultipartUploadRequest(
                        URI.create("http://localhost:" + port + "/api/v1/documents/upload?accessLevel=DEPARTMENT"),
                        "----DocuMindRace" + attempt,
                        "concurrent-policy-" + attempt + ".pdf",
                        "application/pdf",
                        () -> new RepeatingPdfInputStream(payloadSizeBytes, marker),
                        Duration.ofSeconds(60),
                        IT_DEPT_ID
                    );
                    startGate.await(10, TimeUnit.SECONDS);
                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                }));
            }
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        List<HttpResponse<String>> accepted = responses.stream()
            .filter(resp -> resp.statusCode() == HttpStatus.ACCEPTED.value())
            .toList();
        List<HttpResponse<String>> conflicts = responses.stream()
            .filter(resp -> resp.statusCode() == HttpStatus.CONFLICT.value())
            .toList();

        assertThat(accepted).hasSize(1);
        assertThat(conflicts).hasSize(concurrency - 1);

        DocumentResponse winner = objectMapper.readValue(accepted.getFirst().body(), DocumentResponse.class);
        assertThat(winner.id()).isNotNull();
        assertThat(winner.fileHash()).isEqualTo(expectedSha256);

        for (HttpResponse<String> conflict : conflicts) {
            ProblemDetail problem = objectMapper.readValue(conflict.body(), ProblemDetail.class);
            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getProperties()).containsEntry("errorCode", "DUPLICATE_DOCUMENT");
            assertThat(problem.getProperties().get("existingDocumentId").toString())
                .isEqualTo(winner.id().toString());
            assertThat(problem.getProperties()).containsEntry("fileHash", expectedSha256);
            assertThat(problem.getDetail()).doesNotContain(IT_DEPT_ID.toString());
        }

        assertThat(documentRepository.findByDepartmentIdOrderByCreatedAtDesc(IT_DEPT_ID))
            .extracting(DocumentEntity::getId)
            .containsExactly(winner.id());

        ListObjectsV2Response itObjects = s3Client.listObjectsV2(ListObjectsV2Request.builder()
            .bucket(storageProperties.bucket())
            .prefix("departments/" + IT_DEPT_ID + "/")
            .build());
        assertThat(itObjects.contents()).hasSize(1);

        assertThat(eventCollector.events()).hasSize(1);
        assertThat(eventCollector.events().getFirst().documentId()).isEqualTo(winner.id());
    }

    private static List<MemoryPoolMXBean> oldGenHeapPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .filter(pool -> {
                String name = pool.getName();
                return name != null && (name.contains("Old") || name.contains("Tenured"));
            })
            .toList();
    }

    private static long sumUsedHeap(List<MemoryPoolMXBean> pools) {
        long total = 0L;
        for (MemoryPoolMXBean pool : pools) {
            total += Math.max(0L, pool.getUsage().getUsed());
        }
        return total;
    }

    private static long sumPeakUsedHeap(List<MemoryPoolMXBean> pools) {
        long total = 0L;
        for (MemoryPoolMXBean pool : pools) {
            total += Math.max(0L, pool.getPeakUsage().getUsed());
        }
        return total;
    }

    private ResponseEntity<DocumentResponse> uploadBytes(
        String filename,
        String contentType,
        byte[] bytes,
        UUID departmentId,
        AccessLevel accessLevel
    ) {
        return restTemplate.postForEntity(
            "/api/v1/documents/upload",
            buildMultipartEntity(filename, contentType, bytes, departmentId, accessLevel),
            DocumentResponse.class
        );
    }

    private ResponseEntity<ProblemDetail> uploadBytesForError(
        String filename,
        String contentType,
        byte[] bytes,
        UUID departmentId,
        AccessLevel accessLevel
    ) {
        return restTemplate.postForEntity(
            "/api/v1/documents/upload",
            buildMultipartEntity(filename, contentType, bytes, departmentId, accessLevel),
            ProblemDetail.class
        );
    }

    private static HttpEntity<MultiValueMap<String, Object>> buildMultipartEntity(
        String filename,
        String contentType,
        byte[] bytes,
        UUID departmentId,
        AccessLevel accessLevel
    ) {
        return buildMultipartEntity(filename, contentType, bytes, departmentId, accessLevel, null, null);
    }

    private static HttpEntity<MultiValueMap<String, Object>> buildMultipartEntity(
        String filename,
        String contentType,
        byte[] bytes,
        UUID departmentId,
        AccessLevel accessLevel,
        String userRole,
        UUID userId
    ) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        if (accessLevel != null) {
            body.add("accessLevel", accessLevel.name());
        }

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (departmentId != null) {
            requestHeaders.set("X-Department-Id", departmentId.toString());
        }
        if (userRole != null) {
            requestHeaders.set("X-User-Role", userRole);
        }
        if (userId != null) {
            requestHeaders.set("X-User-Id", userId.toString());
        }
        return new HttpEntity<>(body, requestHeaders);
    }

    private void deleteAllBucketObjects() {
        String bucket = storageProperties.bucket();
        String continuationToken = null;
        do {
            ListObjectsV2Response listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .continuationToken(continuationToken)
                .build());
            for (S3Object obj : listing.contents()) {
                s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(obj.key())
                    .build());
            }
            continuationToken = listing.nextContinuationToken();
        } while (continuationToken != null && !continuationToken.isBlank());
    }

    private static long settledUsedHeapBytes() throws InterruptedException {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        System.gc();
        Thread.sleep(50L);
        System.gc();
        Thread.sleep(50L);
        return memoryBean.getHeapMemoryUsage().getUsed();
    }

    private static String sha256Hex(byte[] payload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UploadedEventCollectorConfig {

        @Bean
        UploadedEventCollector uploadedEventCollector() {
            return new UploadedEventCollector();
        }
    }

    static class UploadedEventCollector {

        private final List<DocumentUploadedEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        public void onDocumentUploaded(DocumentUploadedEvent event) {
            events.add(event);
        }

        List<DocumentUploadedEvent> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }
}
