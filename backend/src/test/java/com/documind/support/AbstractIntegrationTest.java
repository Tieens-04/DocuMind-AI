package com.documind.support;

import com.documind.controller.dto.ChatAskResponse;
import com.documind.controller.dto.CitationDto;
import com.documind.controller.dto.DocumentResponse;
import com.documind.controller.dto.DocumentStatusResponse;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.infrastructure.storage.StorageProperties;
import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.service.rag.RagConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared integration test foundation backed by real singleton Testcontainers
 * ({@code pgvector/pgvector:pg16}, {@code redis:7-alpine}, {@code minio}) plus deterministic AI doubles
 * and clean state isolation between tests.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "DOCUMIND_ENV_FILE=.env.test-isolated",
        "spring.main.allow-bean-definition-overriding=true",
        "documind.ingestion.worker.enabled=true",
        "documind.security.dev-caller-fallback.enabled=true"
    }
)
@ActiveProfiles("dev")
@Import({
    PostgresTestcontainersConfig.ContainerOnly.class,
    RedisTestcontainersConfig.class,
    MinioTestcontainersConfig.class,
    AbstractIntegrationTest.DefaultDeterministicAiTestConfig.class
})
public abstract class AbstractIntegrationTest {

    public static final UUID IT_DEPT_ID = SampleEnterpriseCorpusFactory.IT_DEPT_ID;
    public static final UUID HR_DEPT_ID = SampleEnterpriseCorpusFactory.HR_DEPT_ID;
    public static final UUID SALES_DEPT_ID = SampleEnterpriseCorpusFactory.SALES_DEPT_ID;
    public static final UUID ADMIN_USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    public static final UUID HR_MANAGER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    public static final UUID SALES_EMPLOYEE_USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    public static final UUID IT_MANAGER_USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555551");
    public static final UUID IT_EMPLOYEE_USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555552");
    public static final UUID HR_EMPLOYEE_USER_ID = UUID.fromString("22222222-2222-3333-4444-555555555553");
    public static final UUID SALES_MANAGER_USER_ID = UUID.fromString("33333333-2222-3333-4444-555555555554");
    private static final String DEV_SEED_BCRYPT_HASH =
        "$2a$10$q6.0PtDMsJDIKJWkOmdXFefwSPRiXyK4dWz5JIOMMZUEt/e35s8yi";

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate stringRedisTemplate;

    @Autowired
    protected S3Client s3Client;

    @Autowired
    protected StorageProperties storageProperties;

    @Autowired
    protected DocumentRepository documentRepository;

    @Autowired
    protected DepartmentRepository departmentRepository;

    @Autowired
    protected DocumentChunkVectorRepository chunkVectorRepository;

    @Autowired
    protected VectorStore vectorStore;

    @Autowired
    protected RecordingTestChatModel recordingChatModel;

    @Autowired
    protected EmbeddingModel embeddingModel;

    @BeforeEach
    void setUpIntegrationEnvironment() throws InterruptedException {
        cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            recordingChatModel,
            Duration.ofSeconds(15)
        );
        seedSupplementalTestUsers(jdbcTemplate);
    }

    @AfterEach
    void tearDownIntegrationEnvironment() throws InterruptedException {
        cleanSharedInfrastructure(
            jdbcTemplate,
            stringRedisTemplate,
            s3Client,
            storageProperties.bucket(),
            recordingChatModel,
            Duration.ofSeconds(15)
        );
    }

    protected ResponseEntity<DocumentResponse> uploadDocument(
        String filename,
        String contentType,
        byte[] content,
        UUID departmentId,
        AccessLevel accessLevel,
        String role,
        UUID userId
    ) {
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        ByteArrayResource resource = new ByteArrayResource(content) {
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
        if (departmentId != null) {
            body.add("departmentId", departmentId.toString());
        }

        HttpHeaders headers = buildCallerHeaders(departmentId, role, userId);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return restTemplate.postForEntity(
            "/api/v1/documents/upload",
            new HttpEntity<>(body, headers),
            DocumentResponse.class
        );
    }

    protected DocumentResponse uploadAndAwaitCompleted(
        String filename,
        String contentType,
        byte[] content,
        UUID departmentId,
        AccessLevel accessLevel,
        String role,
        UUID userId,
        Duration timeout
    ) throws InterruptedException {
        ResponseEntity<DocumentResponse> uploadResp = uploadDocument(
            filename,
            contentType,
            content,
            departmentId,
            accessLevel,
            role,
            userId
        );
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        DocumentResponse doc = Objects.requireNonNull(uploadResp.getBody());

        DocumentStatusResponse terminal = awaitTerminalStatus(doc.id(), departmentId, role, userId, timeout);
        if (terminal.status() != DocumentStatus.COMPLETED) {
            throw new AssertionError(
                "Expected COMPLETED ingestion for " + filename + " but got " + terminal.status()
                    + " (error=" + terminal.errorMessage() + ")"
            );
        }
        return doc;
    }

    protected DocumentStatusResponse awaitTerminalStatus(
        UUID documentId,
        UUID departmentId,
        String role,
        UUID userId,
        Duration timeout
    ) throws InterruptedException {
        HttpHeaders headers = buildCallerHeaders(departmentId, role, userId);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        DocumentStatusResponse lastStatus = null;
        while (System.nanoTime() < deadlineNanos) {
            ResponseEntity<DocumentStatusResponse> resp = restTemplate.exchange(
                "/api/v1/documents/" + documentId + "/status",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                DocumentStatusResponse.class
            );
            if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                lastStatus = resp.getBody();
                if (lastStatus.status() == DocumentStatus.COMPLETED || lastStatus.status() == DocumentStatus.FAILED) {
                    return lastStatus;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(
            "Timed out waiting for terminal ingestion status of " + documentId + ", lastStatus=" + lastStatus
        );
    }

    protected ResponseEntity<String> getDocumentStatusRaw(UUID documentId, UUID departmentId, String role) {
        return getDocumentStatusRaw(documentId, departmentId, role, null);
    }

    protected ResponseEntity<String> getDocumentStatusRaw(
        UUID documentId,
        UUID departmentId,
        String role,
        UUID userId
    ) {
        HttpHeaders headers = buildCallerHeaders(departmentId, role, userId);
        return restTemplate.exchange(
            "/api/v1/documents/" + documentId + "/status",
            HttpMethod.GET,
            new HttpEntity<>(headers),
            String.class
        );
    }

    protected ResponseEntity<ChatAskResponse> askQuestion(
        String question,
        UUID departmentId,
        String role,
        UUID userId
    ) {
        HttpHeaders headers = buildCallerHeaders(departmentId, role, userId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
            "/api/v1/chat/ask",
            new HttpEntity<>(Map.of("question", question), headers),
            ChatAskResponse.class
        );
    }

    protected ResponseEntity<Void> deleteDocument(
        UUID documentId,
        UUID departmentId,
        String role,
        UUID userId
    ) {
        HttpHeaders headers = buildCallerHeaders(departmentId, role, userId);
        return restTemplate.exchange(
            "/api/v1/documents/" + documentId,
            HttpMethod.DELETE,
            new HttpEntity<>(headers),
            Void.class
        );
    }

    protected void awaitIngestionWorkersIdle(Duration timeout) throws InterruptedException {
        awaitWorkersIdle(jdbcTemplate, timeout);
    }

    /**
     * Waits for active ingestion workers, clears MinIO, flushes Redis, cleans FK-ordered tables,
     * removes supplemental test users so only V3 seed accounts remain, and resets AI test doubles.
     */
    public static void cleanSharedInfrastructure(
        JdbcTemplate jdbcTemplate,
        StringRedisTemplate stringRedisTemplate,
        S3Client s3Client,
        String bucket,
        RecordingTestChatModel recordingChatModel,
        Duration workerTimeout
    ) throws InterruptedException {
        awaitWorkersIdle(jdbcTemplate, workerTimeout);
        clearS3Bucket(s3Client, bucket);
        flushRedis(stringRedisTemplate);
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM chat_messages");
        jdbcTemplate.update("DELETE FROM chat_sessions");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM refresh_tokens");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update(
            "DELETE FROM users WHERE id NOT IN (?, ?, ?)",
            ADMIN_USER_ID,
            HR_MANAGER_USER_ID,
            SALES_EMPLOYEE_USER_ID
        );
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
        if (recordingChatModel != null) {
            recordingChatModel.reset();
        }
    }

    /**
     * Upserts deterministic test user accounts for every role/department pair outside V3 dev seeds
     * so RBAC integration tests always bind a persisted non-null {@code user_id}.
     */
    public static void seedSupplementalTestUsers(JdbcTemplate jdbcTemplate) {
        String sql = """
            INSERT INTO users (id, department_id, email, password_hash, full_name, role, is_active)
            VALUES (?, ?, ?, ?, ?, ?, TRUE)
            ON CONFLICT (id) DO UPDATE SET
                department_id = EXCLUDED.department_id,
                email = EXCLUDED.email,
                full_name = EXCLUDED.full_name,
                role = EXCLUDED.role,
                is_active = TRUE
            """;
        Object[][] supplementalUsers = {
            {IT_MANAGER_USER_ID, IT_DEPT_ID, "manager.it@documind.ai", DEV_SEED_BCRYPT_HASH, "IT Department Manager", "DEPARTMENT_MANAGER"},
            {IT_EMPLOYEE_USER_ID, IT_DEPT_ID, "employee.it@documind.ai", DEV_SEED_BCRYPT_HASH, "IT Standard Employee", "STANDARD_EMPLOYEE"},
            {HR_EMPLOYEE_USER_ID, HR_DEPT_ID, "employee.hr@documind.ai", DEV_SEED_BCRYPT_HASH, "HR Standard Employee", "STANDARD_EMPLOYEE"},
            {SALES_MANAGER_USER_ID, SALES_DEPT_ID, "manager.sales@documind.ai", DEV_SEED_BCRYPT_HASH, "Sales Department Manager", "DEPARTMENT_MANAGER"}
        };
        for (Object[] user : supplementalUsers) {
            jdbcTemplate.update(sql, user);
        }
    }

    /**
     * Resolves a seeded or supplemental test user ID for a {@code (role, departmentId)} pair,
     * failing fast instead of returning {@code null}.
     */
    public static UUID resolveTestUserId(String role, UUID deptId) {
        if (role == null || role.isBlank() || deptId == null) {
            throw new IllegalArgumentException(
                "role and deptId must not be null/blank (role=" + role + ", deptId=" + deptId + ")"
            );
        }
        if ("SYSTEM_ADMIN".equals(role) && IT_DEPT_ID.equals(deptId)) {
            return ADMIN_USER_ID;
        }
        if ("DEPARTMENT_MANAGER".equals(role)) {
            if (IT_DEPT_ID.equals(deptId)) {
                return IT_MANAGER_USER_ID;
            }
            if (HR_DEPT_ID.equals(deptId)) {
                return HR_MANAGER_USER_ID;
            }
            if (SALES_DEPT_ID.equals(deptId)) {
                return SALES_MANAGER_USER_ID;
            }
        }
        if ("STANDARD_EMPLOYEE".equals(role)) {
            if (IT_DEPT_ID.equals(deptId)) {
                return IT_EMPLOYEE_USER_ID;
            }
            if (HR_DEPT_ID.equals(deptId)) {
                return HR_EMPLOYEE_USER_ID;
            }
            if (SALES_DEPT_ID.equals(deptId)) {
                return SALES_EMPLOYEE_USER_ID;
            }
        }
        throw new IllegalArgumentException("No seeded test user for role=" + role + ", deptId=" + deptId);
    }

    /**
     * Verifies deterministic safe refusal and checks answer, citation excerpts/file names, and system prompt
     * for any forbidden secret leakage.
     */
    public static boolean isSafeOosRefusalResponse(
        SampleEnterpriseCorpusFactory.EvalQueryCase oosCase,
        ChatAskResponse body,
        RecordingTestChatModel chatModel
    ) {
        if (body == null) {
            return false;
        }
        boolean safe = body.abstained()
            && RagConstants.SAFE_REFUSAL_MESSAGE.equals(body.answer())
            && body.citations() != null
            && body.citations().isEmpty()
            && (chatModel == null || chatModel.callCount() == 0);
        if (oosCase != null && oosCase.forbiddenPhrase() != null && !oosCase.forbiddenPhrase().isBlank()) {
            String lowerForbidden = normalizeNfcLower(oosCase.forbiddenPhrase());
            if (normalizeNfcLower(body.answer()).contains(lowerForbidden)) {
                safe = false;
            }
            if (body.citations() != null) {
                for (CitationDto citation : body.citations()) {
                    if (normalizeNfcLower(citation.excerpt()).contains(lowerForbidden)
                        || normalizeNfcLower(citation.fileName()).contains(lowerForbidden)) {
                        safe = false;
                    }
                }
            }
            if (chatModel != null && normalizeNfcLower(chatModel.lastSystemPrompt()).contains(lowerForbidden)) {
                safe = false;
            }
        }
        return safe;
    }

    public static void awaitWorkersIdle(JdbcTemplate jdbcTemplate, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
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
        throw new AssertionError("Timed out waiting for active ingestion workers to finish");
    }

    public static void clearS3Bucket(S3Client s3Client, String bucket) {
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

    public static void flushRedis(StringRedisTemplate stringRedisTemplate) {
        if (stringRedisTemplate == null || stringRedisTemplate.getConnectionFactory() == null) {
            return;
        }
        try (RedisConnection connection = stringRedisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    public static HttpHeaders buildCallerHeaders(UUID departmentId, String role, UUID userId) {
        HttpHeaders headers = new HttpHeaders();
        if (departmentId != null) {
            headers.set("X-Department-Id", departmentId.toString());
        }
        if (role != null && !role.isBlank()) {
            headers.set("X-User-Role", role);
        }
        if (userId != null) {
            headers.set("X-User-Id", userId.toString());
        }
        return headers;
    }

    public static String normalizeNfcLower(String text) {
        if (text == null) {
            return "";
        }
        String nfc = Normalizer.isNormalized(text, Normalizer.Form.NFC)
            ? text
            : Normalizer.normalize(text, Normalizer.Form.NFC);
        return nfc.toLowerCase(Locale.ROOT);
    }

    /**
     * Replaces every {@link ChatClient} and {@link ChatClient.Builder} bean by type (not bean name)
     * with a single memoized {@link RecordingTestChatModel} instance.
     */
    public static BeanPostProcessor createRecordingChatClientPostProcessor(
        ObjectProvider<RecordingTestChatModel> recordingChatModelProvider
    ) {
        return new BeanPostProcessor() {
            private final Object monitor = new Object();
            private volatile RecordingTestChatModel resolvedModel;

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

            private RecordingTestChatModel resolveModel() {
                RecordingTestChatModel current = resolvedModel;
                if (current != null) {
                    return current;
                }
                synchronized (monitor) {
                    if (resolvedModel == null) {
                        resolvedModel = recordingChatModelProvider.getObject();
                    }
                    return resolvedModel;
                }
            }
        };
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class RecordingChatModelTestConfig {

        @Bean
        @Primary
        RecordingTestChatModel recordingTestChatModel() {
            return new RecordingTestChatModel();
        }

        @Bean
        static BeanPostProcessor testChatClientOverridePostProcessor(
            ObjectProvider<RecordingTestChatModel> recordingChatModelProvider
        ) {
            return createRecordingChatClientPostProcessor(recordingChatModelProvider);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(RecordingChatModelTestConfig.class)
    public static class DefaultDeterministicAiTestConfig {

        @Bean(name = "deterministicTestEmbeddingModel")
        @Primary
        EmbeddingModel semanticCorpusTestEmbeddingModel() {
            return new SemanticCorpusTestEmbeddingModel();
        }
    }

    public static final class RecordingTestChatModel implements ChatModel {

        private static final Pattern CITATION_ATTR_PATTERN = Pattern.compile("citation=\"([^\"]+)\"");
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<Prompt> lastPrompt = new AtomicReference<>();
        private final AtomicBoolean txActiveOnLastCall = new AtomicBoolean(false);

        public void reset() {
            callCount.set(0);
            lastPrompt.set(null);
            txActiveOnLastCall.set(false);
        }

        public int callCount() {
            return callCount.get();
        }

        public boolean wasTransactionActiveOnLastCall() {
            return txActiveOnLastCall.get();
        }

        public String lastSystemPrompt() {
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
                .usage(new DefaultUsage(125, 48, 173))
                .build();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
        }
    }

    /**
     * Deterministic 1024-dim unit-normalized test double projecting bilingual enterprise concepts into
     * orthogonal subspaces so Testcontainers pgvector cosine queries run offline without external Ollama calls.
     */
    private static final class SemanticCorpusTestEmbeddingModel implements EmbeddingModel {

        private static final float CONCEPT_BOOST = 14.0f;

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

            if (containsAny(lower, "thử việc", "60 ngày", "probation")) {
                vector[0] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "nghỉ phép", "phép năm", "thâm niên", "14 ngày phép", "annual leave")) {
                vector[1] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "pvi care", "bảo hiểm sức khỏe", "khám sức khỏe", "nội trú")) {
                vector[2] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "khách sạn", "công tác phí", "lưu trú nội địa", "1.500.000")) {
                vector[3] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "hoàn ứng", "7 ngày làm việc", "phụ cấp lưu trú")) {
                vector[4] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "hoa hồng doanh số", "chi trả hoa hồng", "ngày 15", "sales commission")) {
                vector[5] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "passphrase", "fido2", "webauthn", "mfa", "14 characters")) {
                vector[6] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "api key", "rotated", "xoay vòng", "khóa api", "security operations center", "soc")) {
                vector[7] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "canary rollout", "canary release", "traffic percentages", "soak")) {
                vector[8] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "rollback", "p95", "800 milliseconds", "5xx")) {
                vector[9] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "postmortem", "post-incident", "48 hours")) {
                vector[10] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "làm việc từ xa", "remote work", "hybrid work", "khung giờ phối hợp", "09:30")) {
                vector[11] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "wireguard", "vpn", "screen lock", "khóa màn hình")) {
                vector[12] += CONCEPT_BOOST;
            }
            if (containsAny(lower, "văn hóa doanh nghiệp", "giá trị cốt lõi")) {
                vector[13] += CONCEPT_BOOST;
            }
            return normalizeL2(vector);
        }

        private static boolean containsAny(String haystack, String... needles) {
            for (String needle : needles) {
                if (haystack.contains(needle)) {
                    return true;
                }
            }
            return false;
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
