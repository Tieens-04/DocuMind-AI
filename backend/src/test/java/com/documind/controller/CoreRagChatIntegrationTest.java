package com.documind.controller;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagConstants;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
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
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    MinioTestcontainersConfig.class,
    CoreRagChatIntegrationTest.TestAiConfig.class
})
class CoreRagChatIntegrationTest {

    private static final UUID IT_DEPT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID HR_DEPT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SALES_DEPT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID HR_MANAGER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SALES_EMPLOYEE_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RecordingTestChatModel recordingChatModel;

    @Autowired
    private EmbeddingModel embeddingModel;

    @BeforeEach
    @AfterEach
    void resetState() throws InterruptedException {
        awaitWorkersIdle();
        deleteAllBucketObjects();
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
        recordingChatModel.reset();
    }

    @Test
    void groundedAskFlowReturnsCitedPdfPageAndMarkdownWithMinioPresignedLinksAndAuditLog() throws Exception {
        byte[] twoPagePdf = createMultiPageUnicodePdf(List.of(
            "Chương 1: Giới thiệu lịch sử hình thành và giá trị cốt lõi của công ty.",
            "Chương 2: Quy định nghỉ phép năm - Nhân viên chính thức được hưởng 12 ngày phép nguyên lương."
        ));
        DocumentResponse uploadedPdf = uploadAndAwaitCompleted(
            "hr-handbook-2026.pdf",
            "application/pdf",
            twoPagePdf,
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );

        byte[] publicMarkdown = (
            "# Hybrid Work Policy\n\n"
                + "Full-time employees may work remote work up to 2 days per week with manager approval.\n"
        ).getBytes(StandardCharsets.UTF_8);
        DocumentResponse uploadedMd = uploadAndAwaitCompleted(
            "remote-work-policy.md",
            "text/markdown",
            publicMarkdown,
            IT_DEPT_ID,
            AccessLevel.PUBLIC
        );

        recordingChatModel.reset();
        ResponseEntity<ChatAskResponse> viResponseEntity = askAs(
            "Nhân viên chính thức được hưởng bao nhiêu ngày nghỉ phép năm?",
            HR_DEPT_ID,
            "STANDARD_EMPLOYEE",
            null
        );

        assertThat(viResponseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse viResponse = Objects.requireNonNull(viResponseEntity.getBody());
        assertThat(embeddingModel).isInstanceOf(TopicClusterTestEmbeddingModel.class);
        assertThat(viResponse.abstained()).isFalse();
        assertThat(viResponse.answer()).contains("[Nguồn: hr-handbook-2026.pdf - Trang 2]");
        assertThat(viResponse.modelProvider()).isEqualTo(RagConstants.PROVIDER_GROQ);
        assertThat(viResponse.promptTokens()).isEqualTo(120);
        assertThat(viResponse.completionTokens()).isEqualTo(45);
        assertThat(recordingChatModel.callCount()).isEqualTo(1);
        assertThat(recordingChatModel.wasTransactionActiveOnLastCall()).isFalse();

        String capturedSystemPrompt = recordingChatModel.lastSystemPrompt();
        assertThat(capturedSystemPrompt)
            .contains("<context>")
            .contains("[Nguồn: hr-handbook-2026.pdf - Trang 2]")
            .contains("12 ngày phép nguyên lương");

        assertThat(viResponse.citations()).isNotEmpty();
        CitationDto pdfCitation = viResponse.citations().getFirst();
        assertThat(pdfCitation.documentId()).isEqualTo(uploadedPdf.id());
        assertThat(pdfCitation.fileName()).isEqualTo("hr-handbook-2026.pdf");
        assertThat(pdfCitation.pageNumber()).isEqualTo(2);
        assertThat(pdfCitation.similarityScore()).isGreaterThanOrEqualTo(0.58d);
        assertThat(pdfCitation.presignedUrl()).isNotBlank().endsWith("#page=2");

        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            String rawSignedUrl = pdfCitation.presignedUrl().substring(0, pdfCitation.presignedUrl().indexOf('#'));
            HttpResponse<byte[]> minioPdfResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(rawSignedUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(minioPdfResponse.statusCode()).isEqualTo(200);
            assertThat(new String(minioPdfResponse.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }

        Map<String, Object> auditRow = jdbcTemplate.queryForMap("""
                SELECT action_type, abstained, cache_hit, cache_tier, prompt_tokens, completion_tokens, provider, status_code
                FROM audit_logs
                WHERE action_type = 'CHAT_QUERY'
                ORDER BY created_at DESC
                LIMIT 1
                """);
        assertThat(auditRow.get("abstained")).isEqualTo(Boolean.FALSE);
        assertThat(auditRow.get("cache_hit")).isEqualTo(Boolean.FALSE);
        assertThat(auditRow.get("cache_tier")).isEqualTo("NONE");
        assertThat(auditRow.get("prompt_tokens")).isEqualTo(120);
        assertThat(auditRow.get("completion_tokens")).isEqualTo(45);
        assertThat(auditRow.get("provider")).isEqualTo("groq");
        assertThat(auditRow.get("status_code")).isEqualTo(200);

        // English PUBLIC markdown query visible across departments with null pageNumber and real MinIO presigned download
        recordingChatModel.reset();
        ResponseEntity<ChatAskResponse> enResponseEntity = askAs(
            "How many remote work days are allowed per week?",
            SALES_DEPT_ID,
            "STANDARD_EMPLOYEE",
            SALES_EMPLOYEE_USER_ID
        );

        assertThat(enResponseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse enResponse = Objects.requireNonNull(enResponseEntity.getBody());
        assertThat(enResponse.abstained()).isFalse();
        assertThat(recordingChatModel.callCount()).isEqualTo(1);
        assertThat(enResponse.citations()).isNotEmpty();
        CitationDto mdCitation = enResponse.citations().getFirst();
        assertThat(mdCitation.documentId()).isEqualTo(uploadedMd.id());
        assertThat(mdCitation.fileName()).isEqualTo("remote-work-policy.md");
        assertThat(mdCitation.pageNumber()).isNull();
        assertThat(mdCitation.sectionHeading()).isEqualTo("Hybrid Work Policy");
        assertThat(mdCitation.presignedUrl()).isNotBlank().doesNotContain("#page=");

        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            HttpResponse<String> minioMdResponse = httpClient.send(
                HttpRequest.newBuilder(URI.create(mdCitation.presignedUrl())).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertThat(minioMdResponse.statusCode()).isEqualTo(200);
            assertThat(minioMdResponse.body()).contains("Full-time employees may work remote work");
        }
    }

    @Test
    void outOfScopeQuestionReturnsSafeRefusalWithZeroLlmCalls() throws Exception {
        uploadAndAwaitCompleted(
            "hr-handbook.pdf",
            "application/pdf",
            createMultiPageUnicodePdf(List.of("Quy định nghỉ phép năm dành cho nhân viên chính thức.")),
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        recordingChatModel.reset();

        ResponseEntity<ChatAskResponse> responseEntity = askAs(
            "Giá cổ phiếu Bitcoin hôm nay là bao nhiêu?",
            HR_DEPT_ID,
            "STANDARD_EMPLOYEE",
            null
        );

        assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse body = Objects.requireNonNull(responseEntity.getBody());
        assertThat(body.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(body.abstained()).isTrue();
        assertThat(body.citations()).isEmpty();
        assertThat(body.modelProvider()).isNull();
        assertThat(body.promptTokens()).isZero();
        assertThat(body.completionTokens()).isZero();
        assertThat(recordingChatModel.callCount()).isZero();

        Map<String, Object> auditRow = jdbcTemplate.queryForMap("""
                SELECT abstained, cache_hit, cache_tier, prompt_tokens, completion_tokens, status_code
                FROM audit_logs
                WHERE action_type = 'CHAT_QUERY'
                ORDER BY created_at DESC
                LIMIT 1
                """);
        assertThat(auditRow.get("abstained")).isEqualTo(Boolean.TRUE);
        assertThat(auditRow.get("cache_hit")).isEqualTo(Boolean.FALSE);
        assertThat(auditRow.get("cache_tier")).isEqualTo("NONE");
        assertThat(auditRow.get("prompt_tokens")).isEqualTo(0);
        assertThat(auditRow.get("completion_tokens")).isEqualTo(0);
        assertThat(auditRow.get("status_code")).isEqualTo(200);
    }

    @Test
    void rbacTruthMatrixIsolatesConfidentialAndCrossDepartmentChunksWithZeroLlmLeakage() throws Exception {
        uploadAndAwaitCompleted(
            "hr-salary-secret.pdf",
            "application/pdf",
            createMultiPageUnicodePdf(List.of("Bảng lương mật ban điều hành và thưởng nội bộ phòng HR.")),
            HR_DEPT_ID,
            AccessLevel.CONFIDENTIAL
        );
        uploadAndAwaitCompleted(
            "hr-leave-dept.pdf",
            "application/pdf",
            createMultiPageUnicodePdf(List.of("Quy trình xin nghỉ phép năm nội bộ phòng HR.")),
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT
        );
        uploadAndAwaitCompleted(
            "company-remote-public.md",
            "text/markdown",
            "# Remote Policy\n\nAll employees follow the remote work hybrid policy.\n".getBytes(StandardCharsets.UTF_8),
            IT_DEPT_ID,
            AccessLevel.PUBLIC
        );
        uploadAndAwaitCompleted(
            "sales-commission.pdf",
            "application/pdf",
            createMultiPageUnicodePdf(List.of("Chính sách hoa hồng bán hàng theo doanh số quý cho phòng Sales.")),
            SALES_DEPT_ID,
            AccessLevel.DEPARTMENT
        );

        // 1. HR Manager sees HR CONFIDENTIAL
        recordingChatModel.reset();
        ChatAskResponse hrManagerSalary = Objects.requireNonNull(askAs(
            "Chi tiết bảng lương mật của phòng HR?",
            HR_DEPT_ID, "DEPARTMENT_MANAGER", HR_MANAGER_USER_ID
        ).getBody());
        assertThat(hrManagerSalary.abstained()).isFalse();
        assertThat(hrManagerSalary.citations().getFirst().fileName()).isEqualTo("hr-salary-secret.pdf");
        assertThat(recordingChatModel.callCount()).isEqualTo(1);

        // 2. HR Standard Employee MUST NOT see HR CONFIDENTIAL -> 0 LLM calls
        recordingChatModel.reset();
        ChatAskResponse hrEmployeeSalary = Objects.requireNonNull(askAs(
            "Chi tiết bảng lương mật của phòng HR?",
            HR_DEPT_ID, "STANDARD_EMPLOYEE", null
        ).getBody());
        assertThat(hrEmployeeSalary.abstained()).isTrue();
        assertThat(hrEmployeeSalary.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(hrEmployeeSalary.citations()).isEmpty();
        assertThat(recordingChatModel.callCount()).isZero();

        // 3. SALES Standard Employee MUST NOT see HR DEPARTMENT or HR CONFIDENTIAL -> 0 LLM calls
        recordingChatModel.reset();
        ChatAskResponse salesOnHrLeave = Objects.requireNonNull(askAs(
            "Quy trình xin nghỉ phép năm của phòng HR?",
            SALES_DEPT_ID, "STANDARD_EMPLOYEE", SALES_EMPLOYEE_USER_ID
        ).getBody());
        assertThat(salesOnHrLeave.abstained()).isTrue();
        assertThat(recordingChatModel.callCount()).isZero();

        // 4. SALES Standard Employee DOES see SALES DEPARTMENT and PUBLIC
        recordingChatModel.reset();
        ChatAskResponse salesOwnDept = Objects.requireNonNull(askAs(
            "Chính sách hoa hồng bán hàng?",
            SALES_DEPT_ID, "STANDARD_EMPLOYEE", SALES_EMPLOYEE_USER_ID
        ).getBody());
        assertThat(salesOwnDept.abstained()).isFalse();
        assertThat(salesOwnDept.citations().getFirst().fileName()).isEqualTo("sales-commission.pdf");

        ChatAskResponse salesPublic = Objects.requireNonNull(askAs(
            "Quy định remote work toàn công ty?",
            SALES_DEPT_ID, "STANDARD_EMPLOYEE", SALES_EMPLOYEE_USER_ID
        ).getBody());
        assertThat(salesPublic.abstained()).isFalse();
        assertThat(salesPublic.citations().getFirst().fileName()).isEqualTo("company-remote-public.md");

        // 5. SYSTEM_ADMIN sees HR CONFIDENTIAL
        recordingChatModel.reset();
        ChatAskResponse adminSalary = Objects.requireNonNull(askAs(
            "Chi tiết bảng lương mật?",
            IT_DEPT_ID, "SYSTEM_ADMIN", ADMIN_USER_ID
        ).getBody());
        assertThat(adminSalary.abstained()).isFalse();
        assertThat(adminSalary.citations().getFirst().fileName()).isEqualTo("hr-salary-secret.pdf");
    }

    @Test
    void inFlightProcessingAndStaleVersionChunksAreExcludedFromRetrieval() {
        DepartmentEntity hrDept = departmentRepository.findById(HR_DEPT_ID).orElseThrow();

        DocumentEntity processingDoc = new DocumentEntity();
        processingDoc.setDepartment(hrDept);
        processingDoc.setOriginalName("in-flight-draft.pdf");
        processingDoc.setStorageKey("departments/" + HR_DEPT_ID + "/draft/v1/in-flight-draft.pdf");
        processingDoc.setFileExtension("pdf");
        processingDoc.setMimeType("application/pdf");
        processingDoc.setFileSizeBytes(1024L);
        processingDoc.setFileHash("a".repeat(64));
        processingDoc.setAccessLevel(AccessLevel.PUBLIC);
        processingDoc.setStatus(DocumentStatus.PROCESSING);
        processingDoc.setCurrentVersion(1);
        processingDoc = documentRepository.saveAndFlush(processingDoc);

        DocumentEntity reindexedDoc = new DocumentEntity();
        reindexedDoc.setDepartment(hrDept);
        reindexedDoc.setOriginalName("reindexed-policy.pdf");
        reindexedDoc.setStorageKey("departments/" + HR_DEPT_ID + "/reindexed/v2/reindexed-policy.pdf");
        reindexedDoc.setFileExtension("pdf");
        reindexedDoc.setMimeType("application/pdf");
        reindexedDoc.setFileSizeBytes(2048L);
        reindexedDoc.setFileHash("b".repeat(64));
        reindexedDoc.setAccessLevel(AccessLevel.PUBLIC);
        reindexedDoc.setStatus(DocumentStatus.COMPLETED);
        reindexedDoc.setCurrentVersion(2);
        reindexedDoc = documentRepository.saveAndFlush(reindexedDoc);

        DocumentEntity failedDoc = new DocumentEntity();
        failedDoc.setDepartment(hrDept);
        failedDoc.setOriginalName("corrupted-policy.pdf");
        failedDoc.setStorageKey("departments/" + HR_DEPT_ID + "/failed/v1/corrupted-policy.pdf");
        failedDoc.setFileExtension("pdf");
        failedDoc.setMimeType("application/pdf");
        failedDoc.setFileSizeBytes(512L);
        failedDoc.setFileHash("c".repeat(64));
        failedDoc.setAccessLevel(AccessLevel.PUBLIC);
        failedDoc.setStatus(DocumentStatus.FAILED);
        failedDoc.setErrorMessage("Simulated parse failure");
        failedDoc.setCurrentVersion(1);
        failedDoc = documentRepository.saveAndFlush(failedDoc);

        vectorStore.add(List.of(
            new Document(
                UUID.randomUUID().toString(),
                "Dữ liệu tạm thời đang xử lý chưa hoàn tất.",
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, processingDoc.getId().toString(),
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, "PUBLIC",
                    ChunkMetadataKeys.FILE_NAME, "in-flight-draft.pdf",
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 0,
                    ChunkMetadataKeys.PAGE_NUMBER, 1
                )
            ),
            new Document(
                UUID.randomUUID().toString(),
                "Bảng lương mật phiên bản cũ v1 đã lỗi thời.",
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, reindexedDoc.getId().toString(),
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, "PUBLIC",
                    ChunkMetadataKeys.FILE_NAME, "reindexed-policy.pdf",
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 0,
                    ChunkMetadataKeys.PAGE_NUMBER, 1
                )
            ),
            new Document(
                UUID.randomUUID().toString(),
                "Nội dung tài liệu lỗi hỏng không được phép truy xuất.",
                Map.of(
                    ChunkMetadataKeys.DOCUMENT_ID, failedDoc.getId().toString(),
                    ChunkMetadataKeys.DEPARTMENT_ID, HR_DEPT_ID.toString(),
                    ChunkMetadataKeys.ACCESS_LEVEL, "PUBLIC",
                    ChunkMetadataKeys.FILE_NAME, "corrupted-policy.pdf",
                    ChunkMetadataKeys.DOC_VERSION, 1,
                    ChunkMetadataKeys.CHUNK_INDEX, 0,
                    ChunkMetadataKeys.PAGE_NUMBER, 1
                )
            )
        ));

        try {
            recordingChatModel.reset();
            ChatAskResponse processingAsk = Objects.requireNonNull(askAs(
                "Thông tin tạm thời đang xử lý?",
                HR_DEPT_ID, "DEPARTMENT_MANAGER", HR_MANAGER_USER_ID
            ).getBody());
            assertThat(processingAsk.abstained()).isTrue();
            assertThat(processingAsk.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(recordingChatModel.callCount()).isZero();

            ChatAskResponse staleAsk = Objects.requireNonNull(askAs(
                "Thông tin bảng lương mật?",
                HR_DEPT_ID, "DEPARTMENT_MANAGER", HR_MANAGER_USER_ID
            ).getBody());
            assertThat(staleAsk.abstained()).isTrue();
            assertThat(staleAsk.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(recordingChatModel.callCount()).isZero();

            ChatAskResponse failedAsk = Objects.requireNonNull(askAs(
                "Thông tin tài liệu lỗi hỏng?",
                HR_DEPT_ID, "DEPARTMENT_MANAGER", HR_MANAGER_USER_ID
            ).getBody());
            assertThat(failedAsk.abstained()).isTrue();
            assertThat(failedAsk.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
            assertThat(recordingChatModel.callCount()).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM documents WHERE id IN (?, ?)", processingDoc.getId(), failedDoc.getId());
        }
    }

    @Test
    void deactivatedUserReceives403ForbiddenWithZeroLlmCalls() {
        UUID inactiveUserId = UUID.randomUUID();
        jdbcTemplate.update(
            """
            INSERT INTO users (id, department_id, email, password_hash, full_name, role, is_active)
            VALUES (?, ?, ?, 'hash', 'Inactive HR User', 'STANDARD_EMPLOYEE', false)
            """,
            inactiveUserId,
            HR_DEPT_ID,
            "inactive." + inactiveUserId + "@documind.ai"
        );
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Department-Id", HR_DEPT_ID.toString());
            headers.set("X-User-Role", "STANDARD_EMPLOYEE");
            headers.set("X-User-Id", inactiveUserId.toString());

            ResponseEntity<ProblemDetail> response = restTemplate.postForEntity(
                "/api/v1/chat/ask",
                new HttpEntity<>(Map.of("question", "Chính sách nghỉ phép năm?"), headers),
                ProblemDetail.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            ProblemDetail problem = Objects.requireNonNull(response.getBody());
            assertThat(problem.getProperties()).containsEntry("errorCode", "DOCUMENT_ACCESS_DENIED");
            assertThat(recordingChatModel.callCount()).isZero();
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", inactiveUserId);
        }
    }

    private DocumentResponse uploadAndAwaitCompleted(
        String filename,
        String contentType,
        byte[] bytes,
        UUID departmentId,
        AccessLevel accessLevel
    ) throws InterruptedException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        body.add("accessLevel", accessLevel.name());
        body.add("departmentId", departmentId.toString());

        HttpHeaders uploadHeaders = new HttpHeaders();
        uploadHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        uploadHeaders.set("X-Department-Id", departmentId.toString());
        uploadHeaders.set("X-User-Role", "DEPARTMENT_MANAGER");

        ResponseEntity<DocumentResponse> uploadResp = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(body, uploadHeaders),
            DocumentResponse.class
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse doc = Objects.requireNonNull(uploadResp.getBody());

        HttpHeaders statusHeaders = new HttpHeaders();
        statusHeaders.set("X-Department-Id", departmentId.toString());
        statusHeaders.set("X-User-Role", "DEPARTMENT_MANAGER");

        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            ResponseEntity<DocumentStatusResponse> statusResp = restTemplate.exchange(
                "/api/v1/documents/" + doc.id() + "/status",
                HttpMethod.GET,
                new HttpEntity<>(statusHeaders),
                DocumentStatusResponse.class
            );
            if (statusResp.getStatusCode() == HttpStatus.OK && statusResp.getBody() != null) {
                DocumentStatus status = statusResp.getBody().status();
                if (status == DocumentStatus.COMPLETED) {
                    return doc;
                }
                if (status == DocumentStatus.FAILED) {
                    throw new AssertionError("Ingestion failed for " + filename + ": " + statusResp.getBody().errorMessage());
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for ingestion completion of " + filename);
    }

    private ResponseEntity<ChatAskResponse> askAs(
        String question,
        UUID departmentId,
        String role,
        UUID userId
    ) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Department-Id", departmentId.toString());
        headers.set("X-User-Role", role);
        if (userId != null) {
            headers.set("X-User-Id", userId.toString());
        }
        return restTemplate.postForEntity(
            "/api/v1/chat/ask",
            new HttpEntity<>(Map.of("question", question), headers),
            ChatAskResponse.class
        );
    }

    private void awaitWorkersIdle() throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadlineNanos) {
            Integer activeDbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM documents WHERE status IN ('PENDING', 'PROCESSING')",
                Integer.class
            );
            if (activeDbCount == null || activeDbCount == 0) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for ingestion workers to settle");
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

    private static byte[] createMultiPageUnicodePdf(List<String> pageTexts) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDType0Font font;
            try (InputStream fontStream = Objects.requireNonNull(
                PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            )) {
                font = PDType0Font.load(doc, fontStream);
            }
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(pageText);
                    cs.endText();
                }
            }
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAiConfig {

        @Bean(name = "deterministicTestEmbeddingModel")
        @Primary
        EmbeddingModel topicClusterTestEmbeddingModel() {
            return new TopicClusterTestEmbeddingModel();
        }

        @Bean
        RecordingTestChatModel recordingTestChatModel() {
            return new RecordingTestChatModel();
        }

        @Bean(name = "groqChatClient")
        @Primary
        ChatClient groqChatClient(RecordingTestChatModel recordingTestChatModel) {
            return ChatClient.create(recordingTestChatModel);
        }
    }

    static final class RecordingTestChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
        private final AtomicBoolean txActiveOnLastCall = new AtomicBoolean(false);

        void reset() {
            callCount.set(0);
            lastPrompt.set(null);
            txActiveOnLastCall.set(false);
        }

        int callCount() {
            return callCount.get();
        }

        boolean wasTransactionActiveOnLastCall() {
            return txActiveOnLastCall.get();
        }

        String lastSystemPrompt() {
            Prompt prompt = lastPrompt.get();
            if (prompt == null) {
                return "";
            }
            return prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElse("");
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount.incrementAndGet();
            lastPrompt.set(prompt);
            txActiveOnLastCall.set(TransactionSynchronizationManager.isActualTransactionActive());

            String systemText = prompt.getInstructions().stream()
                .filter(msg -> msg.getMessageType() == MessageType.SYSTEM)
                .map(Message::getText)
                .findFirst()
                .orElse("");
            Matcher matcher = CITATION_ATTR_PATTERN.matcher(systemText);
            String firstCitation = matcher.find() ? matcher.group(1) : "[Nguồn: document]";
            String answer = "Theo tài liệu nội bộ đã trích xuất " + firstCitation + ".";

            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 45, 165))
                .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
        }
    }

    private static final class TopicClusterTestEmbeddingModel implements EmbeddingModel {

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<String> instructions = request.getInstructions();
            List<Embedding> embeddings = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(embedText(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embedText(document != null ? document.getText() : "");
        }

        @Override
        public int dimensions() {
            return PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS;
        }

        private static float[] embedText(String rawText) {
            String safeText = rawText == null ? "" : rawText;
            float[] vector = deterministicNoiseVector(safeText);
            String lower = safeText.toLowerCase(Locale.ROOT);

            if (lower.contains("nghỉ phép") || lower.contains("phép năm") || lower.contains("annual leave")) {
                vector[0] += 12.0f;
            }
            if (lower.contains("làm việc từ xa") || lower.contains("remote work") || lower.contains("hybrid")) {
                vector[1] += 12.0f;
            }
            if (lower.contains("bảng lương mật") || lower.contains("thu nhập mật") || lower.contains("executive salary")) {
                vector[2] += 12.0f;
            }
            if (lower.contains("hoa hồng") || lower.contains("sales commission")) {
                vector[3] += 12.0f;
            }
            if (lower.contains("tạm thời đang xử lý") || lower.contains("in-flight draft")) {
                vector[4] += 12.0f;
            }
            if (lower.contains("lịch sử hình thành")) {
                vector[5] += 12.0f;
            }
            if (lower.contains("tài liệu lỗi hỏng")) {
                vector[6] += 12.0f;
            }
            return normalizeL2(vector);
        }

        private static float[] deterministicNoiseVector(String safeText) {
            byte[] seed = sha256(safeText.getBytes(StandardCharsets.UTF_8));
            long state = ByteBuffer.wrap(seed, 0, Long.BYTES).getLong();
            if (state == 0L) {
                state = 0x9E3779B97F4A7C15L;
            }
            float[] vector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
            for (int i = 0; i < PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS; i++) {
                state += 0x9E3779B97F4A7C15L;
                long z = state;
                z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
                z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
                z = z ^ (z >>> 31);
                vector[i] = ((z >>> 8) / (float) (1L << 56)) * 2.0f - 1.0f;
            }
            return normalizeL2(vector);
        }

        private static float[] normalizeL2(float[] vector) {
            double sumSquares = 0.0d;
            for (float v : vector) {
                sumSquares += (double) v * v;
            }
            double norm = Math.sqrt(sumSquares);
            if (norm == 0.0d) {
                vector[0] = 1.0f;
                return vector;
            }
            for (int i = 0; i < vector.length; i++) {
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
