# DA-5 Design Spec — Day 1: Monorepo, Docker Infrastructure (16GB RAM) & Hybrid \$0 AI Foundation

- **Jira Issue:** `DA-5` (Parent Epic: `DA-1`, Sprint 1)
- **Date:** 2026-11-15
- **Target Host:** Windows 11 (~15.7 GB physical RAM, CPU-only, Corretto JDK 21.0.12, Docker 29.6 / Compose v5.3)

---

## 1. Goal & Acceptance Criteria

Establish the complete Day-1 monorepo structure, resource-bounded Docker infrastructure, local Ollama `bge-m3` (`1024`-dim) embedding runtime, dual \$0 cloud Chat LLM wiring (Groq primary + Google Gemini OpenAI-compatible secondary), and Spring Boot 3.4 / Java 21 Virtual Threads backend foundation without colliding with active `flintflow-*` workloads on the host.

### Acceptance Criteria
1. `docker compose up -d` starts all 4 infrastructure containers cleanly (`postgres`, `redis`, `minio` healthy; `minio-init` exits `0`) and bucket `documind-documents` automatically exists on MinIO with anonymous public access disabled.
2. Backend starts on Java 21 Virtual Threads at `http://localhost:8081` with `/swagger-ui.html` returning HTTP `200` and `/actuator/health` returning `{"status":"UP"}` across PostgreSQL, Redis, and MinIO.
3. Automated Smoke Test embeds a Vietnamese sentence via Ollama `bge-m3` into a valid `float[1024]` vector and receives non-empty Vietnamese answers from the primary Groq `ChatClient` (and secondary Gemini `ChatClient` when `GEMINI_API_KEY` is present).
4. Host port mapping avoids all `flintflow-*` ports (`3000`, `3001`, `5000`, `6379`, `8080`, `27017`, `27018`), and updated ports are reflected in `AGENTS.md` and affected Jira tickets (`DA-5`, `DA-21`, `DA-25`).

---

## 2. Standard Port Allocation (FlintFlow Coexistence)

Because `flintflow-*` containers actively occupy `3000`, `3001`, `5000`, `6379`, `8080`, and `27017/27018`, DocuMind AI standardizes on the following host ports (all parameterized via `.env`):

| Service | Host Port | Container Port | Env Variable | Notes |
| :--- | :---: | :---: | :--- | :--- |
| PostgreSQL 16 (`pgvector`) | `5432` | `5432` | `POSTGRES_PORT` | Vector store (`1024d` HNSW) + relational schema |
| Redis 7 (`alpine`) | `6380` | `6379` | `REDIS_PORT` | Avoids `flintflow-redis-1` (`6379`) |
| MinIO S3 API | `9000` | `9000` | `MINIO_API_PORT` | S3-compatible object storage endpoint |
| MinIO Web Console | `9001` | `9001` | `MINIO_CONSOLE_PORT` | Admin UI (`http://localhost:9001`) |
| Spring Boot Backend | `8081` | `8080` | `SERVER_PORT` | Avoids `flintflow-plantuml-1` (`8080`) & `be` (`5000`) |
| Next.js Frontend (`DA-21`) | `3002` | `3000` | `FRONTEND_PORT` | Avoids `fe` (`3000`) & `gotenberg` (`3001`) |
| Ollama (Windows Native) | `11434` | N/A | `OLLAMA_BASE_URL` | Runs natively on Windows host (`http://localhost:11434`) |

---

## 3. Monorepo Layout

```text
DocuMind-AI/
├── .agents/                          # Project AI engineering skills
├── backend/                          # Spring Boot 3.4 + Java 21 + Spring AI 1.0.3 GA
│   ├── .mvn/wrapper/                 # Committed Maven Wrapper metadata
│   ├── mvnw                          # Linux/macOS/Docker wrapper script
│   ├── mvnw.cmd                      # Windows PowerShell wrapper script
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/documind/
│       │   │   ├── DocuMindApplication.java
│       │   │   ├── controller/
│       │   │   ├── service/
│       │   │   ├── domain/
│       │   │   ├── repository/
│       │   │   └── infrastructure/
│       │   │       ├── ai/           # AiProperties, AiModelConfig (Ollama + Groq + Gemini)
│       │   │       ├── storage/      # StorageProperties, MinioConfig, MinioHealthIndicator
│       │   │       └── config/       # OpenApiConfig
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/     # Ready for DA-6 V1..V3 SQL migrations
│       └── test/
│           ├── java/com/documind/
│           │   ├── DocuMindApplicationTests.java
│           │   └── infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java
│           └── resources/
│               └── application-test.yml
├── docs/superpowers/specs/           # Validated design specifications
├── eval/                             # Golden Dataset & RAG KPI scripts (DA-27)
├── frontend/                         # Next.js 14+ App Router workspace (DA-21)
├── load-tests/                       # k6 load testing scripts (DA-29)
├── .env.example                      # Committed template (no secrets)
├── .gitignore                        # Ignores .env, target/, .next/, node_modules/, IDE files
├── AGENTS.md                         # Project rules + canonical port table
├── docker-compose.yml                # 4-service 16GB-RAM-tuned infra stack
└── product_overview_enterprise_ai_knowledge_assistant.md
```

---

## 4. Docker Infrastructure Design (`docker-compose.yml`)

Optimized for a 16GB RAM Windows host sharing memory with WSL2, Ollama CPU (`bge-m3` ~1.2GB), and FlintFlow:

1. **`postgres` (`pgvector/pgvector:pg16`):**
   - Port `${POSTGRES_PORT:-5432}:5432`, volume `documind_pgdata:/var/lib/postgresql/data`.
   - Memory cap `mem_limit: 1g` with tuned flags: `-c shared_buffers=256MB -c work_mem=16MB -c maintenance_work_mem=128MB -c max_connections=100`.
   - Healthcheck: `pg_isready -U ${POSTGRES_USER:-documind} -d ${POSTGRES_DB:-documind}`.
2. **`redis` (`redis:7-alpine`):**
   - Port `${REDIS_PORT:-6380}:6379`, volume `documind_redisdata:/data`.
   - Memory cap `mem_limit: 256m`, command `redis-server --appendonly yes --maxmemory 192mb --maxmemory-policy allkeys-lru`.
   - Healthcheck: `redis-cli ping`.
3. **`minio` (`minio/minio:RELEASE.2025-02-28T09-55-16Z`):**
   - Ports `${MINIO_API_PORT:-9000}:9000` and `${MINIO_CONSOLE_PORT:-9001}:9001`, volume `documind_miniodata:/data`.
   - Memory cap `mem_limit: 512m`, command `server /data --console-address ":9001"`.
   - Healthcheck: `mc ready local`.
4. **`minio-init` (`minio/mc:RELEASE.2025-02-21T16-00-46Z`):**
   - `depends_on: minio: condition: service_healthy`, `restart: "no"`.
   - Entrypoint script configures alias `local`, runs `mc mb --ignore-existing local/${MINIO_BUCKET:-documind-documents}`, enforces `mc anonymous set none local/${MINIO_BUCKET:-documind-documents}`, and exits `0`.

---

## 5. Backend & Dual-Provider AI Architecture (`backend/`)

### 5.1 Build & Runtime Baseline
- **Parent:** `org.springframework.boot:spring-boot-starter-parent:3.4.5` (Java `21`).
- **BOMs:** `org.springframework.ai:spring-ai-bom:1.0.3` and `software.amazon.awssdk:bom:2.30.26`.
- **Core Starters:**
  - `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `spring-boot-starter-data-redis`
  - `spring-ai-starter-model-openai`, `spring-ai-starter-model-ollama`, `spring-ai-starter-vector-store-pgvector`, `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`
  - `flyway-core`, `flyway-database-postgresql`, `postgresql`
  - `software.amazon.awssdk:s3`
  - `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5`
- **Virtual Threads & `.env` Loading:**
  - `spring.threads.virtual.enabled=true`
  - `spring.config.import=optional:file:.env[.properties],optional:file:../.env[.properties]` so running Maven from either repo root or `backend/` loads `.env` seamlessly without manual shell variable exports.

### 5.2 Multi-Model Spring AI 1.0.3 Wiring (`AiModelConfig`)
- **Auto-Configuration Disambiguation (`application.yml`):**
  - `spring.ai.model.embedding=ollama` -> binds single `EmbeddingModel` (`OllamaEmbeddingModel`, `base-url=${OLLAMA_BASE_URL:http://localhost:11434}`, `model=${OLLAMA_EMBEDDING_MODEL:bge-m3}`).
  - `spring.ai.model.chat=openai` -> binds `OpenAiChatModel` to primary Groq endpoint (`base-url=${GROQ_BASE_URL:https://api.groq.com/openai}`, `api-key=${GROQ_API_KEY:placeholder-key}`, `chat.options.model=${GROQ_CHAT_MODEL:llama-3.3-70b-versatile}`, `temperature=0.1`).
  - Disable unused model auto-configs: `spring.ai.model.image=none`, `spring.ai.model.audio.speech=none`, `spring.ai.model.audio.transcription=none`, `spring.ai.model.moderation=none`.
- **Explicit `ChatClient` Beans (`AiModelConfig`):**
  - `@Bean @Primary ChatClient groqChatClient(OpenAiChatModel openAiChatModel)`: primary low-latency chat client backed by Groq.
  - `@Bean("geminiChatClient") ChatClient geminiChatClient(OpenAiApi baseOpenAiApi, OpenAiChatModel baseChatModel, AiProperties props)`: derived via `baseOpenAiApi.mutate().baseUrl(props.gemini().baseUrl()).completionsPath(props.gemini().completionsPath()).apiKey(props.gemini().apiKey()).build()` where `baseUrl = https://generativelanguage.googleapis.com/v1beta/openai` and `completionsPath = /chat/completions` (crucial because Gemini's OpenAI-compatible path omits `/v1`), using model `${GEMINI_CHAT_MODEL:gemini-2.5-flash}`.

### 5.3 Flyway & PgVector Alignment with `DA-6`
- `spring.jpa.hibernate.ddl-auto=none`
- `spring.flyway.enabled=true` (reads `classpath:db/migration`)
- `spring.ai.vectorstore.pgvector.initialize-schema=false`
- `spring.ai.vectorstore.pgvector.table-name=document_chunks`
- `spring.ai.vectorstore.pgvector.dimensions=1024`
- `spring.ai.vectorstore.pgvector.index-type=HNSW`
- `spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE`

Keeping `initialize-schema=false` and `ddl-auto=none` guarantees `DA-5` does not pollute PostgreSQL with a `1536`-dim `vector_store` table, allowing `DA-6` migrations `V1..V3` to execute cleanly on Day 2.

### 5.4 MinIO S3 & Actuator Readiness
- `MinioConfig` creates `S3Client` and `S3Presigner` beans configured with `endpointOverride`, `Region.US_EAST_1`, `StaticCredentialsProvider`, and `forcePathStyle(true)`.
- `MinioHealthIndicator` executes `s3Client.headBucket(b -> b.bucket(storageProperties.bucket()))` and reports `Health.up().withDetail("bucket", bucket)` (or `Health.down(ex)` if unreachable or missing), ensuring `/actuator/health` verifies all 3 backing stores (`db`, `redis`, `minio`).

---

## 6. Error Handling & Testing Strategy

1. **Offline CI / Context Test (`DocuMindApplicationTests`):**
   - Uses `application-test.yml` (disabling live DB/Redis/MinIO health probes or using test exclusions for pure context/unit verification in Day 1; full Testcontainers suite arrives in `DA-11`) so `.\mvnw.cmd test` runs fast and deterministically without requiring external API keys.
   - Unit tests verify `AiModelConfig` bean wiring (`groqChatClient`, `geminiChatClient`, `EmbeddingModel`) and `MinioHealthIndicator` `UP`/`DOWN` behavior.
2. **Live End-to-End Day-1 Smoke Test (`Day1InfrastructureAndAiSmokeTest`):**
   - Enabled via `-Dsmoke=true` against live Docker (`postgres:5432`, `redis:6380`, `minio:9000`), native Windows Ollama (`11434`), Groq API, and Gemini API:
     - Asserts `embeddingModel.embed("Quy trình nghỉ phép năm của nhân viên chính thức")` returns `float[1024]` with non-zero norm.
     - Asserts `groqChatClient` and `geminiChatClient` return non-blank Vietnamese responses.
     - Asserts `/actuator/health` HTTP `200` (`status == "UP"`, `components.db.status == "UP"`, `components.redis.status == "UP"`, `components.minio.status == "UP"`) and `/swagger-ui.html` HTTP `200`.

---

## 7. Documentation & Jira Synchronization Scope

- **`AGENTS.md`:** Add a dedicated Local Host Port Mapping subsection documenting `5432`, `6380`, `9000/9001`, `8081`, `3002`, and `11434`.
- **Jira Updates:**
  - `DA-5`: Document the conflict-free port mapping (`Redis 6380:6379`, `Backend 8081` -> `http://localhost:8081/swagger-ui.html`, `http://localhost:8081/actuator/health`) and dual Groq + Gemini setup.
  - `DA-21` & `DA-25`: Note Frontend host port `3002` and Backend host port `8081` so Week 3 Frontend & Full-Stack Docker tasks stay consistent.
