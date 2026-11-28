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
- **Post-RBAC Bucket4j rate limiting & bounded async FinOps audit**: Enforce Bucket4j + Lettuce quotas (`15/30/60` chat/min, `5/10` upload/min) after RBAC/session pre-checks with ABA-safe connection recovery + `AS_IS` demotion clamping, and bound fire-and-forget `429`/failure writes via non-blocking `Semaphore` + single-SQL `JdbcClient` insert on `documind-audit-vt-`.
- **FlintFlow port coexistence**: Keep canonical host ports in `.env.example` / `docker-compose.yml` (`5432`, `6380`, `9000/9001`, `8081`, `3002`, `11434`) and never collide with `flintflow-*` containers.
- **Frontend RAM-only access token & single-flight refresh**: Keep `accessToken` in memory and `refreshToken` in `localStorage`; serialize 401 rotation across tabs via `navigator.locks` + macrotask yield + `sessionEpoch`, clearing session only on `401/403` (`frontend/src/lib/auth/token-manager.ts`).
- **Frontend typed SSE streaming, XSS-safe GFM & live-verified Citation preview**: Stream `/api/v1/chat/stream` via `fetch` + `ReadableStream` (`TextDecoder({ stream: true })`, single-flight `401` retry, `reader.cancel()` in `finally`), strip ASCII control chars/backslashes before `ReactMarkdown` `urlTransform`, and gate `CitationDrawer` links behind live `/preview-url` RBAC verification keyed by citation identity (`frontend/src/lib/api/chat-stream.ts`, `frontend/src/components/chat/citation-drawer.tsx`).
- **Full-stack Docker hardening & headed E2E**: Keep `frontend/Dockerfile` fail-closed (`ARG NEXT_PUBLIC_DEMO_ACCOUNTS=false`), build backend via `./mvnw` with `MaxRAMPercentage=55.0` + `stop_grace_period: 35s`, and scope Playwright `--headed` assertions to `main ol.max-w-4xl > li` / `table tbody` (`backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`, `frontend/e2e/milestone3-fullstack.spec.ts`).

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
- **Bucket4j Rate Limiting, Async Audit Worker & Admin FinOps Analytics**: `backend/src/main/java/com/documind/infrastructure/ratelimit/RateLimitService.java`, `backend/src/main/java/com/documind/service/audit/AuditLogService.java`, `backend/src/main/java/com/documind/service/admin/AdminAnalyticsService.java`, `backend/src/test/java/com/documind/integration/RateLimitIntegrationTest.java`, `backend/src/test/java/com/documind/integration/AdminAnalyticsIntegrationTest.java`
- **Next.js Auth, Single-Flight Refresh & Role Guard**: `frontend/src/lib/auth/token-manager.ts`, `frontend/src/lib/auth/permissions.ts`, `frontend/src/providers/auth-provider.tsx`, `frontend/src/__tests__/api-client.test.ts`
- **Next.js Chat Workspace, Typed SSE Client & Citation Side Drawer**: `frontend/src/lib/api/chat-stream.ts`, `frontend/src/app/(workspace)/chat/page.tsx`, `frontend/src/components/chat/citation-drawer.tsx`, `frontend/src/components/chat/markdown-content.tsx`, `frontend/src/__tests__/chat-workspace.test.tsx`
- **Next.js Documents Upload Queue, Conditional Polling & RBAC Table**: `frontend/src/lib/api/documents.ts`, `frontend/src/app/(workspace)/documents/page.tsx`, `frontend/src/components/documents/document-upload-zone.tsx`, `frontend/src/__tests__/helpers/mock-xhr.ts`, `frontend/src/__tests__/documents-workspace.test.tsx`
- **Next.js Admin FinOps Dashboard & Users/Departments 2-Tab Governance**: `frontend/src/lib/api/admin.ts`, `frontend/src/app/(workspace)/admin/analytics/page.tsx`, `frontend/src/app/(workspace)/admin/users/page.tsx`, `frontend/src/__tests__/admin-rbac-three-roles.test.tsx`
- **Full-Stack Docker Packaging & Headed Playwright E2E**: `backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml`, `backend/src/test/java/com/documind/infrastructure/config/DockerComposeHardeningTest.java`, `frontend/playwright.config.ts`, `frontend/e2e/milestone3-fullstack.spec.ts`

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
- **TestContext cache cap on low-RAM hosts**: Bound `spring.test.context.cache.maxSize=4` (`backend/src/test/resources/spring.properties`) and keep singleton Testcontainers alive across `@DirtiesContext` so full `verify` suites stay within 8 GiB / no-swap container limits.
- **Caller-owned `AbortSignal` check over generic `AbortError` matching**: Check `controller.signal.aborted` in frontend `catch` blocks instead of `error.name === 'AbortError'` so internal `refreshAccessToken()` 10s timeout aborts surface as errors rather than hanging UI loading states (`frontend/src/app/(workspace)/chat/page.tsx`).
