package com.documind.infrastructure.ai;

import com.documind.controller.dto.DocumentResponse;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.support.StreamingMultipartTestSupport;
import com.documind.support.StreamingMultipartTestSupport.RepeatingPdfInputStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "smoke", matches = "true")
class Day1InfrastructureAndAiSmokeTest {

    private static final String VIETNAMESE_EMBED_SAMPLE =
        "Quy trình nghỉ phép năm của nhân viên chính thức tại doanh nghiệp";
    private static final String VIETNAMESE_CHAT_PROMPT =
        "Trả lời đúng 1 câu tiếng Việt ngắn gọn: 2 + 2 bằng mấy?";
    private static final UUID SMOKE_IT_DEPARTMENT_ID =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String EXPECTED_MINIO_BUCKET = "documind-documents";
    private static final long FORTY_MIB_BYTES = 40L * 1024L * 1024L;
    private static final long MAX_STANDARD_UPLOAD_MS = 300L;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private S3Client s3Client;

    @Autowired
    private StorageProperties storageProperties;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ChatClient groqChatClient;

    @Autowired
    @Qualifier("geminiChatClient")
    private ChatClient geminiChatClient;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private Environment environment;

    @Autowired
    private DocumentChunkVectorRepository chunkVectorRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void actuatorHealthReportsUpForPostgresRedisAndMinio() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(response.getBody());
        assertThat(root.path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("db").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("redis").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("minio").path("status").asText()).isEqualTo("UP");
        assertThat(root.path("components").path("minio").path("details").path("bucket").asText())
            .isEqualTo("documind-documents");
    }

    @Test
    void swaggerUiHtmlIsAccessible() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui.html", String.class);
        if (response.getStatusCode().is3xxRedirection()) {
            URI location = response.getHeaders().getLocation();
            assertThat(location).isNotNull();
            String redirectTarget = location.isAbsolute() ? location.getRawPath() : location.toString();
            response = restTemplate.getForEntity(redirectTarget, String.class);
        }

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Swagger UI");
    }

    @Test
    void pgVectorSchemaInitializationRemainsDisabledForDa6Flyway() {
        String vectorStoreTable = jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.vector_store')",
            String.class
        );
        assertThat(vectorStoreTable).isNull();
    }

    @Test
    void ollamaBgeM3EmbedsVietnameseSentenceInto1024Dimensions() {
        float[] vector = embeddingModel.embed(VIETNAMESE_EMBED_SAMPLE);

        assertThat(vector).hasSize(1024);
        double sumSquares = 0.0;
        for (float v : vector) {
            assertThat(Float.isFinite(v)).isTrue();
            sumSquares += (double) v * v;
        }
        double l2Norm = Math.sqrt(sumSquares);
        assertThat(l2Norm).isGreaterThan(0.1);
    }

    @Test
    void primaryGroqAndSecondaryGeminiChatClientsAnswerInVietnamese() {
        String groqKey = environment.getProperty("GROQ_API_KEY", "");
        assertThat(groqKey)
            .as("GROQ_API_KEY in .env must be populated before running live LLM smoke test")
            .isNotBlank()
            .isNotEqualTo(AiModelConfig.UNCONFIGURED_API_KEY);

        String groqAnswer = groqChatClient.prompt()
            .user(VIETNAMESE_CHAT_PROMPT)
            .call()
            .content();

        assertThat(groqAnswer).isNotBlank();
        assertThat(groqAnswer.toLowerCase(Locale.ROOT)).containsAnyOf("4", "bốn");
        System.out.println("[SMOKE] Primary Groq ChatClient Vietnamese response verified (" + groqAnswer.length() + " chars)");

        String geminiKey = aiProperties.gemini() != null ? aiProperties.gemini().apiKey() : "";
        if (geminiKey != null && !geminiKey.isBlank() && !AiModelConfig.UNCONFIGURED_API_KEY.equals(geminiKey)) {
            String geminiAnswer = geminiChatClient.prompt()
                .user(VIETNAMESE_CHAT_PROMPT)
                .call()
                .content();

            assertThat(geminiAnswer).isNotBlank();
            assertThat(geminiAnswer.toLowerCase(Locale.ROOT)).containsAnyOf("4", "bốn");
            System.out.println("[SMOKE] Secondary Gemini ChatClient Vietnamese response verified (" + geminiAnswer.length() + " chars)");
        }
    }

    @Test
    void livePostgresAppliesFlywayV1ToV3AndCreatesAllNineTables() {
        Integer failedMigrations = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false",
            Integer.class
        );
        assertThat(failedMigrations).isZero();

        List<String> versions = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL AND version <> '0' ORDER BY installed_rank",
            String.class
        );
        assertThat(versions).containsSubsequence("1", "2", "3");

        List<String> tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class
        );
        assertThat(tables).contains(
            "departments", "users", "refresh_tokens", "documents",
            "chat_sessions", "chat_messages", "audit_logs",
            "document_chunks", "semantic_cache"
        );
        System.out.println("[SMOKE] Live Flyway versions verified: " + versions);
    }

    @Test
    void livePostgresContainsSeededDepartmentsAndUsers() {
        List<String> departmentCodes = jdbcTemplate.queryForList(
            "SELECT code FROM departments ORDER BY code",
            String.class
        );
        assertThat(departmentCodes).containsExactlyInAnyOrder("IT", "HR", "SALES");

        List<String> userEmails = jdbcTemplate.queryForList(
            "SELECT email FROM users ORDER BY email",
            String.class
        );
        assertThat(userEmails).containsExactlyInAnyOrder(
            "admin@documind.ai",
            "manager.hr@documind.ai",
            "employee.sales@documind.ai"
        );
        System.out.println("[SMOKE] Live seeded departments=" + departmentCodes + ", users=" + userEmails);
    }

    @Test
    void liveExplainCosineSearchWithBgeM3VectorUsesHnswIndex() {
        float[] queryVector = embeddingModel.embed(VIETNAMESE_EMBED_SAMPLE);
        assertThat(queryVector).hasSize(1024);

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

            List<String> planLines = chunkVectorRepository.explainCosineSearch(queryVector, 5);
            assertThat(planLines).isNotEmpty();
            planLines.forEach(line -> System.out.println("[SMOKE][EXPLAIN] " + line));

            String fullPlan = String.join("\n", planLines);
            assertThat(fullPlan).contains("Index Scan using idx_document_chunks_embedding_hnsw");
        });
    }

    @Test
    void liveMinioStreams40MbPdfRejectsDuplicateAndCleansUp() throws Exception {
        assertThat(storageProperties.bucket()).isEqualTo(EXPECTED_MINIO_BUCKET);

        String runMarker = UUID.randomUUID().toString();
        List<UUID> createdDocumentIds = new ArrayList<>();
        Throwable primaryFailure = null;

        try (HttpClient httpClient = StreamingMultipartTestSupport.newHttp11Client()) {
            try {
                // Stream 40 MiB PDF first to validate zero-Heap streaming and warm Tomcat/Multipart/Tika/Hikari/S3 pools
                HttpResponse<String> pdfResponse = uploadMultipartStream(
                    httpClient,
                    "smoke-40mib-" + runMarker + ".pdf",
                    "application/pdf",
                    SMOKE_IT_DEPARTMENT_ID,
                    () -> new RepeatingPdfInputStream(FORTY_MIB_BYTES, runMarker)
                );
                assertThat(pdfResponse.statusCode()).isEqualTo(HttpStatus.ACCEPTED.value());

                DocumentResponse createdPdf = objectMapper.readValue(pdfResponse.body(), DocumentResponse.class);
                assertThat(createdPdf.id()).isNotNull();
                createdDocumentIds.add(createdPdf.id());
                assertThat(createdPdf.departmentId()).isEqualTo(SMOKE_IT_DEPARTMENT_ID);
                assertThat(createdPdf.fileSizeBytes()).isEqualTo(FORTY_MIB_BYTES);
                assertThat(createdPdf.mimeType()).isEqualTo("application/pdf");

                String pdfStorageKey = jdbcTemplate.queryForObject(
                    "SELECT storage_key FROM documents WHERE id = ?",
                    String.class,
                    createdPdf.id()
                );
                assertThat(pdfStorageKey).isNotBlank();

                HeadObjectResponse headObject = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(EXPECTED_MINIO_BUCKET)
                    .key(pdfStorageKey)
                    .build());
                assertThat(headObject.contentLength()).isEqualTo(FORTY_MIB_BYTES);
                System.out.println(
                    "[SMOKE] Live 40 MiB PDF streamed to MinIO bucket=" + EXPECTED_MINIO_BUCKET
                        + " docId=" + createdPdf.id()
                        + " size=" + headObject.contentLength()
                        + " storageKey=" + pdfStorageKey
                );

                // Stream small Vietnamese Markdown document twice: warm <300ms 202 Accepted, then 409 Conflict
                byte[] markdownBytes = (
                    "# Quy chế nghỉ phép năm 2026 (" + runMarker + ")\n"
                        + "- Nhân viên chính thức được hưởng 12 ngày nghỉ phép nguyên lương.\n"
                        + "- Tài liệu kiểm thử MinIO smoke test tiếng Việt.\n"
                ).getBytes(StandardCharsets.UTF_8);

                long mdStartedNanos = System.nanoTime();
                HttpResponse<String> mdFirstResponse = uploadMultipartStream(
                    httpClient,
                    "quy-che-nghi-phep-smoke-" + runMarker + ".md",
                    "text/markdown",
                    SMOKE_IT_DEPARTMENT_ID,
                    () -> new ByteArrayInputStream(markdownBytes)
                );
                long mdElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mdStartedNanos);

                assertThat(mdFirstResponse.statusCode()).isEqualTo(HttpStatus.ACCEPTED.value());
                DocumentResponse createdMd = objectMapper.readValue(mdFirstResponse.body(), DocumentResponse.class);
                assertThat(createdMd.id()).isNotNull();
                createdDocumentIds.add(createdMd.id());
                assertThat(mdElapsedMs)
                    .as("Warm Vietnamese .md upload latency (ms)")
                    .isLessThan(MAX_STANDARD_UPLOAD_MS);
                assertThat(createdMd.fileExtension()).isEqualTo("md");
                assertThat(createdMd.mimeType()).isEqualTo("text/markdown");
                assertThat(createdMd.fileSizeBytes()).isEqualTo(markdownBytes.length);

                HttpResponse<String> mdDuplicateResponse = uploadMultipartStream(
                    httpClient,
                    "quy-che-nghi-phep-smoke-" + runMarker + "-duplicate.md",
                    "text/markdown",
                    SMOKE_IT_DEPARTMENT_ID,
                    () -> new ByteArrayInputStream(markdownBytes)
                );
                if (mdDuplicateResponse.statusCode() == HttpStatus.ACCEPTED.value()) {
                    DocumentResponse unexpectedDuplicate = objectMapper.readValue(
                        mdDuplicateResponse.body(),
                        DocumentResponse.class
                    );
                    if (unexpectedDuplicate.id() != null) {
                        createdDocumentIds.add(unexpectedDuplicate.id());
                    }
                }
                assertThat(mdDuplicateResponse.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
                JsonNode duplicateProblem = objectMapper.readTree(mdDuplicateResponse.body());
                assertThat(duplicateProblem.path("status").asInt()).isEqualTo(HttpStatus.CONFLICT.value());
                assertThat(duplicateProblem.path("errorCode").asText()).isEqualTo("DUPLICATE_DOCUMENT");
                assertThat(duplicateProblem.path("existingDocumentId").asText()).isEqualTo(createdMd.id().toString());
                System.out.println(
                    "[SMOKE] Live Vietnamese .md upload verified: docId=" + createdMd.id()
                        + " latencyMs=" + mdElapsedMs
                        + " duplicateStatus=" + mdDuplicateResponse.statusCode()
                );
            } catch (Throwable failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                deleteAndVerifyCleanup(httpClient, createdDocumentIds, primaryFailure);
            }
        }
    }

    private HttpResponse<String> uploadMultipartStream(
        HttpClient httpClient,
        String filename,
        String contentType,
        UUID departmentId,
        Supplier<InputStream> contentStreamSupplier
    ) throws Exception {
        HttpRequest request = StreamingMultipartTestSupport.buildMultipartUploadRequest(
            URI.create("http://localhost:" + port + "/api/v1/documents/upload?departmentId="
                + departmentId + "&accessLevel=DEPARTMENT"),
            "----DocuMindSmoke",
            filename,
            contentType,
            contentStreamSupplier,
            Duration.ofSeconds(120)
        );
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void deleteAndVerifyCleanup(
        HttpClient httpClient,
        List<UUID> createdDocumentIds,
        Throwable primaryFailure
    ) throws Exception {
        List<Throwable> cleanupFailures = new ArrayList<>();
        for (UUID docId : createdDocumentIds) {
            try {
                List<String> storageKeys = jdbcTemplate.queryForList(
                    "SELECT storage_key FROM documents WHERE id = ?",
                    String.class,
                    docId
                );

                HttpRequest deleteRequest = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/documents/" + docId
                        + "?departmentId=" + SMOKE_IT_DEPARTMENT_ID))
                    .timeout(Duration.ofSeconds(30))
                    .DELETE()
                    .build();
                HttpResponse<Void> deleteResponse = httpClient.send(deleteRequest, HttpResponse.BodyHandlers.discarding());
                assertThat(deleteResponse.statusCode()).isEqualTo(HttpStatus.NO_CONTENT.value());

                Integer remainingRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM documents WHERE id = ?",
                    Integer.class,
                    docId
                );
                assertThat(remainingRows).isZero();

                for (String storageKey : storageKeys) {
                    assertThatThrownBy(() -> s3Client.headObject(HeadObjectRequest.builder()
                        .bucket(EXPECTED_MINIO_BUCKET)
                        .key(storageKey)
                        .build()))
                        .isInstanceOf(NoSuchKeyException.class);
                }
            } catch (Throwable failure) {
                cleanupFailures.add(failure);
            }
        }

        if (!cleanupFailures.isEmpty()) {
            if (primaryFailure != null) {
                cleanupFailures.forEach(primaryFailure::addSuppressed);
                return;
            }
            Throwable first = cleanupFailures.getFirst();
            for (int i = 1; i < cleanupFailures.size(); i++) {
                first.addSuppressed(cleanupFailures.get(i));
            }
            if (first instanceof Exception ex) {
                throw ex;
            }
            if (first instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Smoke cleanup verification failed", first);
        }

        if (!createdDocumentIds.isEmpty()) {
            System.out.println("[SMOKE] Live cleanup verified (204 No Content, 0 Postgres rows, 0 MinIO objects): " + createdDocumentIds);
        }
    }
}
