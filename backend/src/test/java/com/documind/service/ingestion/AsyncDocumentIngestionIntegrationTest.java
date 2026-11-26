package com.documind.service.ingestion;

import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.config.IngestionAsyncConfig;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true",
        "documind.ingestion.worker.batch-size=50",
        "documind.ingestion.worker.max-concurrent-batches=10",
        "documind.ingestion.worker.max-attempts=3",
        "documind.ingestion.worker.initial-backoff=15ms",
        "documind.ingestion.worker.backoff-multiplier=2.0",
        "documind.ingestion.worker.max-backoff=60ms",
        "documind.security.dev-caller-fallback.enabled=true",
        "documind.rate-limit.enabled=false"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AsyncDocumentIngestionIntegrationTest.RecordingEmbeddingTestConfig.class
})
class AsyncDocumentIngestionIntegrationTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DocumentChunkVectorRepository chunkVectorRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ControllableRecordingEmbeddingModel embeddingModel;

    @Autowired
    private DocumentIngestionWorker ingestionWorker;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @BeforeEach
    @AfterEach
    void resetEnvironment() throws InterruptedException {
        awaitActiveWorkersIdle();
        deleteAllBucketObjects();
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
        embeddingModel.reset();
    }

    @Test
    void uploadsTenMixedDocumentsConcurrentlyOnVirtualThreadsAndCompletesAllWithSearchableRbacVectors()
        throws Exception {
        embeddingModel.setSimulatedLatencyMillis(30L);

        List<UploadSpec> specs = List.of(
            new UploadSpec(
                "hr-handbook-2026.pdf", "application/pdf",
                createUnicodePdf("Quy định nghỉ phép năm HR cho nhân viên chính thức: 12 ngày phép."),
                HR_DEPT_ID, AccessLevel.DEPARTMENT
            ),
            new UploadSpec(
                "company-holiday-public.pdf", "application/pdf",
                createUnicodePdf("Lịch nghỉ lễ toàn công ty năm 2026 áp dụng cho mọi phòng ban."),
                HR_DEPT_ID, AccessLevel.PUBLIC
            ),
            new UploadSpec(
                "it-architecture-secret.pdf", "application/pdf",
                createUnicodePdf("Sơ đồ mạng nội bộ bí mật phòng IT chỉ dành cho quản lý."),
                IT_DEPT_ID, AccessLevel.CONFIDENTIAL
            ),
            new UploadSpec(
                "hr-onboarding.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createDocxBytes("Chương 1: Hội nhập", "Điều 1: Quy trình tiếp nhận nhân sự mới HR."),
                HR_DEPT_ID, AccessLevel.DEPARTMENT
            ),
            new UploadSpec(
                "sales-playbook.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createDocxBytes("Chương 1: Bán hàng", "Điều 1: Quy trình báo giá chuẩn phòng Sales."),
                SALES_DEPT_ID, AccessLevel.DEPARTMENT
            ),
            new UploadSpec(
                "it-security-guidelines.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                createDocxBytes("Chương 1: An toàn thông tin", "Điều 1: Quy định mật khẩu toàn công ty."),
                IT_DEPT_ID, AccessLevel.PUBLIC
            ),
            new UploadSpec(
                "rag-overview-public.md", "text/markdown",
                "# Tổng quan DocuMind\n\nHệ thống trợ lý tri thức nội bộ hỗ trợ tìm kiếm ngữ nghĩa.\n".getBytes(StandardCharsets.UTF_8),
                IT_DEPT_ID, AccessLevel.PUBLIC
            ),
            new UploadSpec(
                "hr-salary-matrix.md", "text/markdown",
                "# Bảng lương nội bộ\n\nThông tin thu nhập mật chỉ dành cho Trưởng phòng HR.\n".getBytes(StandardCharsets.UTF_8),
                HR_DEPT_ID, AccessLevel.CONFIDENTIAL
            ),
            new UploadSpec(
                "hr-code-of-conduct.txt", "text/plain",
                "CHƯƠNG I: VĂN HÓA DOANH NGHIỆP\n\nĐiều 1. Quy tắc ứng xử nơi công sở phòng HR.\n".getBytes(StandardCharsets.UTF_8),
                HR_DEPT_ID, AccessLevel.DEPARTMENT
            ),
            new UploadSpec(
                "sales-commission-secret.txt", "text/plain",
                "CHƯƠNG I: HOA HỒNG\n\nĐiều 1. Chính sách thưởng bí mật phòng Sales.\n".getBytes(StandardCharsets.UTF_8),
                SALES_DEPT_ID, AccessLevel.CONFIDENTIAL
            )
        );

        assertThat(specs).hasSize(10);
        long totalCacheVersionBefore = departmentRepository.sumCacheVersions();

        CyclicBarrier barrier = new CyclicBarrier(specs.size());
        List<DocumentResponse> acceptedDocs = new ArrayList<>(specs.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResponseEntity<DocumentResponse>>> futures = new ArrayList<>(specs.size());
            for (UploadSpec spec : specs) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return uploadMultipart(spec);
                }));
            }
            for (Future<ResponseEntity<DocumentResponse>> future : futures) {
                ResponseEntity<DocumentResponse> resp = future.get(30, TimeUnit.SECONDS);
                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                acceptedDocs.add(Objects.requireNonNull(resp.getBody()));
            }
        }

        for (int i = 0; i < acceptedDocs.size(); i++) {
            DocumentResponse uploaded = acceptedDocs.get(i);
            UploadSpec spec = specs.get(i);
            DocumentStatusResponse completed = awaitTerminalStatus(uploaded.id(), spec.departmentId(), Duration.ofSeconds(20));
            assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
            assertThat(completed.chunkCount()).isPositive();
            assertThat(completed.errorMessage()).isNull();
            assertThat(chunkVectorRepository.countByDocumentId(uploaded.id()))
                .isEqualTo(completed.chunkCount());
        }

        // Verify execution ran on Java 21 Virtual Threads with genuine parallel overlap
        List<RecordedEmbedCall> embedCalls = embeddingModel.recordedCalls();
        assertThat(embedCalls).hasSizeGreaterThanOrEqualTo(10);
        assertThat(embedCalls).allSatisfy(call -> {
            assertThat(call.virtualThread()).isTrue();
            assertThat(call.threadName()).startsWith(IngestionAsyncConfig.VIRTUAL_THREAD_PREFIX);
        });
        assertThat(embeddingModel.peakConcurrentEmbedCalls()).isGreaterThanOrEqualTo(2);

        // Upload incremented cache versions + completion incremented cache versions again
        assertThat(departmentRepository.sumCacheVersions()).isGreaterThan(totalCacheVersionBefore + specs.size());

        // Verify RBAC Filter.Expression on real pgvector rows: HR STANDARD_EMPLOYEE sees PUBLIC + HR DEPARTMENT only
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        Filter.Expression hrEmployeeFilter = builder.or(
            builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, "PUBLIC"),
            builder.and(
                builder.eq(ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString()),
                builder.eq(ChunkMetadataKeys.ACCESS_LEVEL, "DEPARTMENT")
            )
        ).build();

        Map<String, String> visibleQueries = Map.of(
            "hr-handbook-2026.pdf", "Quy định nghỉ phép năm HR cho nhân viên chính thức: 12 ngày phép.",
            "company-holiday-public.pdf", "Lịch nghỉ lễ toàn công ty năm 2026 áp dụng cho mọi phòng ban.",
            "hr-onboarding.docx", "Chương 1: Hội nhập\n\nĐiều 1: Quy trình tiếp nhận nhân sự mới HR.",
            "it-security-guidelines.docx", "Chương 1: An toàn thông tin\n\nĐiều 1: Quy định mật khẩu toàn công ty.",
            "rag-overview-public.md", "Tổng quan DocuMind\n\nHệ thống trợ lý tri thức nội bộ hỗ trợ tìm kiếm ngữ nghĩa.",
            "hr-code-of-conduct.txt", "CHƯƠNG I: VĂN HÓA DOANH NGHIỆP\n\nĐiều 1. Quy tắc ứng xử nơi công sở phòng HR."
        );
        for (Map.Entry<String, String> entry : visibleQueries.entrySet()) {
            List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(entry.getValue())
                    .topK(5)
                    .similarityThreshold(0.95d)
                    .filterExpression(hrEmployeeFilter)
                    .build()
            );
            assertThat(hits)
                .as("Expected HR employee RBAC search to find %s", entry.getKey())
                .isNotEmpty();
            assertThat(hits.getFirst().getMetadata().get(ChunkMetadataKeys.FILE_NAME))
                .isEqualTo(entry.getKey());
        }

        List<String> forbiddenExactTexts = List.of(
            "Sơ đồ mạng nội bộ bí mật phòng IT chỉ dành cho quản lý.",
            "Chương 1: Bán hàng\n\nĐiều 1: Quy trình báo giá chuẩn phòng Sales.",
            "Bảng lương nội bộ\n\nThông tin thu nhập mật chỉ dành cho Trưởng phòng HR.",
            "CHƯƠNG I: HOA HỒNG\n\nĐiều 1. Chính sách thưởng bí mật phòng Sales."
        );
        for (String forbiddenQuery : forbiddenExactTexts) {
            List<Document> blockedHits = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(forbiddenQuery)
                    .topK(5)
                    .similarityThreshold(0.95d)
                    .filterExpression(hrEmployeeFilter)
                    .build()
            );
            assertThat(blockedHits)
                .as("Forbidden document must remain invisible to HR STANDARD_EMPLOYEE for query: %s", forbiddenQuery)
                .isEmpty();
        }
    }

    @Test
    void splitsLargeDocumentIntoBatchesOf50AndRecoversFromTransientEmbeddingFailureViaRetry() throws Exception {
        StringBuilder markdown = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            markdown.append("## Mục ").append(i).append(": Quy định vận hành số ").append(i).append("\n\n");
            markdown.append("Nội dung hướng dẫn chi tiết cho quy trình kiểm soát nội bộ phần ").append(i);
            if (i == 70) {
                markdown.append(" RETRY_TRANSIENT_MARKER");
            }
            markdown.append(".\n\n");
        }

        embeddingModel.failOnMarker("RETRY_TRANSIENT_MARKER", 2);

        ResponseEntity<DocumentResponse> uploadResp = uploadMultipart(new UploadSpec(
            "large-policy-120-sections.md",
            "text/markdown",
            markdown.toString().getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID docId = Objects.requireNonNull(uploadResp.getBody()).id();

        DocumentStatusResponse status = awaitTerminalStatus(docId, IT_DEPT_ID, Duration.ofSeconds(20));
        assertThat(status.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(status.chunkCount()).isEqualTo(120);
        assertThat(status.errorMessage()).isNull();

        List<RecordedEmbedCall> calls = embeddingModel.recordedCalls();
        assertThat(calls).hasSize(5);
        assertThat(calls).extracting(RecordedEmbedCall::batchSize)
            .containsExactly(50, 50, 50, 50, 20);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.virtualThread()).isTrue();
            assertThat(call.batchSize()).isLessThanOrEqualTo(50);
        });

        assertThat(chunkVectorRepository.countByDocumentId(docId)).isEqualTo(120L);
        Integer distinctIndices = jdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT chunk_index) FROM document_chunks WHERE document_id = ?",
            Integer.class,
            docId
        );
        assertThat(distinctIndices).isEqualTo(120);
    }

    @Test
    void transitionsEmptyScannedCorruptAndFailedBatchDocumentsToFailedWithClearErrorAndZeroOrphanChunks()
        throws Exception {
        // 1. Whitespace-only .txt -> EMPTY_DOCUMENT
        ResponseEntity<DocumentResponse> emptyTxtResp = uploadMultipart(new UploadSpec(
            "whitespace-empty.txt",
            "text/plain",
            "   \n\t   \r\n   ".getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(emptyTxtResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID emptyTxtId = Objects.requireNonNull(emptyTxtResp.getBody()).id();

        // 2. Scanned / blank 1-page .pdf -> NO_EXTRACTABLE_TEXT
        ResponseEntity<DocumentResponse> scannedPdfResp = uploadMultipart(new UploadSpec(
            "scanned-blank.pdf",
            "application/pdf",
            createBlankSinglePagePdf(),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(scannedPdfResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID scannedPdfId = Objects.requireNonNull(scannedPdfResp.getBody()).id();

        // 3. Corrupt truncated .pdf -> CORRUPT_DOCUMENT
        ResponseEntity<DocumentResponse> corruptPdfResp = uploadMultipart(new UploadSpec(
            "corrupt-truncated.pdf",
            "application/pdf",
            "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\ncorrupt-xref-payload\n%%EOF\n".getBytes(StandardCharsets.US_ASCII),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(corruptPdfResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID corruptPdfId = Objects.requireNonNull(corruptPdfResp.getBody()).id();

        // 4. Encrypted password-protected .pdf -> ENCRYPTED_DOCUMENT
        ResponseEntity<DocumentResponse> encryptedPdfResp = uploadMultipart(new UploadSpec(
            "encrypted-policy.pdf",
            "application/pdf",
            createEncryptedPdf("Tài liệu mật khóa mật khẩu"),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(encryptedPdfResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID encryptedPdfId = Objects.requireNonNull(encryptedPdfResp.getBody()).id();

        // 5. Missing MinIO object (NoSuchKeyException) -> STORAGE_OBJECT_MISSING without retry
        UUID missingS3DocId = UUID.randomUUID();
        String missingStorageKey = "departments/" + IT_DEPT_ID + "/" + missingS3DocId + "/v1/missing-object.md";
        transactionTemplate.executeWithoutResult(tx -> {
            jdbcTemplate.update(
                """
                INSERT INTO documents (
                    id, department_id, original_name, storage_key, file_extension, mime_type,
                    file_size_bytes, file_hash, access_level, status, current_version, chunk_count
                ) VALUES (?, ?, ?, ?, 'md', 'text/markdown', 128, ?, 'DEPARTMENT', 'PENDING', 1, 0)
                """,
                missingS3DocId,
                IT_DEPT_ID,
                "missing-object.md",
                missingStorageKey,
                "f".repeat(64)
            );
            eventPublisher.publishEvent(new com.documind.domain.event.DocumentUploadedEvent(
                missingS3DocId,
                IT_DEPT_ID,
                missingStorageKey,
                "missing-object.md",
                "md",
                AccessLevel.DEPARTMENT,
                1
            ));
        });

        DocumentStatusResponse emptyStatus = awaitTerminalStatus(emptyTxtId, IT_DEPT_ID, Duration.ofSeconds(15));
        assertThat(emptyStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(emptyStatus.chunkCount()).isZero();
        assertThat(emptyStatus.errorMessage())
            .startsWith("[EMPTY_DOCUMENT]")
            .contains("whitespace-empty.txt");
        assertThat(chunkVectorRepository.countByDocumentId(emptyTxtId)).isZero();

        DocumentStatusResponse scannedStatus = awaitTerminalStatus(scannedPdfId, IT_DEPT_ID, Duration.ofSeconds(15));
        assertThat(scannedStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(scannedStatus.chunkCount()).isZero();
        assertThat(scannedStatus.errorMessage())
            .startsWith("[NO_EXTRACTABLE_TEXT]")
            .contains("scanned-blank.pdf");
        assertThat(chunkVectorRepository.countByDocumentId(scannedPdfId)).isZero();

        DocumentStatusResponse corruptStatus = awaitTerminalStatus(corruptPdfId, IT_DEPT_ID, Duration.ofSeconds(15));
        assertThat(corruptStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(corruptStatus.chunkCount()).isZero();
        assertThat(corruptStatus.errorMessage())
            .startsWith("[CORRUPT_DOCUMENT]")
            .contains("corrupt-truncated.pdf");
        assertThat(chunkVectorRepository.countByDocumentId(corruptPdfId)).isZero();

        DocumentStatusResponse encryptedStatus = awaitTerminalStatus(encryptedPdfId, IT_DEPT_ID, Duration.ofSeconds(15));
        assertThat(encryptedStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(encryptedStatus.chunkCount()).isZero();
        assertThat(encryptedStatus.errorMessage())
            .startsWith("[ENCRYPTED_DOCUMENT]")
            .contains("encrypted-policy.pdf");
        assertThat(chunkVectorRepository.countByDocumentId(encryptedPdfId)).isZero();

        DocumentStatusResponse missingS3Status = awaitTerminalStatus(missingS3DocId, IT_DEPT_ID, Duration.ofSeconds(15));
        assertThat(missingS3Status.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(missingS3Status.chunkCount()).isZero();
        assertThat(missingS3Status.errorMessage())
            .isEqualTo("[STORAGE_OBJECT_MISSING] Document file was not found in object storage.");
        assertThat(chunkVectorRepository.countByDocumentId(missingS3DocId)).isZero();

        // 6. Multi-batch .md isolated run: batch 1 (50 chunks) commits, batch 2 fails 3 attempts -> FAILED, 0 chunks, cache_version unchanged
        embeddingModel.reset();
        StringBuilder failingMd = new StringBuilder();
        for (int i = 1; i <= 65; i++) {
            failingMd.append("## Điều khoản ").append(i).append("\n\n");
            failingMd.append("Quy tắc bảo mật dữ liệu nội bộ mục ").append(i);
            if (i == 55) {
                failingMd.append(" FAIL_PERMANENT_BATCH2_MARKER");
            }
            failingMd.append(".\n\n");
        }
        embeddingModel.failOnMarker("FAIL_PERMANENT_BATCH2_MARKER", 10);

        ResponseEntity<DocumentResponse> failedBatchResp = uploadMultipart(new UploadSpec(
            "permanent-batch2-fail.md",
            "text/markdown",
            failingMd.toString().getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(failedBatchResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID failedBatchId = Objects.requireNonNull(failedBatchResp.getBody()).id();
        long itCacheVersionAfterUpload = departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion();

        DocumentStatusResponse batchFailStatus = awaitTerminalStatus(failedBatchId, IT_DEPT_ID, Duration.ofSeconds(15));
        awaitActiveWorkersIdle();
        assertThat(batchFailStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(batchFailStatus.chunkCount()).isZero();
        assertThat(batchFailStatus.errorMessage()).startsWith("[EMBEDDING_ERROR]");
        assertThat(chunkVectorRepository.countByDocumentId(failedBatchId)).isZero();
        assertThat(embeddingModel.recordedCalls())
            .extracting(RecordedEmbedCall::batchSize)
            .containsExactly(50, 15, 15, 15);
        assertThat(departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion())
            .isEqualTo(itCacheVersionAfterUpload);
    }

    @Test
    void recoversStaleProcessingAndPendingDocumentsAndMarksFailedOnJvmError() throws Exception {
        // Part A: JVM Error (StackOverflowError) on batch 2 marks FAILED and purges batch 1 chunks
        StringBuilder errorMd = new StringBuilder();
        for (int i = 1; i <= 62; i++) {
            errorMd.append("## Phần ").append(i).append("\n\n");
            errorMd.append("Nội dung kiểm tra lỗi JVM nghiêm trọng mục ").append(i);
            if (i == 55) {
                errorMd.append(" JVM_STACK_ERROR_MARKER");
            }
            errorMd.append(".\n\n");
        }
        embeddingModel.throwErrorOnMarker("JVM_STACK_ERROR_MARKER", () -> new StackOverflowError("Simulated PDF/JVM Error"));

        ResponseEntity<DocumentResponse> errUploadResp = uploadMultipart(new UploadSpec(
            "jvm-error-batch2.md",
            "text/markdown",
            errorMd.toString().getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.DEPARTMENT
        ));
        assertThat(errUploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID errDocId = Objects.requireNonNull(errUploadResp.getBody()).id();

        DocumentStatusResponse errStatus = awaitTerminalStatus(errDocId, IT_DEPT_ID, Duration.ofSeconds(15));
        awaitActiveWorkersIdle();
        assertThat(errStatus.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(errStatus.chunkCount()).isZero();
        assertThat(errStatus.errorMessage())
            .isEqualTo("[INGESTION_ERROR] Unexpected error while processing document.");
        assertThat(chunkVectorRepository.countByDocumentId(errDocId)).isZero();

        // Part B: Startup recovery marks stale PROCESSING -> FAILED (purging partial chunks) and re-drives stale PENDING -> COMPLETED
        embeddingModel.reset();
        UUID staleProcessingId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO documents (
                id, department_id, original_name, storage_key, file_extension, mime_type,
                file_size_bytes, file_hash, access_level, status, current_version, chunk_count,
                created_at, updated_at
            ) VALUES (
                ?, ?, 'crashed-mid-flight.md', ?, 'md', 'text/markdown',
                256, ?, 'DEPARTMENT', 'PROCESSING', 1, 0,
                CURRENT_TIMESTAMP - INTERVAL '25 minutes',
                CURRENT_TIMESTAMP - INTERVAL '25 minutes'
            )
            """,
            staleProcessingId,
            IT_DEPT_ID,
            "departments/" + IT_DEPT_ID + "/" + staleProcessingId + "/v1/crashed-mid-flight.md",
            "a".repeat(64)
        );
        vectorStore.add(List.of(new Document(
            ChunkIdGenerator.generate(staleProcessingId, 1, 0).toString(),
            "Partial chunk left behind by crashed worker",
            Map.of(
                ChunkMetadataKeys.DOCUMENT_ID, staleProcessingId.toString(),
                ChunkMetadataKeys.DEPARTMENT_ID, IT_DEPT_ID.toString(),
                ChunkMetadataKeys.ACCESS_LEVEL, "DEPARTMENT",
                ChunkMetadataKeys.FILE_NAME, "crashed-mid-flight.md",
                ChunkMetadataKeys.DOC_VERSION, 1,
                ChunkMetadataKeys.CHUNK_INDEX, 0
            )
        )));
        assertThat(chunkVectorRepository.countByDocumentId(staleProcessingId)).isEqualTo(1L);

        UUID stalePendingId = UUID.randomUUID();
        String stalePendingKey = "departments/" + HR_DEPT_ID + "/" + stalePendingId + "/v1/recovered-pending.md";
        byte[] stalePendingBytes = "# Khôi phục sau sự cố\n\nTài liệu PENDING được tự động xử lý lại sau khi khởi động.\n"
            .getBytes(StandardCharsets.UTF_8);
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(storageProperties.bucket())
                .key(stalePendingKey)
                .contentType("text/markdown")
                .build(),
            RequestBody.fromBytes(stalePendingBytes)
        );
        jdbcTemplate.update(
            """
            INSERT INTO documents (
                id, department_id, original_name, storage_key, file_extension, mime_type,
                file_size_bytes, file_hash, access_level, status, current_version, chunk_count,
                created_at, updated_at
            ) VALUES (
                ?, ?, 'recovered-pending.md', ?, 'md', 'text/markdown',
                ?, ?, 'PUBLIC', 'PENDING', 1, 0,
                CURRENT_TIMESTAMP - INTERVAL '25 minutes',
                CURRENT_TIMESTAMP - INTERVAL '25 minutes'
            )
            """,
            stalePendingId,
            HR_DEPT_ID,
            stalePendingKey,
            stalePendingBytes.length,
            "b".repeat(64)
        );

        long itCacheBefore = departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion();
        long hrCacheBefore = departmentRepository.findById(HR_DEPT_ID).orElseThrow().getCacheVersion();
        long salesCacheBefore = departmentRepository.findById(SALES_DEPT_ID).orElseThrow().getCacheVersion();

        ingestionWorker.recoverStaleDocumentsOnStartup();

        DocumentStatusResponse recoveredFailed = awaitTerminalStatus(staleProcessingId, IT_DEPT_ID, Duration.ofSeconds(10));
        assertThat(recoveredFailed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(recoveredFailed.chunkCount()).isZero();
        assertThat(recoveredFailed.errorMessage()).isEqualTo(IngestionFailureFormatter.INTERRUPTED_ERROR_MESSAGE);
        assertThat(chunkVectorRepository.countByDocumentId(staleProcessingId)).isZero();

        DocumentStatusResponse recoveredCompleted = awaitTerminalStatus(stalePendingId, HR_DEPT_ID, Duration.ofSeconds(15));
        awaitActiveWorkersIdle();
        assertThat(recoveredCompleted.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(recoveredCompleted.chunkCount()).isPositive();
        assertThat(chunkVectorRepository.countByDocumentId(stalePendingId)).isEqualTo(recoveredCompleted.chunkCount());

        // PUBLIC document completion increments cache_version across ALL departments deterministically
        assertThat(departmentRepository.findById(IT_DEPT_ID).orElseThrow().getCacheVersion()).isEqualTo(itCacheBefore + 1L);
        assertThat(departmentRepository.findById(HR_DEPT_ID).orElseThrow().getCacheVersion()).isEqualTo(hrCacheBefore + 1L);
        assertThat(departmentRepository.findById(SALES_DEPT_ID).orElseThrow().getCacheVersion()).isEqualTo(salesCacheBefore + 1L);

        // Part C: Duplicate event re-delivery for an already COMPLETED document is idempotently ignored
        int embedCallsBeforeRedelivery = embeddingModel.recordedCalls().size();
        transactionTemplate.executeWithoutResult(tx -> eventPublisher.publishEvent(
            new com.documind.domain.event.DocumentUploadedEvent(
                stalePendingId,
                HR_DEPT_ID,
                stalePendingKey,
                "recovered-pending.md",
                "md",
                AccessLevel.PUBLIC,
                1
            )
        ));
        Thread.sleep(100L);
        awaitActiveWorkersIdle();
        assertThat(embeddingModel.recordedCalls()).hasSize(embedCallsBeforeRedelivery);
        assertThat(departmentRepository.findById(HR_DEPT_ID).orElseThrow().getCacheVersion()).isEqualTo(hrCacheBefore + 1L);
        assertThat(chunkVectorRepository.countByDocumentId(stalePendingId)).isEqualTo(recoveredCompleted.chunkCount());
    }

    @Test
    void deletesDocumentWhileEmbeddingInFlightLeavingZeroOrphanRowsOrChunks() throws Exception {
        StringBuilder markdown = new StringBuilder();
        for (int i = 1; i <= 60; i++) {
            markdown.append("## Khoản ").append(i).append("\n\n");
            markdown.append("Quy định kiểm tra xóa tài liệu khi đang xử lý mục ").append(i).append(".\n\n");
        }

        CountDownLatch embedEntered = new CountDownLatch(1);
        CountDownLatch allowEmbedFinish = new CountDownLatch(1);
        embeddingModel.pauseNextCall(embedEntered, allowEmbedFinish);

        try {
            ResponseEntity<DocumentResponse> uploadResp = uploadMultipart(new UploadSpec(
                "delete-in-flight.md",
                "text/markdown",
                markdown.toString().getBytes(StandardCharsets.UTF_8),
                IT_DEPT_ID,
                AccessLevel.DEPARTMENT
            ));
            assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            UUID docId = Objects.requireNonNull(uploadResp.getBody()).id();

            assertThat(embedEntered.await(10, TimeUnit.SECONDS)).isTrue();

            HttpHeaders deleteHeaders = new HttpHeaders();
            deleteHeaders.set("X-Department-Id", IT_DEPT_ID.toString());
            deleteHeaders.set("X-User-Role", "DEPARTMENT_MANAGER");
            ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/documents/" + docId,
                HttpMethod.DELETE,
                new HttpEntity<>(deleteHeaders),
                Void.class
            );
            assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            allowEmbedFinish.countDown();
            awaitActiveWorkersIdle();

            assertThat(documentRepository.findById(docId)).isEmpty();
            assertThat(chunkVectorRepository.countByDocumentId(docId)).isZero();

            ResponseEntity<String> statusAfterDelete = restTemplate.exchange(
                "/api/v1/documents/" + docId + "/status",
                HttpMethod.GET,
                new HttpEntity<>(deleteHeaders),
                String.class
            );
            assertThat(statusAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            allowEmbedFinish.countDown();
        }
    }

    private ResponseEntity<DocumentResponse> uploadMultipart(UploadSpec spec) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(spec.mimeType()));
        ByteArrayResource resource = new ByteArrayResource(spec.content()) {
            @Override
            public String getFilename() {
                return spec.filename();
            }
        };

        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        body.add("accessLevel", spec.accessLevel().name());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("X-Department-Id", spec.departmentId().toString());
        headers.set("X-User-Role", "DEPARTMENT_MANAGER");

        return restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(body, headers),
            DocumentResponse.class
        );
    }

    private DocumentStatusResponse awaitTerminalStatus(UUID documentId, UUID departmentId, Duration timeout)
        throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Department-Id", departmentId.toString());
        headers.set("X-User-Role", "DEPARTMENT_MANAGER");

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        DocumentStatusResponse lastResponse = null;
        while (System.nanoTime() < deadlineNanos) {
            ResponseEntity<DocumentStatusResponse> resp = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DocumentStatusResponse.class
            );
            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                lastResponse = resp.getBody();
                if (lastResponse.status() == DocumentStatus.COMPLETED || lastResponse.status() == DocumentStatus.FAILED) {
                    return lastResponse;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(
            "Timed out waiting for terminal ingestion status of " + documentId + ", last=" + lastResponse
        );
    }

    private void awaitActiveWorkersIdle() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        Integer activeDbCount = null;
        while (System.nanoTime() < deadlineNanos) {
            activeDbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE status IN ('PENDING', 'PROCESSING')",
                Integer.class
            );
            if ((activeDbCount == null || activeDbCount == 0)
                && ingestionWorker.activeWorkerCount() == 0
                && embeddingModel.activeEmbedCalls() == 0) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(
            "Timed out waiting for ingestion workers to become idle (activeDb=" + activeDbCount
                + ", activeWorkers=" + ingestionWorker.activeWorkerCount()
                + ", activeEmbedCalls=" + embeddingModel.activeEmbedCalls() + ")"
        );
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

    private static byte[] createBlankSinglePagePdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createUnicodePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font font;
            try (InputStream fontStream = Objects.requireNonNull(
                PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            )) {
                font = PDType0Font.load(doc, fontStream);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createEncryptedPdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType0Font font;
            try (InputStream fontStream = Objects.requireNonNull(
                PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            )) {
                font = PDType0Font.load(doc, fontStream);
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.protect(new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission()));
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static byte[] createDocxBytes(String heading, String body) throws IOException {
        String contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
              <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>
            """;
        String rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
            """;
        String docRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>
            """;
        String stylesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/></w:style>
            </w:styles>
            """;
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading1"/></w:pPr>
                  <w:r><w:t>%s</w:t></w:r>
                </w:p>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
              </w:body>
            </w:document>
            """.formatted(heading, body);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "[Content_Types].xml", contentTypes);
            writeZipEntry(zos, "_rels/.rels", rootRels);
            writeZipEntry(zos, "word/_rels/document.xml.rels", docRels);
            writeZipEntry(zos, "word/styles.xml", stylesXml);
            writeZipEntry(zos, "word/document.xml", documentXml);
        }
        return baos.toByteArray();
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String xml) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(xml.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private record UploadSpec(
        String filename,
        String mimeType,
        byte[] content,
        UUID departmentId,
        AccessLevel accessLevel
    ) {
    }

    private record RecordedEmbedCall(
        boolean virtualThread,
        String threadName,
        int batchSize
    ) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingEmbeddingTestConfig {

        @Bean(name = "deterministicTestEmbeddingModel")
        @Primary
        ControllableRecordingEmbeddingModel deterministicTestEmbeddingModel() {
            return new ControllableRecordingEmbeddingModel();
        }
    }

    static final class ControllableRecordingEmbeddingModel implements EmbeddingModel {

        private final List<RecordedEmbedCall> calls = new CopyOnWriteArrayList<>();
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger peakConcurrentCalls = new AtomicInteger();
        private final Map<String, AtomicInteger> failureCounters = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, java.util.function.Supplier<Error>> errorTriggers = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile long simulatedLatencyMillis = 0L;
        private volatile CountDownLatch pauseEnteredLatch;
        private volatile CountDownLatch pauseReleaseLatch;

        void reset() {
            CountDownLatch release = pauseReleaseLatch;
            if (release != null) {
                release.countDown();
            }
            calls.clear();
            activeCalls.set(0);
            peakConcurrentCalls.set(0);
            failureCounters.clear();
            errorTriggers.clear();
            simulatedLatencyMillis = 0L;
            pauseEnteredLatch = null;
            pauseReleaseLatch = null;
        }

        void pauseNextCall(CountDownLatch enteredLatch, CountDownLatch releaseLatch) {
            this.pauseEnteredLatch = enteredLatch;
            this.pauseReleaseLatch = releaseLatch;
        }

        void setSimulatedLatencyMillis(long simulatedLatencyMillis) {
            this.simulatedLatencyMillis = simulatedLatencyMillis;
        }

        void failOnMarker(String marker, int failures) {
            failureCounters.put(marker, new AtomicInteger(failures));
        }

        void throwErrorOnMarker(String marker, java.util.function.Supplier<Error> errorSupplier) {
            errorTriggers.put(marker, errorSupplier);
        }

        List<RecordedEmbedCall> recordedCalls() {
            return List.copyOf(calls);
        }

        int activeEmbedCalls() {
            return activeCalls.get();
        }

        int peakConcurrentEmbedCalls() {
            return peakConcurrentCalls.get();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            Thread current = Thread.currentThread();
            calls.add(new RecordedEmbedCall(current.isVirtual(), current.getName(), instructions.size()));

            int active = activeCalls.incrementAndGet();
            peakConcurrentCalls.accumulateAndGet(active, Math::max);
            try {
                CountDownLatch entered = pauseEnteredLatch;
                CountDownLatch release = pauseReleaseLatch;
                if (entered != null && release != null) {
                    pauseEnteredLatch = null;
                    pauseReleaseLatch = null;
                    entered.countDown();
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting on pauseReleaseLatch");
                    }
                }
                if (simulatedLatencyMillis > 0L) {
                    Thread.sleep(simulatedLatencyMillis);
                }
                for (Map.Entry<String, java.util.function.Supplier<Error>> entry : errorTriggers.entrySet()) {
                    String marker = entry.getKey();
                    if (instructions.stream().anyMatch(text -> text.contains(marker))) {
                        throw entry.getValue().get();
                    }
                }
                for (Map.Entry<String, AtomicInteger> entry : failureCounters.entrySet()) {
                    String marker = entry.getKey();
                    boolean matches = instructions.stream().anyMatch(text -> text.contains(marker));
                    if (matches && entry.getValue().getAndDecrement() > 0) {
                        throw new TransientAiException("Simulated Ollama failure for marker: " + marker);
                    }
                }
                List<Embedding> embeddings = new ArrayList<>(instructions.size());
                for (int i = 0; i < instructions.size(); i++) {
                    embeddings.add(new Embedding(deterministicVector(instructions.get(i)), i));
                }
                return new EmbeddingResponse(embeddings);
            } catch (InterruptedException ex) {
                current.interrupt();
                throw new IllegalStateException("Interrupted during simulated embedding", ex);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        @Override
        public float[] embed(Document document) {
            String text = (document != null && document.getText() != null) ? document.getText() : "";
            return deterministicVector(text);
        }

        @Override
        public int dimensions() {
            return PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS;
        }

        private static float[] deterministicVector(String text) {
            String safeText = text == null ? "" : text;
            byte[] seed = sha256(safeText.getBytes(StandardCharsets.UTF_8));
            long state = ByteBuffer.wrap(seed, 0, Long.BYTES).getLong();
            if (state == 0L) {
                state = 0x9E3779B97F4A7C15L;
            }

            float[] vector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
            double sumSquares = 0.0;
            for (int i = 0; i < PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS; i++) {
                state += 0x9E3779B97F4A7C15L;
                long z = state;
                z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                z = z ^ (z >>> 31);
                float component = ((z >>> 8) / (float) (1L << 56)) * 2.0f - 1.0f;
                vector[i] = component;
                sumSquares += (double) component * component;
            }

            double norm = Math.sqrt(sumSquares);
            if (norm == 0.0) {
                vector[0] = 1.0f;
                return vector;
            }
            for (int i = 0; i < PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
            return vector;
        }

        private static byte[] sha256(byte[] input) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(input);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 algorithm unavailable", ex);
            }
        }
    }
}
