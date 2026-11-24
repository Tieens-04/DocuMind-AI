# DocuMind AI — Persistent Team Memory (`pstack` + Grok Bot Edition)

> Shared, rot-resistant engineering memory consulted at **Step 1 (Plan)**, **Step 2 (Implement)**, and **Step 6 (Deliver)**.
>
> **Write Policy (Lauren Tan's 4-Step Hierarchy)**:
>
> 1. Fix setup/tooling first.
> 2. Encode deterministic checks in `.ai-workflow/scripts/verify.cjs`, Maven/JaCoCo, or Testcontainers integration tests.
> 3. Encode domain review checklists in `.ai-workflow/rules/domain-review.md`.
> 4. Add to `MEMORY.md` **last** — max 2 lines per item, written as a **general principle + canonical source**, never an incident story.
>
> **Fetch, Never Remember**: Live Jira AC/comments (`jira-cwb`), Flyway `V*.sql` migrations, `AGENTS.md`, `backend/pom.xml`, and `verify.cjs diff` are always fetched fresh; never rely on memory for mutable schema or ticket state.

---

## 1. Decisions (Settled Architecture & Conventions)

- **Dual-layer Vector RBAC & HNSW recall**: Build `Filter.Expression` strictly from `SecurityContextHolder` via `RbacVectorFilterFactory`, re-verify in `VectorRetrievalService` + `GroundedSystemPromptService`, and keep `SET hnsw.iterative_scan = 'relaxed_order'` on vector queries.
- **Zero-null `PgVectorStore` metadata (`V7`)**: Spring AI `1.0.3` `Document.Builder` rejects `null` values in `metadata`; omit `page_number` completely for `.docx`/`.md`/`.txt` instead of storing `null`, and store integer `>= 1` for `.pdf`.
- **Deterministic refusal & zero-leak SSE**: When `similaritySearch` returns 0 hits, short-circuit in Java with `RagConstants.SAFE_REFUSAL_MESSAGE` (`0` Answer LLM tokens); never hold HikariCP DB connections open while streaming SSE on Virtual Threads (`documind-sse-vt-`).
- **Stream token accounting (`streamUsage: true`)**: Stream via `chatClient.prompt()...stream().chatResponse()` (never `.stream().content()`) with `CL100K_BASE` fallback so `audit_logs` always records accurate `prompt_tokens` and `completion_tokens`.
- **Immediate JWT revocation & fail-closed auth**: Keep `documind.security.dev-caller-fallback.enabled=false` in `application.yml`, enforce Redis blacklist revocation (`0s` cutoff on role/department change), and fail closed (`503`) if Redis auth state store is unavailable.
- **Two-tier RBAC-scoped RAG cache & monotonic invalidation**: Pin L1 (`documind:rag:l1:{v1|ctx:v1}:`) and L2 (`semantic_cache`, cosine `>= 0.94`) to `(scope.cacheScopeKey(), authoritativeDbVersion)` with `MGET` TOCTOU reconciliation, live citation/preview re-verification, sync L1 SSE replay (`< 50ms`), and post-TX single-Lua Redis sync (carrying `retiredVersion + 1` on empty department deletion so `ADMIN:GLOBAL` never regresses).
- **FlintFlow port coexistence**: Keep canonical host ports in `.env.example` / `docker-compose.yml` (`5432`, `6380`, `9000/9001`, `8081`, `3002`, `11434`) and never collide with `flintflow-*` containers.

---

## 2. Sources (Canonical Repo Examples for `plan.md` Startup Checklist)

- **Auth, Stateless JWT & Redis Revocation**: `backend/src/main/java/com/documind/infrastructure/security/JwtAuthenticationFilter.java`, `backend/src/main/java/com/documind/service/auth/AuthService.java`, `backend/src/main/java/com/documind/service/auth/RedisTokenBlacklistService.java`
- **Document RBAC, Versioned Re-index & MinIO Presign**: `backend/src/main/java/com/documind/service/document/DocumentService.java`, `backend/src/main/java/com/documind/service/document/DocumentVisibilityPolicy.java`, `backend/src/main/java/com/documind/infrastructure/storage/MinioStorageService.java`
- **Async Ingestion, Extraction & Smart Chunking (`512/100`)**: `backend/src/main/java/com/documind/service/ingestion/DocumentIngestionWorker.java`, `backend/src/main/java/com/documind/service/ingestion/SmartChunkingService.java`, `backend/src/main/java/com/documind/service/ingestion/ChunkIdGenerator.java`
- **Vector RBAC, Grounded Prompt, Multi-turn & Typed SSE**: `backend/src/main/java/com/documind/service/rag/RbacVectorFilterFactory.java`, `backend/src/main/java/com/documind/service/rag/VectorRetrievalService.java`, `backend/src/main/java/com/documind/service/rag/RagChatStreamService.java`, `backend/src/main/java/com/documind/service/chat/ChatSessionService.java`
- **RFC 9110 Error Contract**: `backend/src/main/java/com/documind/controller/advice/GlobalExceptionHandler.java`
- **Singleton Testcontainers & Real-JWT Integration Suites**: `backend/src/test/java/com/documind/support/AbstractIntegrationTest.java`, `backend/src/test/java/com/documind/controller/VectorRbacRetrievalIntegrationTest.java`, `backend/src/test/java/com/documind/controller/TypedSseChatStreamingIntegrationTest.java`
- **Milestone 2 Cross-Gate RBAC & Concurrent SSE Suites**: `backend/src/test/java/com/documind/support/Week2SseTestClient.java`, `backend/src/test/java/com/documind/integration/Week2CrossGateRbacIntegrationTest.java`, `backend/src/test/java/com/documind/integration/Week2SseConcurrencyAndResilienceIntegrationTest.java`
- **RBAC-Scoped Two-Tier RAG Cache & Turn-1 CTE Persistence**: `backend/src/main/java/com/documind/service/rag/RagCacheService.java`, `backend/src/main/java/com/documind/infrastructure/vector/SemanticCacheVectorRepository.java`, `backend/src/main/java/com/documind/repository/ChatTurnPersistenceRepository.java`, `backend/src/test/java/com/documind/integration/ScopedRagCacheIntegrationTest.java`

---

## 3. Prompts & Role Delegation Patterns (4-Role Specialist Team)

- **`Researcher` (`/how` + `/why` via `research` subagent in Step 1)**: Delegate multi-file exploration to a read-only `research` subagent that traces execution (`/how`), inspects `git log -n 5` / `git log -S` on security or concurrency guards (`/why`), and returns `file:line` citations + 1–2 canonical examples in ≤ 30 lines.
- **`Engineer` Specialist Split (Large Fullstack Tickets in Step 2)**: When `plan.md` spans complex backend Spring AI / Flyway / Redis logic AND multi-component Next.js / Playwright UI changes, split `Backend Specialist` (`backend/**`) from `Frontend Specialist` (`frontend/**`).
- **`Reviewer` (`FAILED / SAW / FIX` + `Comment-Sicko` in Step 3)**: Dispatch 3 independent `research` subagents (`Standards & Domain`, `Spec & Test`, `Ponytail & Comment-Sicko`) over `node .ai-workflow/scripts/verify.cjs diff` + `git diff <BASE_SHA>` + untracked `??` files; strip AI comment narration, dead code, and speculative abstractions.

---

## 4. Lessons (Generalizable Guardrails)

- **Merge-base + uncommitted + untracked diff**: Steps 2 and 4 do not commit; always collect review scope via `node .ai-workflow/scripts/verify.cjs diff` + `git diff <BASE_SHA>` + direct reads of `??` untracked files (three-dot `main...HEAD` misses uncommitted work).
- **Windows Maven Wrapper & file locks**: Global `mvn` is absent on Windows; always run `.\mvnw.cmd` inside `backend/` (or `node .ai-workflow/scripts/verify.cjs check --verify`) and close all `InputStream`/`PDDocument`/temp files in `try-with-resources`/`finally`.
- **Real Testcontainers only**: Never mock `VectorStore`, `JdbcTemplate`, `StringRedisTemplate`, `S3Client`, or Bucket4j `ProxyManager` in integration tests; reuse singleton `AbstractIntegrationTest` containers and keep JaCoCo `>= 75%`.
- **Headed Playwright E2E**: Always run Playwright E2E with `--headed` (`headless: false`) so browser execution stays visible on screen.
- **Jira lifecycle on `jira-cwb`**: Project `DA` lives on `nguyentien191004.atlassian.net` via MCP `jira-cwb` (`To Do` -> `In Progress` in Step 1, `Verify / Test` in Step 3/5, `Done` in Step 6 only when `SAFE TO COMMIT`).
- **HTTP/1.1 SSE Test Harness & Leak-Free Cleanup**: Force `HTTP_1_1`, wrap `HttpResponse<InputStream>` in `try-with-resources` before assertions, and close `HttpClient`/`ExecutorService` with `shutdownNow()` + sink completion in `finally` (`Week2SseTestClient.java`) so early failures never hang Surefire.
