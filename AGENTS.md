# DocuMind AI — Project Rules & Engineering Standards

## 1. Architecture & Stack Baseline
- **Backend:** Java 21 (LTS), Spring Boot 3.3+, Spring AI 1.0+ GA, Spring Security 6 (Stateless JWT: Access + Refresh Token), Apache Tika, Bucket4j, OpenAPI 3 / Swagger UI.
- **Data & Cache:** PostgreSQL 16 with `pgvector` (HNSW index), Redis 7 (Exact/Semantic Cache + Rate Limiting).
- **Frontend:** Next.js (App Router, TypeScript) + Tailwind CSS (SSE streaming client + Markdown & Citation cards).
- **Architecture Style:** Clean / Layered Architecture (`controller` -> `service` -> `domain` / `repository` -> `infrastructure`), stateless application tier ready for Docker Compose and horizontal scaling.

## 2. Workspace & Build Rules (Windows Host)
- **Maven Wrapper Required:** Global `mvn` is not installed on the host. Always commit and execute the Maven Wrapper (`.\mvnw.cmd` in PowerShell, `./mvnw` in Docker/GitHub Actions).
- **Java 21 Features:** Enable `spring.threads.virtual.enabled=true`. Use Java `record` for DTOs, API requests/responses, and domain events. Use Virtual Threads for async document ingestion and blocking I/O.
- **Spring AI Version Discipline:** Spring AI changed package/API shapes across pre-1.0 milestones. Always verify current Spring AI 1.0+ GA imports (`ChatClient`, `PgVectorStore`, `TikaDocumentReader`, `TokenTextSplitter`, `Filter.Expression`) via Context7 MCP or current dependencies in `pom.xml` — never guess `0.8.x` classes (`AiClient`, `AiResponse`, etc.).
- **Playwright E2E:** Always run Playwright in headed mode (`--headed` / `headless: false`) so browser execution is visible on screen.
- **Local Host Port Allocation (FlintFlow Coexistence):** Never stop or collide with `flintflow-*` containers (`3000`, `3001`, `5000`, `6379`, `8080`, `27017/27018`). Always parameterize via `.env` with these canonical host defaults:
  - PostgreSQL 16 (`pgvector`): `5432` (`POSTGRES_PORT`)
  - Redis 7 (`alpine`): `6380` -> container `6379` (`REDIS_PORT`)
  - MinIO S3 API / Console: `9000` / `9001` (`MINIO_API_PORT` / `MINIO_CONSOLE_PORT`, bucket `documind-documents`)
  - Spring Boot Backend: `8081` -> container `8080` (`SERVER_PORT`)
  - Next.js Frontend: `3002` -> container `3000` (`FRONTEND_PORT`)
  - Ollama (Windows Native): `11434` (`OLLAMA_BASE_URL=http://localhost:11434`)

## 3. Non-Negotiable Security & RAG Invariants
1. **Mandatory Vector RBAC Filtering (Zero Cross-Department Leakage):**
   - Every chunk stored in `pgvector` MUST carry non-null metadata: `document_id`, `file_name`, `department_id`, `access_level` (`PUBLIC`, `DEPARTMENT`, `CONFIDENTIAL`), `doc_version`, and `chunk_index`, plus `page_number` (`>= 1`) for paginated `.pdf` documents or `section_heading` when present (omit `null` metadata keys per `V7` for `PgVectorStore` compatibility).
   - Every `VectorStore.similaritySearch(...)` call MUST attach a server-enforced `Filter.Expression` derived strictly from `SecurityContextHolder` (never from unvalidated request parameters):
     - `STANDARD_EMPLOYEE` (`VIEWER`): `access_level == 'PUBLIC' || (department_id == <user.departmentId> && access_level == 'DEPARTMENT')` (never `CONFIDENTIAL`)
     - `DEPARTMENT_MANAGER` (`EDITOR`): manage documents only within `<user.departmentId>`; query `access_level == 'PUBLIC' || department_id == <user.departmentId>` (includes `DEPARTMENT` and `CONFIDENTIAL` within own department)
     - `SYSTEM_ADMIN`: full governance/audit access and authorized administrative scope.
2. **Deterministic Anti-Hallucination Fallback & Citations:**
   - Enforce a configurable `similarityThreshold` on vector retrieval.
   - When zero chunks pass the threshold, do NOT let the LLM speculate — return `"Không tìm thấy thông tin trong tài liệu nội bộ"` immediately.
   - Every grounded answer MUST return structured source citations (`file_name`, `page_number` or `section_heading`, `similarity_score`, `excerpt`) alongside the streamed SSE response.
3. **Async Document Ingestion Lifecycle:**
   - Validate file extension (`.pdf`, `.docx`, `.txt`, `.md`) and size (`<= 50MB`) at upload time.
   - Persist document state transitions atomically: `PENDING` -> `PROCESSING` -> `COMPLETED` / `FAILED` (with failure reason recorded).
4. **Caching, Rate Limiting & Cost Governance:**
   - Check Redis cache before invoking the LLM (`< 50ms` Redis lookup, `< 100ms` cached API response) and ensure cached entries never bypass caller RBAC scope (scope cache keys by `department_id` / visibility!).
   - Enforce per-user / per-role rate limits via Redis + Bucket4j.
   - Record `prompt_tokens`, `completion_tokens`, latency, cache hit/miss, `user_id`, and `department_id` in Audit Logs for every query.

## 4. Quality & KPI Gates
- **Unit & Integration Coverage:** Maintain `>= 75%` test coverage across core flows (`Ingestion`, `Vector RBAC Filter`, `JWT Security`, `Cache & Rate Limit`) using **Testcontainers** (`pgvector/pgvector:pg16` and `redis:7-alpine`) — never mock away SQL/pgvector metadata filter execution.
- **RAG KPIs (Golden Dataset):** Retrieval Precision@5 `>= 85%`, Out-of-Scope Hallucination Rate `= 0%`, Repeated-Query Cache Hit Ratio `>= 30%-40%`.
