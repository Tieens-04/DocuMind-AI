package com.documind.integration;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
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
import com.documind.service.ingestion.ChunkMetadataKeys;
import com.documind.service.ingestion.CompositeDocumentExtractor;
import com.documind.service.ingestion.DocumentIngestionContext;
import com.documind.service.ingestion.ExtractedSegment;
import com.documind.service.ingestion.IngestionProperties;
import com.documind.service.ingestion.SmartChunkingService;
import com.documind.service.rag.RagProperties;
import com.documind.service.rag.VectorRetrievalService;
import com.documind.support.AbstractIntegrationTest;
import com.documind.support.AbstractIntegrationTest.RecordingTestChatModel;
import com.documind.support.MinioTestcontainersConfig;
import com.documind.support.PostgresTestcontainersConfig;
import com.documind.support.RedisTestcontainersConfig;
import com.documind.support.SampleEnterpriseCorpusFactory;
import com.documind.support.SampleEnterpriseCorpusFactory.EvalQueryCase;
import com.documind.support.SampleEnterpriseCorpusFactory.SampleDocumentSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live calibration benchmark evaluating real {@code OllamaEmbeddingModel} ({@code bge-m3}, 1024-dim)
 * across the 5 bilingual enterprise documents and 20 Milestone 1 queries to calibrate
 * {@code chunkSize}, {@code chunkOverlap}, and {@code similarityThreshold}.
 *
 * <p>Run with {@code .\mvnw.cmd test -Dsmoke=true -Dtest=BgeM3Week1RagTuningBenchmarkTest} when Ollama is running.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true"
    }
)
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "smoke", matches = "true")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.RecordingChatModelTestConfig.class
})
class BgeM3Week1RagTuningBenchmarkTest {

    private static final List<int[]> CHUNK_CANDIDATES = List.of(
        new int[]{512, 100},
        new int[]{650, 120},
        new int[]{800, 150}
    );
    private static final double[] THRESHOLD_CANDIDATES = {0.48d, 0.52d, 0.55d, 0.58d, 0.60d, 0.62d, 0.65d, 0.68d};

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private VectorRetrievalService vectorRetrievalService;

    @Autowired
    private CompositeDocumentExtractor compositeDocumentExtractor;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private IngestionProperties activeIngestionProperties;

    @Autowired
    private RagProperties activeRagProperties;

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

    @BeforeEach
    void setUpState() throws InterruptedException {
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
    void tearDownState() throws InterruptedException {
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
    void calibratesChunkingAndSimilarityThresholdWithLiveBgeM3AndVerifiesMilestone1Pipeline() throws Exception {
        assertThat(embeddingModel).isInstanceOf(OllamaEmbeddingModel.class);
        assertThat(embeddingModel.dimensions()).isEqualTo(1024);

        List<SampleDocumentSpec> corpus = SampleEnterpriseCorpusFactory.buildWeek1EnterpriseCorpus();
        List<EvalQueryCase> evalCases = SampleEnterpriseCorpusFactory.loadWeek1MilestoneQueries(objectMapper);

        // Extract all 5 documents once via real MinIO + CompositeDocumentExtractor
        Map<SampleDocumentSpec, List<ExtractedSegment>> extractedSegmentsByDoc = new LinkedHashMap<>();
        for (SampleDocumentSpec spec : corpus) {
            UUID docId = UUID.randomUUID();
            String storageKey = "benchmark/" + spec.departmentId() + "/" + docId + "/v1/" + spec.filename();
            try (ByteArrayInputStream stream = new ByteArrayInputStream(spec.content())) {
                minioStorageService.uploadStream(storageKey, stream, spec.content().length, spec.mimeType());
            }
            DocumentIngestionContext ctx = new DocumentIngestionContext(
                docId,
                spec.departmentId(),
                storageKey,
                spec.filename(),
                extensionOf(spec.filename()),
                spec.accessLevel(),
                1
            );
            extractedSegmentsByDoc.put(spec, compositeDocumentExtractor.extract(ctx));
        }

        // Sweep (chunkSize, chunkOverlap) and similarityThreshold using live bge-m3 embeddings
        System.out.println("\n=== [DA-11] LIVE BGE-M3 WEEK 1 CALIBRATION MATRIX ===");
        System.out.printf(
            "%-11s | %-6s | %-9s | %-7s | %-7s | %-9s | %-9s | %-9s | %-7s%n",
            "Chunk/Ovlp", "Chunks", "Threshold", "Hit@5", "MRR@5", "OOS_Leak", "MinInHit", "MaxOOS", "Margin"
        );
        System.out.println("-".repeat(94));

        for (int[] chunkCandidate : CHUNK_CANDIDATES) {
            int chunkSize = chunkCandidate[0];
            int chunkOverlap = chunkCandidate[1];

            AbstractIntegrationTest.flushRedis(stringRedisTemplate);
            jdbcTemplate.update("DELETE FROM semantic_cache");
            jdbcTemplate.update("DELETE FROM documents");
            SmartChunkingService candidateChunker = new SmartChunkingService(new IngestionProperties(
                activeIngestionProperties.extraction(),
                new IngestionProperties.ChunkingProperties(chunkSize, chunkOverlap, 10_000)
            ));

            int totalChunks = 0;
            for (Map.Entry<SampleDocumentSpec, List<ExtractedSegment>> entry : extractedSegmentsByDoc.entrySet()) {
                SampleDocumentSpec spec = entry.getKey();
                DocumentEntity docEntity = persistCompletedDoc(spec);
                DocumentIngestionContext ctx = new DocumentIngestionContext(
                    docEntity.getId(),
                    spec.departmentId(),
                    docEntity.getStorageKey(),
                    spec.filename(),
                    docEntity.getFileExtension(),
                    spec.accessLevel(),
                    1
                );
                List<Document> chunks = candidateChunker.chunk(ctx, entry.getValue());
                totalChunks += chunks.size();
                vectorStore.add(chunks);
            }

            Map<EvalQueryCase, List<Document>> rawTopHitsByQuery = new LinkedHashMap<>();
            for (EvalQueryCase evalCase : evalCases) {
                CallerContext caller = callerFor(evalCase);
                Filter.Expression rbacFilter = vectorRetrievalService.buildRbacFilter(caller);
                List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(evalCase.query())
                    .topK(10)
                    .similarityThreshold(0.0d)
                    .filterExpression(rbacFilter)
                    .build());
                rawTopHitsByQuery.put(evalCase, hits != null ? hits : List.of());
            }

            double minInScopeWinningScore = 1.0d;
            double maxOosScore = 0.0d;
            for (EvalQueryCase evalCase : evalCases) {
                List<Document> hits = rawTopHitsByQuery.get(evalCase);
                if ("IN_SCOPE".equals(evalCase.category())) {
                    double bestMatchScore = hits.stream()
                        .limit(5)
                        .filter(doc -> matchesExpected(evalCase, doc))
                        .mapToDouble(BgeM3Week1RagTuningBenchmarkTest::scoreOf)
                        .max()
                        .orElse(0.0d);
                    minInScopeWinningScore = Math.min(minInScopeWinningScore, bestMatchScore);
                } else {
                    double topOos = hits.stream()
                        .mapToDouble(BgeM3Week1RagTuningBenchmarkTest::scoreOf)
                        .max()
                        .orElse(0.0d);
                    maxOosScore = Math.max(maxOosScore, topOos);
                }
            }

            for (double threshold : THRESHOLD_CANDIDATES) {
                int inScopeTotal = 0;
                int inScopeHits = 0;
                double rrSum = 0.0d;
                int oosTotal = 0;
                int oosLeaks = 0;

                for (EvalQueryCase evalCase : evalCases) {
                    List<Document> filteredTop5 = rawTopHitsByQuery.get(evalCase).stream()
                        .filter(doc -> scoreOf(doc) >= threshold)
                        .limit(5)
                        .toList();

                    if ("IN_SCOPE".equals(evalCase.category())) {
                        inScopeTotal++;
                        int rank = firstMatchingRank(evalCase, filteredTop5);
                        if (rank > 0) {
                            inScopeHits++;
                            rrSum += 1.0d / rank;
                        }
                    } else {
                        oosTotal++;
                        if (!filteredTop5.isEmpty()) {
                            oosLeaks++;
                        }
                    }
                }

                double hitRate = (double) inScopeHits / inScopeTotal;
                double mrr = rrSum / inScopeTotal;
                double oosLeakRate = (double) oosLeaks / oosTotal;
                double margin = minInScopeWinningScore - maxOosScore;
                System.out.printf(
                    Locale.ROOT,
                    "%-11s | %-6d | %-9.2f | %-6.1f%% | %-7.3f | %-8.1f%% | %-9.4f | %-9.4f | %-+7.4f%n",
                    chunkSize + "/" + chunkOverlap,
                    totalChunks,
                    threshold,
                    hitRate * 100.0d,
                    mrr,
                    oosLeakRate * 100.0d,
                    minInScopeWinningScore,
                    maxOosScore,
                    margin
                );
            }

            // Print per-query top-1 diagnostic for visibility
            System.out.println("  Per-query scores (" + chunkSize + "/" + chunkOverlap + "):");
            for (EvalQueryCase evalCase : evalCases) {
                List<Document> hits = rawTopHitsByQuery.get(evalCase);
                double topScore = hits.isEmpty() ? 0.0d : scoreOf(hits.getFirst());
                String topFile = hits.isEmpty() ? "NONE" : String.valueOf(hits.getFirst().getMetadata().get(ChunkMetadataKeys.FILE_NAME));
                int matchRank = firstMatchingRank(evalCase, hits.stream().limit(5).toList());
                System.out.printf(
                    Locale.ROOT,
                    "    [%s] %-12s top1=%.4f (%s) matchRank@5=%d%n",
                    evalCase.id(),
                    evalCase.category(),
                    topScore,
                    topFile,
                    matchRank
                );
            }
        }

        // Now verify the active production configuration from application.yml end-to-end via HTTP API
        setUpState();
        List<DocumentResponse> uploaded = new ArrayList<>(corpus.size());
        for (SampleDocumentSpec spec : corpus) {
            uploaded.add(uploadAndAwaitCompleted(spec));
        }
        assertThat(uploaded).hasSize(5);

        int e2eInScopeHits = 0;
        int e2eInScopeTotal = 0;
        int e2eOosViolations = 0;
        int e2eOosTotal = 0;

        for (EvalQueryCase evalCase : evalCases) {
            UUID deptId = SampleEnterpriseCorpusFactory.departmentIdForCode(evalCase.dept());
            recordingChatModel.reset();
            ResponseEntity<ChatAskResponse> resp = askQuestion(evalCase.query(), deptId, evalCase.role());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            ChatAskResponse body = Objects.requireNonNull(resp.getBody());

            if ("IN_SCOPE".equals(evalCase.category())) {
                e2eInScopeTotal++;
                String promptLower = AbstractIntegrationTest.normalizeNfcLower(recordingChatModel.lastSystemPrompt());
                boolean hit = !body.abstained()
                    && body.citations().stream().anyMatch(c ->
                        Objects.equals(evalCase.expectedFile(), c.fileName())
                            && (evalCase.expectedPage() == null || Objects.equals(evalCase.expectedPage(), c.pageNumber()))
                    )
                    && promptLower.contains(AbstractIntegrationTest.normalizeNfcLower(evalCase.expectedKeyPhrase()));
                if (hit) {
                    e2eInScopeHits++;
                } else {
                    System.out.println("[MISS][E2E] " + evalCase.id() + " abstained=" + body.abstained()
                        + " citations=" + body.citations());
                }
            } else {
                e2eOosTotal++;
                if (!AbstractIntegrationTest.isSafeOosRefusalResponse(evalCase, body, recordingChatModel)) {
                    e2eOosViolations++;
                    System.out.println("[LEAK][E2E] " + evalCase.id() + " citations=" + body.citations());
                }
            }
        }

        double e2eHitRate = (double) e2eInScopeHits / e2eInScopeTotal;
        double e2eHallucinationRate = (double) e2eOosViolations / e2eOosTotal;
        System.out.printf(
            Locale.ROOT,
            "%n[DA-11][ACTIVE CONFIG] chunkSize=%d chunkOverlap=%d threshold=%.2f => E2E Hit@5=%.2f%% (%d/%d), HallucinationRate=%.2f%% (%d/%d)%n",
            activeIngestionProperties.chunking().chunkSize(),
            activeIngestionProperties.chunking().chunkOverlap(),
            activeRagProperties.retrieval().similarityThreshold(),
            e2eHitRate * 100.0d,
            e2eInScopeHits,
            e2eInScopeTotal,
            e2eHallucinationRate * 100.0d,
            e2eOosViolations,
            e2eOosTotal
        );

        assertThat(e2eHitRate).isGreaterThanOrEqualTo(0.85d);
        assertThat(e2eHallucinationRate).isZero();
    }

    private DocumentEntity persistCompletedDoc(SampleDocumentSpec spec) {
        DepartmentEntity dept = departmentRepository.findById(spec.departmentId()).orElseThrow();
        DocumentEntity entity = new DocumentEntity();
        entity.setDepartment(dept);
        entity.setOriginalName(spec.filename());
        entity.setStorageKey("benchmark/" + spec.departmentId() + "/" + UUID.randomUUID() + "/v1/" + spec.filename());
        entity.setFileExtension(extensionOf(spec.filename()));
        entity.setMimeType(spec.mimeType());
        entity.setFileSizeBytes((long) spec.content().length);
        entity.setFileHash(sha256Hex(spec.content()));
        entity.setAccessLevel(spec.accessLevel());
        entity.setStatus(DocumentStatus.COMPLETED);
        entity.setCurrentVersion(1);
        entity.setChunkCount(1);
        return documentRepository.saveAndFlush(entity);
    }

    private DocumentResponse uploadAndAwaitCompleted(SampleDocumentSpec spec) throws InterruptedException {
        UUID uploaderId = AbstractIntegrationTest.resolveTestUserId("DEPARTMENT_MANAGER", spec.departmentId());
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(spec.mimeType()));
        ByteArrayResource resource = new ByteArrayResource(spec.content()) {
            @Override
            public String getFilename() {
                return spec.filename();
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        body.add("accessLevel", spec.accessLevel().name());
        body.add("departmentId", spec.departmentId().toString());

        HttpHeaders headers = AbstractIntegrationTest.buildCallerHeaders(
            spec.departmentId(),
            "DEPARTMENT_MANAGER",
            uploaderId
        );
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<DocumentResponse> uploadResp = restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(body, headers),
            DocumentResponse.class
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse doc = Objects.requireNonNull(uploadResp.getBody());

        HttpHeaders pollHeaders = AbstractIntegrationTest.buildCallerHeaders(
            spec.departmentId(),
            "DEPARTMENT_MANAGER",
            uploaderId
        );
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            ResponseEntity<DocumentStatusResponse> statusResp = restTemplate.exchange(
                "/api/v1/documents/" + doc.id() + "/status",
                HttpMethod.GET,
                new HttpEntity<>(pollHeaders),
                DocumentStatusResponse.class
            );
            if (statusResp.getStatusCode() == HttpStatus.OK && statusResp.getBody() != null) {
                DocumentStatus status = statusResp.getBody().status();
                if (status == DocumentStatus.COMPLETED) {
                    return doc;
                }
                if (status == DocumentStatus.FAILED) {
                    throw new AssertionError("Ingestion failed: " + statusResp.getBody().errorMessage());
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out awaiting completion for " + spec.filename());
    }

    private ResponseEntity<ChatAskResponse> askQuestion(String question, UUID deptId, String role) {
        UUID userId = AbstractIntegrationTest.resolveTestUserId(role, deptId);
        HttpHeaders headers = AbstractIntegrationTest.buildCallerHeaders(deptId, role, userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
            "/api/v1/chat/ask",
            new HttpEntity<>(Map.of("question", question), headers),
            ChatAskResponse.class
        );
    }

    private static CallerContext callerFor(EvalQueryCase evalCase) {
        UUID deptId = SampleEnterpriseCorpusFactory.departmentIdForCode(evalCase.dept());
        UUID userId = AbstractIntegrationTest.resolveTestUserId(evalCase.role(), deptId);
        Role role = Role.valueOf(evalCase.role());
        return new CallerContext(userId, deptId, role, "127.0.0.1");
    }

    private static int firstMatchingRank(EvalQueryCase evalCase, List<Document> top5) {
        for (int i = 0; i < top5.size(); i++) {
            if (matchesExpected(evalCase, top5.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static boolean matchesExpected(EvalQueryCase evalCase, Document doc) {
        if (evalCase.expectedFile() == null || doc == null) {
            return false;
        }
        Object fileObj = doc.getMetadata().get(ChunkMetadataKeys.FILE_NAME);
        if (!Objects.equals(evalCase.expectedFile(), fileObj != null ? fileObj.toString() : null)) {
            return false;
        }
        if (evalCase.expectedPage() != null) {
            Object pageObj = doc.getMetadata().get(ChunkMetadataKeys.PAGE_NUMBER);
            Integer page = pageObj instanceof Number n ? n.intValue() : null;
            if (!Objects.equals(evalCase.expectedPage(), page)) {
                return false;
            }
        }
        if (evalCase.expectedKeyPhrase() != null) {
            String textNorm = AbstractIntegrationTest.normalizeNfcLower(doc.getText());
            String phraseNorm = AbstractIntegrationTest.normalizeNfcLower(evalCase.expectedKeyPhrase());
            return textNorm.contains(phraseNorm);
        }
        return true;
    }

    private static double scoreOf(Document doc) {
        if (doc == null || doc.getScore() == null || !Double.isFinite(doc.getScore())) {
            return 0.0d;
        }
        return doc.getScore();
    }

    private static String extensionOf(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 failed", ex);
        }
    }
}
