package com.documind.integration;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.enums.Role;
import com.documind.service.document.CallerContext;
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.rag.RagConstants;
import com.documind.service.rag.VectorRetrievalService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.documind.support.SampleEnterpriseCorpusFactory.EvalQueryCase;
import com.documind.support.SampleEnterpriseCorpusFactory.SampleDocumentSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Week 1 Milestone 1 end-to-end integration test suite backed by real Testcontainers
 * ({@code pgvector:pg16}, {@code redis:7-alpine}, {@code minio}).
 */
class Week1FullLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LettuceConnectionFactory lettuceConnectionFactory;

    @Autowired
    private VectorRetrievalService vectorRetrievalService;

    @Test
    void infrastructureHealthAndRedisMinioPgVectorConnectivityAreUp() throws Exception {
        assertThat(lettuceConnectionFactory.getPort()).isEqualTo(RedisTestcontainersConfig.sharedMappedPort());

        String redisKey = "documind:da11:health-probe";
        String redisValue = "Kết nối Redis Testcontainer thành công";
        stringRedisTemplate.opsForValue().set(redisKey, redisValue, Duration.ofMinutes(1));
        assertThat(stringRedisTemplate.opsForValue().get(redisKey)).isEqualTo(redisValue);

        ResponseEntity<String> healthResponse = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(healthResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(healthResponse.getBody());
        assertThat(root.path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("db").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("redis").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("minio").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("minio").path("details").path("bucket").asText())
            .isEqualTo(storageProperties.bucket());
    }

    @Test
    void sharedCleanupHandlesForeignKeysFlushesRedisAndRestoresSeedUsers() throws Exception {
        UUID sessionId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO chat_sessions (id, user_id, title) VALUES (?, ?, ?)",
            sessionId,
            HR_MANAGER_USER_ID,
            "DA-11 FK cleanup probe"
        );
        jdbcTemplate.update(
            "INSERT INTO chat_messages (session_id, role, content) VALUES (?, 'USER', 'Probe message')",
            sessionId
        );
        jdbcTemplate.update(
            "INSERT INTO refresh_tokens (user_id, token_hash, expires_at) VALUES (?, ?, NOW() + INTERVAL '1 hour')",
            HR_MANAGER_USER_ID,
            "probe-token-hash-" + UUID.randomUUID()
        );
        stringRedisTemplate.opsForValue().set("documind:da11:stale-l1-cache", "cached-payload", Duration.ofMinutes(5));
        assertThat(stringRedisTemplate.hasKey("documind:da11:stale-l1-cache")).isTrue();

        cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            recordingChatModel,
            Duration.ofSeconds(10)
        );

        assertThat(stringRedisTemplate.hasKey("documind:da11:stale-l1-cache")).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_messages", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chat_sessions", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(3);

        seedSupplementalTestUsers(jdbcTemplate);
    }

    @Test
    void fullLifecycleAcrossFormatsUploadProcessVectorAskAndDeleteCascadesCleanly() throws Exception {
        byte[] pdfBytes = SampleEnterpriseCorpusFactory.createMultiPagePdf(List.of(
            List.of(
                "Trang 1: Tổng quan văn hóa doanh nghiệp và giá trị cốt lõi.",
                "Mọi nhân sự cần tuân thủ nguyên tắc minh bạch và trách nhiệm."
            ),
            List.of(
                "Trang 2: Quy định nghỉ phép năm và thử việc.",
                "Thời gian thử việc tối đa cho kỹ sư là 60 ngày và nhân viên chính thức hưởng 14 ngày phép."
            )
        ));
        byte[] docxBytes = SampleEnterpriseCorpusFactory.createStructuredDocx(List.of(
            new SampleEnterpriseCorpusFactory.DocxBlock("Heading1", "Chương 1: Định mức công tác phí"),
            new SampleEnterpriseCorpusFactory.DocxBlock(
                null,
                "Hạn mức khách sạn tối đa mỗi đêm là 1.500.000 VNĐ và hoàn ứng trong 7 ngày làm việc."
            )
        ));

        verifySingleDocumentFullLifecycle(
            "lifecycle-hr.pdf",
            "application/pdf",
            pdfBytes,
            HR_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "Thời gian thử việc tối đa cho kỹ sư là bao nhiêu ngày và hưởng bao nhiêu ngày phép năm?",
            true,
            2,
            "%PDF-"
        );

        verifySingleDocumentFullLifecycle(
            "lifecycle-sales.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            docxBytes,
            SALES_DEPT_ID,
            AccessLevel.DEPARTMENT,
            "Hạn mức khách sạn tối đa mỗi đêm khi đi công tác là bao nhiêu VNĐ?",
            false,
            null,
            "PK"
        );
    }

    @Test
    void fiveBilingualEnterpriseDocumentsPassMilestone1HitRateAndZeroHallucinationGates() throws Exception {
        List<SampleDocumentSpec> corpus = SampleEnterpriseCorpusFactory.buildWeek1EnterpriseCorpus();
        assertThat(corpus).hasSize(5);

        List<DocumentResponse> uploadedDocs = new ArrayList<>(corpus.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<DocumentResponse>> uploadTasks = corpus.stream()
                .map(spec -> (Callable<DocumentResponse>) () -> uploadAndAwaitCompleted(
                    spec.filename(),
                    spec.mimeType(),
                    spec.content(),
                    spec.departmentId(),
                    spec.accessLevel(),
                    "DEPARTMENT_MANAGER",
                    resolveTestUserId("DEPARTMENT_MANAGER", spec.departmentId()),
                    Duration.ofSeconds(30)
                ))
                .toList();
            List<Future<DocumentResponse>> futures = executor.invokeAll(uploadTasks, 45, TimeUnit.SECONDS);
            for (Future<DocumentResponse> future : futures) {
                assertThat(future.isCancelled())
                    .as("Parallel 5-document upload batch must finish within 45s aggregate timeout")
                    .isFalse();
                uploadedDocs.add(future.get());
            }
        }

        for (DocumentResponse uploaded : uploadedDocs) {
            assertThat(chunkVectorRepository.countByDocumentId(uploaded.id())).isPositive();
        }

        List<EvalQueryCase> evalCases = SampleEnterpriseCorpusFactory.loadWeek1MilestoneQueries(objectMapper);
        List<EvalQueryCase> inScopeCases = evalCases.stream()
            .filter(c -> "IN_SCOPE".equals(c.category()))
            .toList();
        List<EvalQueryCase> oosCases = evalCases.stream()
            .filter(c -> !"IN_SCOPE".equals(c.category()))
            .toList();

        assertThat(inScopeCases).hasSize(14);
        assertThat(oosCases).hasSize(6);

        int inScopeHits = 0;
        for (EvalQueryCase evalCase : inScopeCases) {
            UUID deptId = SampleEnterpriseCorpusFactory.departmentIdForCode(evalCase.dept());
            UUID userId = resolveTestUserId(evalCase.role(), deptId);
            recordingChatModel.reset();

            ResponseEntity<ChatAskResponse> resp = askQuestion(evalCase.query(), deptId, evalCase.role(), userId);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            ChatAskResponse body = Objects.requireNonNull(resp.getBody());

            assertThat(body.abstained())
                .as("Expected grounded answer for %s (%s)", evalCase.id(), evalCase.query())
                .isFalse();
            assertThat(recordingChatModel.callCount()).isEqualTo(1);
            assertThat(body.citations()).isNotEmpty();
            assertThat(body.citations()).allSatisfy(citation -> {
                assertThat(citation.similarityScore()).isBetween(0.58d, 1.0d);
                assertThat(citation.excerpt()).isNotBlank();
            });

            String systemPrompt = normalizeNfcLower(recordingChatModel.lastSystemPrompt());
            String normalizedPhrase = normalizeNfcLower(evalCase.expectedKeyPhrase());
            boolean hit = body.citations().stream().anyMatch(citation ->
                Objects.equals(evalCase.expectedFile(), citation.fileName())
                    && (evalCase.expectedPage() == null || Objects.equals(evalCase.expectedPage(), citation.pageNumber()))
            ) && systemPrompt.contains(normalizedPhrase);

            if (hit) {
                inScopeHits++;
            }
        }

        double hitRateAt5 = (double) inScopeHits / inScopeCases.size();
        assertThat(hitRateAt5)
            .as("Week 1 Milestone 1 binary question-level Hit@5 across 5 enterprise docs")
            .isGreaterThanOrEqualTo(0.85d);

        int oosViolations = 0;
        for (EvalQueryCase oosCase : oosCases) {
            UUID deptId = SampleEnterpriseCorpusFactory.departmentIdForCode(oosCase.dept());
            UUID userId = resolveTestUserId(oosCase.role(), deptId);
            recordingChatModel.reset();

            ResponseEntity<ChatAskResponse> resp = askQuestion(oosCase.query(), deptId, oosCase.role(), userId);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            ChatAskResponse body = Objects.requireNonNull(resp.getBody());

            if (!isSafeOosRefusalResponse(oosCase, body, recordingChatModel)) {
                oosViolations++;
            }
        }

        double hallucinationRate = (double) oosViolations / oosCases.size();
        assertThat(hallucinationRate)
            .as("Week 1 Milestone 1 OOS_EXTERNAL + OOS_RBAC hallucination/leakage rate")
            .isZero();

        Integer chatAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY'",
            Integer.class
        );
        assertThat(chatAuditCount).isEqualTo(inScopeCases.size() + oosCases.size());
        Integer unattributedAuditCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_logs WHERE action_type = 'CHAT_QUERY' AND (user_id IS NULL OR department_id IS NULL)",
            Integer.class
        );
        assertThat(unattributedAuditCount).isZero();

        // Threshold-independent (similarityThreshold = 0.0) pure Vector RBAC isolation checks (F-13)
        assertVectorFileHiddenFromCaller(
            "Which HTTP 5xx error rate and p95 latency thresholds trigger an automated production rollback?",
            HR_EMPLOYEE_USER_ID,
            HR_DEPT_ID,
            Role.STANDARD_EMPLOYEE,
            "en-cloud-release-runbook.pdf"
        );
        assertVectorFileHiddenFromCaller(
            "Which HTTP 5xx error rate and p95 latency thresholds trigger an automated production rollback?",
            IT_EMPLOYEE_USER_ID,
            IT_DEPT_ID,
            Role.STANDARD_EMPLOYEE,
            "en-cloud-release-runbook.pdf"
        );
        assertVectorFileVisibleToCaller(
            "Which HTTP 5xx error rate and p95 latency thresholds trigger an automated production rollback?",
            IT_MANAGER_USER_ID,
            IT_DEPT_ID,
            Role.DEPARTMENT_MANAGER,
            "en-cloud-release-runbook.pdf"
        );
        assertVectorFileHiddenFromCaller(
            "Gói bảo hiểm sức khỏe PVI Care có hạn mức nội trú bao nhiêu tiền?",
            SALES_EMPLOYEE_USER_ID,
            SALES_DEPT_ID,
            Role.STANDARD_EMPLOYEE,
            "vn-hr-handbook-2026.pdf"
        );
        assertVectorFileHiddenFromCaller(
            "Định mức khách sạn tối đa mỗi đêm khi công tác nội địa là bao nhiêu VNĐ?",
            HR_EMPLOYEE_USER_ID,
            HR_DEPT_ID,
            Role.STANDARD_EMPLOYEE,
            "vn-sales-expense-policy.docx"
        );
    }

    private void assertVectorFileHiddenFromCaller(
        String query,
        UUID userId,
        UUID deptId,
        Role role,
        String forbiddenFileName
    ) {
        List<Document> rawHits = vectorStore.similaritySearch(SearchRequest.builder()
            .query(query)
            .topK(10)
            .similarityThreshold(0.0d)
            .filterExpression(vectorRetrievalService.buildRbacFilter(new CallerContext(userId, deptId, role, "127.0.0.1")))
            .build());
        assertThat(rawHits).noneMatch(doc ->
            Objects.equals(forbiddenFileName, doc.getMetadata().get(ChunkMetadataKeys.FILE_NAME))
        );
    }

    private void assertVectorFileVisibleToCaller(
        String query,
        UUID userId,
        UUID deptId,
        Role role,
        String expectedFileName
    ) {
        List<Document> rawHits = vectorStore.similaritySearch(SearchRequest.builder()
            .query(query)
            .topK(10)
            .similarityThreshold(0.0d)
            .filterExpression(vectorRetrievalService.buildRbacFilter(new CallerContext(userId, deptId, role, "127.0.0.1")))
            .build());
        assertThat(rawHits).anyMatch(doc ->
            Objects.equals(expectedFileName, doc.getMetadata().get(ChunkMetadataKeys.FILE_NAME))
        );
    }

    private void verifySingleDocumentFullLifecycle(
        String filename,
        String contentType,
        byte[] content,
        UUID departmentId,
        AccessLevel accessLevel,
        String question,
        boolean expectPdfPage,
        Integer expectedPageNumber,
        String expectedDownloadPrefix
    ) throws Exception {
        long cacheVersionStart = departmentRepository.findById(departmentId).orElseThrow().getCacheVersion();
        UUID managerUserId = resolveTestUserId("DEPARTMENT_MANAGER", departmentId);
        UUID employeeUserId = resolveTestUserId("STANDARD_EMPLOYEE", departmentId);

        // 1. Upload -> 202 Accepted
        ResponseEntity<DocumentResponse> uploadResp = uploadDocument(
            filename,
            contentType,
            content,
            departmentId,
            accessLevel,
            "DEPARTMENT_MANAGER",
            managerUserId
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse uploaded = Objects.requireNonNull(uploadResp.getBody());
        assertThat(uploaded.status()).isEqualTo(DocumentStatus.PENDING);

        String storageKey = documentRepository.findById(uploaded.id()).orElseThrow().getStorageKey();
        HeadObjectResponse s3Head = s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(storageKey)
            .build());
        assertThat(s3Head.contentLength()).isEqualTo(content.length);

        // 2. Async Virtual Thread Worker -> COMPLETED
        DocumentStatusResponse completed = awaitTerminalStatus(
            uploaded.id(),
            departmentId,
            "DEPARTMENT_MANAGER",
            managerUserId,
            Duration.ofSeconds(25)
        );
        assertThat(completed.status()).isEqualTo(DocumentStatus.COMPLETED);
        assertThat(completed.chunkCount()).isPositive();
        assertThat(completed.errorMessage()).isNull();

        // 3. Vector Stored in pgvector & V4..V7 metadata compliance (no JSON null page_number on DOCX)
        long vectorCount = chunkVectorRepository.countByDocumentId(uploaded.id());
        assertThat(vectorCount).isEqualTo(completed.chunkCount());

        List<Map<String, Object>> chunkRows = jdbcTemplate.queryForList(
            """
            SELECT doc_version, chunk_index, page_number,
                   jsonb_exists(metadata, 'page_number') AS has_page_key,
                   metadata->>'document_id' AS meta_doc_id,
                   metadata->>'department_id' AS meta_dept_id,
                   metadata->>'access_level' AS meta_access_level,
                   metadata->>'file_name' AS meta_file_name
            FROM document_chunks
            WHERE document_id = ?
            ORDER BY chunk_index
            """,
            uploaded.id()
        );
        assertThat(chunkRows).hasSize(completed.chunkCount());
        for (Map<String, Object> row : chunkRows) {
            assertThat(row.get("meta_doc_id")).isEqualTo(uploaded.id().toString());
            assertThat(row.get("meta_dept_id")).isEqualTo(departmentId.toString());
            assertThat(row.get("meta_access_level")).isEqualTo(accessLevel.name());
            assertThat(row.get("meta_file_name")).isEqualTo(filename);
            assertThat(row.get("doc_version")).isEqualTo(1);
            if (expectPdfPage) {
                assertThat(row.get("has_page_key")).isEqualTo(Boolean.TRUE);
                assertThat((Integer) row.get("page_number")).isGreaterThanOrEqualTo(1);
            } else {
                assertThat(row.get("has_page_key")).isEqualTo(Boolean.FALSE);
                assertThat(row.get("page_number")).isNull();
            }
        }

        // 4. Ask Question -> Grounded Answer + Citations + Presigned MinIO Download + Audit Log
        recordingChatModel.reset();
        ResponseEntity<ChatAskResponse> askResp = askQuestion(question, departmentId, "STANDARD_EMPLOYEE", employeeUserId);
        assertThat(askResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse askBody = Objects.requireNonNull(askResp.getBody());
        assertThat(askBody.abstained()).isFalse();
        assertThat(recordingChatModel.callCount()).isEqualTo(1);
        assertThat(recordingChatModel.wasTransactionActiveOnLastCall()).isFalse();
        assertThat(askBody.citations()).isNotEmpty();

        CitationDto citation = askBody.citations().getFirst();
        assertThat(citation.documentId()).isEqualTo(uploaded.id());
        assertThat(citation.fileName()).isEqualTo(filename);
        assertThat(citation.similarityScore()).isBetween(0.58d, 1.0d);
        assertThat(citation.excerpt()).isNotBlank();
        assertThat(citation.presignedUrl()).isNotBlank();
        if (expectPdfPage) {
            assertThat(citation.pageNumber()).isEqualTo(expectedPageNumber);
            assertThat(citation.presignedUrl()).endsWith("#page=" + expectedPageNumber);
        } else {
            assertThat(citation.pageNumber()).isNull();
            assertThat(citation.sectionHeading()).isNotBlank();
            assertThat(citation.presignedUrl()).doesNotContain("#page=");
        }

        String downloadUrl = citation.presignedUrl().contains("#")
            ? citation.presignedUrl().substring(0, citation.presignedUrl().indexOf('#'))
            : citation.presignedUrl();
        try (HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()) {
            HttpResponse<byte[]> downloaded = httpClient.send(
                HttpRequest.newBuilder(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).isEqualTo(content);
            if (expectedDownloadPrefix != null) {
                assertThat(new String(downloaded.body(), 0, expectedDownloadPrefix.length(), StandardCharsets.US_ASCII))
                    .isEqualTo(expectedDownloadPrefix);
            }
        }

        Map<String, Object> groundedAudit = jdbcTemplate.queryForMap(
            """
            SELECT user_id, department_id, abstained, cache_hit, cache_tier,
                   prompt_tokens, completion_tokens, status_code
            FROM audit_logs
            WHERE action_type = 'CHAT_QUERY'
            ORDER BY created_at DESC
            LIMIT 1
            """
        );
        assertThat(groundedAudit.get("user_id")).isEqualTo(employeeUserId);
        assertThat(groundedAudit.get("department_id")).isEqualTo(departmentId);
        assertThat(groundedAudit.get("abstained")).isEqualTo(Boolean.FALSE);
        assertThat(groundedAudit.get("cache_hit")).isEqualTo(Boolean.FALSE);
        assertThat(groundedAudit.get("cache_tier")).isEqualTo("NONE");
        assertThat(groundedAudit.get("prompt_tokens")).isEqualTo(125);
        assertThat(groundedAudit.get("completion_tokens")).isEqualTo(48);
        assertThat(groundedAudit.get("status_code")).isEqualTo(200);

        // 5. Delete Document -> 204 No Content + Cascade Cleanup (DB + pgvector + MinIO + Audit + Cache Version)
        long cacheVersionBeforeDelete = departmentRepository.findById(departmentId).orElseThrow().getCacheVersion();
        assertThat(cacheVersionBeforeDelete).isGreaterThan(cacheVersionStart);

        ResponseEntity<Void> deleteResp = deleteDocument(uploaded.id(), departmentId, "DEPARTMENT_MANAGER", managerUserId);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(documentRepository.findById(uploaded.id())).isEmpty();
        assertThat(chunkVectorRepository.countByDocumentId(uploaded.id())).isZero();
        assertThat(departmentRepository.findById(departmentId).orElseThrow().getCacheVersion())
            .isEqualTo(cacheVersionBeforeDelete + 1L);

        assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
            .bucket(storageProperties.bucket())
            .key(storageKey)
            .build()))
            .isInstanceOf(NoSuchKeyException.class);

        ResponseEntity<String> statusAfterDelete = getDocumentStatusRaw(
            uploaded.id(),
            departmentId,
            "DEPARTMENT_MANAGER",
            managerUserId
        );
        assertThat(statusAfterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> deleteAudit = jdbcTemplate.queryForMap(
            """
            SELECT user_id, department_id, status_code, query_text
            FROM audit_logs
            WHERE action_type = 'DOC_DELETE'
            ORDER BY created_at DESC
            LIMIT 1
            """
        );
        assertThat(deleteAudit.get("user_id")).isEqualTo(managerUserId);
        assertThat(deleteAudit.get("department_id")).isEqualTo(departmentId);
        assertThat(deleteAudit.get("status_code")).isEqualTo(204);
        assertThat(deleteAudit.get("query_text")).isEqualTo(filename);

        // 6. Ask Exact Same Question Post-Delete -> Deterministic Safe Refusal with 0 LLM Calls
        recordingChatModel.reset();
        ResponseEntity<ChatAskResponse> postDeleteAsk = askQuestion(
            question,
            departmentId,
            "STANDARD_EMPLOYEE",
            employeeUserId
        );
        assertThat(postDeleteAsk.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChatAskResponse refusalBody = Objects.requireNonNull(postDeleteAsk.getBody());
        assertThat(refusalBody.abstained()).isTrue();
        assertThat(refusalBody.answer()).isEqualTo(RagConstants.SAFE_REFUSAL_MESSAGE);
        assertThat(refusalBody.citations()).isEmpty();
        assertThat(refusalBody.modelProvider()).isNull();
        assertThat(refusalBody.promptTokens()).isZero();
        assertThat(refusalBody.completionTokens()).isZero();
        assertThat(recordingChatModel.callCount()).isZero();

        Map<String, Object> refusalAudit = jdbcTemplate.queryForMap(
            """
            SELECT user_id, department_id, abstained, cache_hit, cache_tier,
                   prompt_tokens, completion_tokens, status_code
            FROM audit_logs
            WHERE action_type = 'CHAT_QUERY'
            ORDER BY created_at DESC
            LIMIT 1
            """
        );
        assertThat(refusalAudit.get("user_id")).isEqualTo(employeeUserId);
        assertThat(refusalAudit.get("department_id")).isEqualTo(departmentId);
        assertThat(refusalAudit.get("abstained")).isEqualTo(Boolean.TRUE);
        assertThat(refusalAudit.get("cache_hit")).isEqualTo(Boolean.FALSE);
        assertThat(refusalAudit.get("cache_tier")).isEqualTo("NONE");
        assertThat(refusalAudit.get("prompt_tokens")).isEqualTo(0);
        assertThat(refusalAudit.get("completion_tokens")).isEqualTo(0);
        assertThat(refusalAudit.get("status_code")).isEqualTo(200);
    }
}
