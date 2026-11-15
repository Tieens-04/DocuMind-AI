# DA-5 Day 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Initialize the DocuMind AI monorepo, 16GB-RAM-tuned Docker stack (PostgreSQL `pgvector`, Redis 7, MinIO + bucket init), Windows-native Ollama `bge-m3` (`1024d`), and Spring Boot 3.4 / Java 21 / Spring AI 1.0.3 backend with separated Ollama `EmbeddingModel` and Groq/Gemini `ChatClient` beans.

**Architecture:** A monorepo (`backend/`, `frontend/`, `eval/`, `load-tests/`) backed by a 4-service `docker-compose.yml` mapped to FlintFlow-safe host ports (`5432`, `6380`, `9000/9001`, backend `8081`, frontend `3002`). Spring AI `1.0.3` auto-configuration selects `ollama` for embeddings (`bge-m3`, `1024d`) and `openai` for primary Groq chat, while `AiModelConfig` derives a secondary `@Qualifier("geminiChatClient")` bean via `OpenAiApi.mutate()` with `completionsPath=/chat/completions` and keeps PostgreSQL schema creation disabled (`initialize-schema=false`, `ddl-auto=none`) for `DA-6` Flyway migrations.

**Tech Stack:** Java 21 (Virtual Threads), Spring Boot `3.4.5`, Spring AI `1.0.3` GA, PostgreSQL 16 (`pgvector/pgvector:pg16`), Redis 7 (`redis:7-alpine`), MinIO + AWS SDK S3 v2 (`2.30.26`), Flyway, SpringDoc OpenAPI `2.8.5`, Ollama (`bge-m3`), Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-11-15-da5-day1-foundation-design.md`

## Global Constraints

- **Host Coexistence (Never Stop FlintFlow):** Never stop or modify running `flintflow-*` containers; bind Redis to host `6380`, Backend to `8081`, Frontend (reserved) to `3002`, Postgres to `5432`, MinIO to `9000`/`9001`, Ollama to `11434`.
- **Maven Wrapper Only:** Commit and execute `.\mvnw.cmd` (PowerShell) / `./mvnw` (Linux/CI) from `backend/`; never invoke global `mvn`.
- **Java 21 Standards:** Enable `spring.threads.virtual.enabled=true` and use Java `record` for `@ConfigurationProperties` and DTOs.
- **Spring AI 1.0.3 GA Coordinates Only:** Use `spring-ai-bom:1.0.3`, `spring-ai-starter-model-openai`, `spring-ai-starter-model-ollama`, `spring-ai-starter-vector-store-pgvector`, `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`.
- **Clean DB for `DA-6`:** Set `spring.jpa.hibernate.ddl-auto=none` and `spring.ai.vectorstore.pgvector.initialize-schema=false` (`table-name=document_chunks`, `dimensions=1024`).

## Review Focus

1. **Blank `GROQ_API_KEY=""` or `GEMINI_API_KEY=""` in `.env`:** Spring AI `OpenAiApi` throws `IllegalArgumentException` on empty strings during bean initialization, crashing startup; normalize blank keys to `"unconfigured-api-key"` (`AiModelConfigTest`).
2. **Trailing slash in `GEMINI_BASE_URL` or missing leading slash in `completionsPath`:** Causes `//chat/completions` or `404` on Google's `/v1beta/openai/chat/completions` endpoint; normalize URL/path in `AiModelConfig` (`AiModelConfigTest`).
3. **MinIO down or `documind-documents` bucket missing:** `/actuator/health` must degrade cleanly (`DOWN` with `bucket` detail) rather than throwing an unhandled `S3Exception` (`MinioHealthIndicatorTest`).
4. **Premature `vector_store(1536)` table creation:** Would conflict with `DA-6` `V1..V3` Flyway scripts; assert `public.vector_store` does not exist after live startup (`Day1InfrastructureAndAiSmokeTest`).
5. **Vietnamese UTF-8 diacritics on Windows JVM:** Maven Surefire and JVM must run with `UTF-8` so `bge-m3` embeds accented Vietnamese text into finite `float[1024]` vectors (`Day1InfrastructureAndAiSmokeTest`).

---

### Task 1: Monorepo Scaffold, Port Synchronization (`AGENTS.md` + Jira), & Docker Stack

**Files:**
- Modify: `AGENTS.md`
- Create: `.gitignore`
- Create: `.env.example`
- Create: `.env` (git-ignored local copy from `.env.example`)
- Create: `docker-compose.yml`
- Create: `frontend/.gitkeep`, `eval/.gitkeep`, `load-tests/.gitkeep`

**Interfaces:**
- Produces:
  - Env keys in `.env.example`: `SERVER_PORT=8081`, `FRONTEND_PORT=3002`, `POSTGRES_PORT=5432`, `POSTGRES_DB=documind`, `POSTGRES_USER=documind`, `POSTGRES_PASSWORD=documind_secret`, `REDIS_PORT=6380`, `MINIO_API_PORT=9000`, `MINIO_CONSOLE_PORT=9001`, `MINIO_ROOT_USER=documind_minio`, `MINIO_ROOT_PASSWORD=documind_minio_secret`, `MINIO_BUCKET=documind-documents`, `OLLAMA_BASE_URL=http://localhost:11434`, `OLLAMA_EMBEDDING_MODEL=bge-m3`, `GROQ_BASE_URL=https://api.groq.com/openai`, `GROQ_API_KEY=`, `GROQ_CHAT_MODEL=llama-3.3-70b-versatile`, `GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai`, `GEMINI_COMPLETIONS_PATH=/chat/completions`, `GEMINI_API_KEY=`, `GEMINI_CHAT_MODEL=gemini-2.5-flash`.
  - Live Docker endpoints: `localhost:5432` (Postgres), `localhost:6380` (Redis), `localhost:9000` & `localhost:9001` (MinIO with private bucket `documind-documents`).

- [ ] **Step 1: Transition Jira `DA-5` to `In Progress` and update port references in Jira (`DA-5`, `DA-21`, `DA-25`) and `AGENTS.md`**

Transition `DA-5` via `jira-cwb:jira_transition_issue` (`In Progress`), append/update the canonical port mapping note (`Backend 8081`, `Redis 6380`, `Frontend 3002`, `Postgres 5432`, `MinIO 9000/9001`, `Ollama 11434`) on `DA-5`, `DA-21`, and `DA-25` via `jira_update_issue`, and add a `Local Host Port Allocation` subsection under Section 2 of `AGENTS.md`.

- [ ] **Step 2: Create Monorepo directories, `.gitignore`, `.env.example`, and untracked `.env`**

Create `frontend/.gitkeep`, `eval/.gitkeep`, `load-tests/.gitkeep`. Configure `.gitignore` to exclude `.env`, `.env.local`, `backend/target/`, `frontend/.next/`, `frontend/node_modules/`, `.idea/`, `*.iml`, `.vscode/`, and OS temp files. Copy `.env.example` to `.env`.

- [ ] **Step 3: Write `docker-compose.yml` (4 services tuned for 16GB RAM)**

Define `postgres` (`pgvector/pgvector:pg16`, `mem_limit: 1g`, tuned flags `-c shared_buffers=256MB -c work_mem=16MB -c maintenance_work_mem=128MB -c max_connections=100`, `pg_isready` healthcheck), `redis` (`redis:7-alpine`, `${REDIS_PORT:-6380}:6379`, `mem_limit: 256m`, `maxmemory 192mb --maxmemory-policy allkeys-lru`, `redis-cli ping` healthcheck), `minio` (`minio/minio:RELEASE.2025-02-28T09-55-16Z`, `9000`/`9001`, `mem_limit: 512m`, `mc ready local` healthcheck), and `minio-init` (`minio/mc:RELEASE.2025-02-21T16-00-46Z`, `depends_on: minio (service_healthy)`, creates `local/${MINIO_BUCKET:-documind-documents}` and sets `mc anonymous set none`).

- [ ] **Step 4: Boot Docker stack and verify all 4 services + MinIO bucket & FlintFlow safety**

Run:
```powershell
docker compose config -q
docker compose up -d
docker compose ps
docker run --rm --network documind-ai_default --entrypoint sh minio/mc:RELEASE.2025-02-21T16-00-46Z -c "mc alias set local http://minio:9000 documind_minio documind_minio_secret >/dev/null && mc ls local/ && mc anonymous get local/documind-documents"
```
Expected: `postgres`, `redis`, `minio` show `(healthy)`; `minio-init` exited `0`; `documind-documents/` is listed with `Access permission ... is 'none'`; `flintflow-*` containers remain untouched and healthy.

- [ ] **Step 5: Commit baseline monorepo & Docker infrastructure**

```powershell
git add .agents AGENTS.md product_overview_enterprise_ai_knowledge_assistant.md .gitignore .env.example docker-compose.yml frontend/.gitkeep eval/.gitkeep load-tests/.gitkeep
git commit -m "chore(infra): initialize monorepo, port allocation, and 16GB-tuned docker stack (DA-5)"
```

---

### Task 2: Windows-Native Ollama (`bge-m3`) & Backend Spring Boot 3.4 Skeleton

**Files:**
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/mvnw`, `backend/mvnw.cmd`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/documind/DocuMindApplication.java`
- Create: `backend/src/main/java/com/documind/infrastructure/config/OpenApiConfig.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/.gitkeep`
- Create: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/documind/DocuMindApplicationTests.java`

**Interfaces:**
- Consumes: `.env` keys from Task 1 (`SERVER_PORT`, `POSTGRES_*`, `REDIS_PORT`, `MINIO_*`, `OLLAMA_*`, `GROQ_*`, `GEMINI_*`).
- Produces:
  - Maven Wrapper `backend/mvnw.cmd` (Maven `3.9.9`) + `backend/pom.xml` with UTF-8 compiler/surefire settings.
  - `com.documind.DocuMindApplication` (`@SpringBootApplication`, `@ConfigurationPropertiesScan`).
  - `com.documind.infrastructure.config.OpenApiConfig` registering `OpenAPI` metadata (`DocuMind AI API`, `v1.0.0`).

- [ ] **Step 1: Install Ollama natively on Windows (if absent) and pull `bge-m3`**

Run:
```powershell
winget install --id Ollama.Ollama -e --accept-package-agreements --accept-source-agreements
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" pull bge-m3
& "$env:LOCALAPPDATA\Programs\Ollama\ollama.exe" list
```
Expected: `bge-m3:latest` appears in `ollama list` and `http://localhost:11434/api/tags` responds `200 OK`.

- [ ] **Step 2: Create Maven Wrapper (`3.9.9`) and `backend/pom.xml`**

Configure `spring-boot-starter-parent:3.4.5`, `java.version=21`, `project.build.sourceEncoding=UTF-8`, `maven-surefire-plugin` (`-Dfile.encoding=UTF-8`), `spring-ai-bom:1.0.3`, `software.amazon.awssdk:bom:2.30.26`, and dependencies specified in Spec §5.1. Bootstrap wrapper scripts (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).

- [ ] **Step 3: Write the failing context & Virtual Threads test in `DocuMindApplicationTests.java`**

```java
@SpringBootTest
@ActiveProfiles("test")
class DocuMindApplicationTests {
    @Autowired Environment environment;

    @Test
    void contextLoadsWithVirtualThreadsAndSafeSchemaSettings() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.initialize-schema", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.pgvector.dimensions", Integer.class)).isEqualTo(1024);
    }
}
```

- [ ] **Step 4: Run test to verify it fails before application classes exist**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=DocuMindApplicationTests`
Expected: FAIL (missing `DocuMindApplication` / configuration).

- [ ] **Step 5: Implement `DocuMindApplication`, `OpenApiConfig`, `application.yml`, `application-test.yml`, and `db/migration/.gitkeep`**

In `application.yml`: set `server.port=${SERVER_PORT:8081}`, `spring.threads.virtual.enabled=true`, `spring.config.import=optional:file:.env[.properties],optional:file:../.env[.properties]`, Postgres/Redis/Flyway (`ddl-auto=none`), PgVector (`initialize-schema=false`, `table-name=document_chunks`, `dimensions=1024`, `index-type=HNSW`, `distance-type=COSINE_DISTANCE`), Spring AI selectors (`model.embedding=ollama`, `model.chat=openai`, `image/audio/moderation=none`), and Actuator health exposure (`management.endpoints.web.exposure.include=health,info`, `management.endpoint.health.show-details=always`). In `application-test.yml`: disable live DataSource/JPA/Flyway/PgVector/Redis auto-configs or health probes for offline unit/context speed.

- [ ] **Step 6: Run test to verify offline context boots cleanly**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=DocuMindApplicationTests`
Expected: `BUILD SUCCESS`, 1 test passed.

- [ ] **Step 7: Commit backend skeleton & Maven Wrapper**

```powershell
git add backend/
git commit -m "feat(backend): bootstrap Spring Boot 3.4 Java 21 skeleton with Maven Wrapper (DA-5)"
```

---

### Task 3: MinIO S3 Client & Actuator Health Indicator

**Files:**
- Create: `backend/src/main/java/com/documind/infrastructure/storage/StorageProperties.java`
- Create: `backend/src/main/java/com/documind/infrastructure/storage/MinioConfig.java`
- Create: `backend/src/main/java/com/documind/infrastructure/storage/MinioHealthIndicator.java`
- Test: `backend/src/test/java/com/documind/infrastructure/storage/MinioHealthIndicatorTest.java`

**Interfaces:**
- Consumes: `documind.storage.{endpoint,access-key,secret-key,bucket,region}` bound in `application.yml` from `MINIO_*` env vars.
- Produces:
  - `public record StorageProperties(@NotBlank String endpoint, @NotBlank String accessKey, @NotBlank String secretKey, @NotBlank String bucket, String region)` (`@ConfigurationProperties(prefix = "documind.storage")`, `@Validated`).
  - `@Bean public S3Client s3Client(StorageProperties props)` and `@Bean public S3Presigner s3Presigner(StorageProperties props)` (`forcePathStyle(true)`).
  - `@Component("minio") public class MinioHealthIndicator implements HealthIndicator`.

- [ ] **Step 1: Write failing unit tests in `MinioHealthIndicatorTest.java`**

```java
class MinioHealthIndicatorTest {
    @Test
    void reportsUpWhenBucketExists() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(Consumer.class))).thenReturn(HeadBucketResponse.builder().build());
        var indicator = new MinioHealthIndicator(s3, new StorageProperties("http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1"));

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "documind-documents");
    }

    @Test
    void reportsDownWithBucketDetailWhenBucketMissingOrUnreachable() {
        S3Client s3 = mock(S3Client.class);
        when(s3.headBucket(any(Consumer.class))).thenThrow(NoSuchBucketException.builder().message("missing").build());
        var indicator = new MinioHealthIndicator(s3, new StorageProperties("http://localhost:9000", "ak", "sk", "documind-documents", "us-east-1"));

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("bucket", "documind-documents");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=MinioHealthIndicatorTest`
Expected: FAIL (classes do not exist).

- [ ] **Step 3: Implement `StorageProperties`, `MinioConfig`, and `MinioHealthIndicator`**

Wire `documind.storage` properties in `application.yml`. Build `S3Client` and `S3Presigner` with `URI.create(props.endpoint())`, `StaticCredentialsProvider`, `Region.of(Optional.ofNullable(props.region()).orElse("us-east-1"))`, and `forcePathStyle(true)`. Catch `SdkException` in `MinioHealthIndicator.health()` and return `Health.down(ex).withDetail("bucket", props.bucket()).build()`.

- [ ] **Step 4: Run tests to verify `MinioHealthIndicatorTest` and `DocuMindApplicationTests` pass**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit MinIO storage wiring & health indicator**

```powershell
git add backend/
git commit -m "feat(storage): add MinIO S3Client, S3Presigner, and health indicator (DA-5)"
```

---

### Task 4: Dual-Provider Spring AI Wiring (`Ollama bge-m3` + `Groq` Primary + `Gemini` Secondary)

**Files:**
- Create: `backend/src/main/java/com/documind/infrastructure/ai/AiProperties.java`
- Create: `backend/src/main/java/com/documind/infrastructure/ai/AiModelConfig.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/documind/infrastructure/ai/AiModelConfigTest.java`

**Interfaces:**
- Consumes: Spring AI auto-configured `OpenAiApi`, `OpenAiChatModel`, and `OllamaEmbeddingModel` (`EmbeddingModel`), plus `documind.ai.gemini.{base-url,completions-path,api-key,model,temperature}`.
- Produces:
  - `public record AiProperties(GeminiProperties gemini)` with `public record GeminiProperties(String baseUrl, String completionsPath, String apiKey, String model, Double temperature)`.
  - `@Bean @Primary public ChatClient groqChatClient(OpenAiChatModel openAiChatModel)`.
  - `@Bean("geminiChatClient") public ChatClient geminiChatClient(OpenAiApi baseOpenAiApi, OpenAiChatModel baseChatModel, AiProperties aiProperties)`.
  - Static helper `AiModelConfig.normalizeApiKey(String rawKey)` (returns `"unconfigured-api-key"` when null/blank) and `AiModelConfig.normalizeBaseUrl(String rawUrl)` / `normalizeCompletionsPath(String rawPath)`.

- [ ] **Step 1: Write failing unit tests in `AiModelConfigTest.java` (covering blank keys & Gemini URL normalization)**

```java
class AiModelConfigTest {
    @Test
    void normalizesBlankApiKeysAndGeminiPathsSafely() {
        assertThat(AiModelConfig.normalizeApiKey("")).isEqualTo("unconfigured-api-key");
        assertThat(AiModelConfig.normalizeApiKey("   ")).isEqualTo("unconfigured-api-key");
        assertThat(AiModelConfig.normalizeApiKey("AIza-real")).isEqualTo("AIza-real");
        assertThat(AiModelConfig.normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta/openai/"))
            .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
        assertThat(AiModelConfig.normalizeCompletionsPath("chat/completions"))
            .isEqualTo("/chat/completions");
    }

    @Test
    void createsDistinctPrimaryGroqAndSecondaryGeminiChatClientsEvenWhenGeminiKeyBlank() {
        OpenAiApi baseApi = OpenAiApi.builder().baseUrl("https://api.groq.com/openai").apiKey("gsk_test").build();
        OpenAiChatModel baseModel = OpenAiChatModel.builder()
            .openAiApi(baseApi)
            .defaultOptions(OpenAiChatOptions.builder().model("llama-3.3-70b-versatile").temperature(0.1).build())
            .build();
        var props = new AiProperties(new AiProperties.GeminiProperties(
            "https://generativelanguage.googleapis.com/v1beta/openai/", "chat/completions", "", "gemini-2.5-flash", 0.1));

        var config = new AiModelConfig();
        ChatClient groq = config.groqChatClient(baseModel);
        ChatClient gemini = config.geminiChatClient(baseApi, baseModel, props);

        assertThat(groq).isNotNull();
        assertThat(gemini).isNotNull().isNotSameAs(groq);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=AiModelConfigTest`
Expected: FAIL (missing `AiProperties` and `AiModelConfig`).

- [ ] **Step 3: Implement `AiProperties`, `AiModelConfig`, and update `application.yml`**

Ensure `spring.ai.openai.api-key=${GROQ_API_KEY:unconfigured-api-key}` (handling empty `.env` fallback via `AiModelConfig` / non-empty default) and `documind.ai.gemini` binds `GEMINI_BASE_URL`, `GEMINI_COMPLETIONS_PATH`, `GEMINI_API_KEY`, `GEMINI_CHAT_MODEL`. Construct `geminiChatClient` using `baseOpenAiApi.mutate().baseUrl(normalizeBaseUrl(...)).completionsPath(normalizeCompletionsPath(...)).apiKey(normalizeApiKey(...)).build()` and `baseChatModel.mutate()...build()`.

- [ ] **Step 4: Run full offline test suite**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: `BUILD SUCCESS`, all unit and context tests pass (`DocuMindApplicationTests` also injects `@Autowired ChatClient`, `@Qualifier("geminiChatClient") ChatClient`, and `@Autowired EmbeddingModel`).

- [ ] **Step 5: Commit Dual-Provider Spring AI configuration**

```powershell
git add backend/
git commit -m "feat(ai): configure Ollama bge-m3 EmbeddingModel and Groq/Gemini ChatClient beans (DA-5)"
```

---

### Task 5: End-to-End Live Smoke Verification (`Day1InfrastructureAndAiSmokeTest`)

**Files:**
- Create: `backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java`

**Interfaces:**
- Consumes: Live Docker containers (`5432`, `6380`, `9000`), Windows Ollama (`11434`), `GROQ_API_KEY` & `GEMINI_API_KEY` in `.env`, `EmbeddingModel`, `ChatClient` (`@Primary` + `@Qualifier("geminiChatClient")`), `JdbcTemplate`, `TestRestTemplate`.
- Produces: Automated proof for all 3 `DA-5` Acceptance Criteria.

- [ ] **Step 1: Write `Day1InfrastructureAndAiSmokeTest.java` gated by `@EnabledIfSystemProperty(named = "smoke", matches = "true")`**

Boot `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` against the real infrastructure profile (not `test`). Assert:
1. `GET /actuator/health` returns `200 OK` with `status == "UP"`, `components.db.status == "UP"`, `components.redis.status == "UP"`, `components.minio.status == "UP"`.
2. `GET /swagger-ui.html` (following redirects) returns `200 OK` containing `"Swagger UI"`.
3. `jdbcTemplate.queryForObject("SELECT to_regclass('public.vector_store')", String.class)` is `null` (proving `initialize-schema=false` preserved a clean DB for `DA-6`).
4. `embeddingModel.embed("Quy trình nghỉ phép năm của nhân viên chính thức tại doanh nghiệp")` returns `float[1024]` with finite values and L2 norm `> 0.1`.
5. Primary `groqChatClient` answers `"Trả lời đúng 1 câu tiếng Việt ngắn gọn: 2 + 2 bằng mấy?"` with non-blank text containing `"4"` or `"bốn"`.
6. Secondary `geminiChatClient` (when `GEMINI_API_KEY` is non-blank) answers the same prompt with non-blank text containing `"4"` or `"bốn"`.

- [ ] **Step 2: Pause for user to paste `GROQ_API_KEY` and `GEMINI_API_KEY` into `.env` (if not yet populated), then run live Smoke Test & live HTTP check on `:8081`**

Run:
```powershell
Set-Location backend
.\mvnw.cmd test -Dsmoke=true -Dtest=Day1InfrastructureAndAiSmokeTest
```
And verify standalone server boot on `:8081` (`/actuator/health` & `/swagger-ui.html`).
Expected: `BUILD SUCCESS`, all live assertions pass (`float[1024]`, Groq OK, Gemini OK, Health `UP`, Swagger `200 OK`).

- [ ] **Step 3: Commit Smoke Test & finalize Jira `DA-5`**

```powershell
git add backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java
git commit -m "test(smoke): add Day-1 live infra, Ollama bge-m3 1024d, and Groq/Gemini smoke suite (DA-5)"
```
Update Jira `DA-5` with verification evidence comment and transition status as appropriate.
