package com.documind.infrastructure.ai;

import com.documind.infrastructure.vector.DocumentChunkVectorRepository;
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
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "smoke", matches = "true")
class Day1InfrastructureAndAiSmokeTest {

    private static final String VIETNAMESE_EMBED_SAMPLE =
        "Quy trình nghỉ phép năm của nhân viên chính thức tại doanh nghiệp";
    private static final String VIETNAMESE_CHAT_PROMPT =
        "Trả lời đúng 1 câu tiếng Việt ngắn gọn: 2 + 2 bằng mấy?";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
}
