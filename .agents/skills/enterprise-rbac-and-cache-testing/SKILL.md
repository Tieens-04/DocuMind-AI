---
name: enterprise-rbac-and-cache-testing
description: Use when writing or reviewing integration tests, security tests, cache tests, rate-limiting tests, or test coverage configuration for RBAC vector search, JWT authentication, Redis caching, or Bucket4j in DocuMind AI.
---

# Enterprise RBAC, Cache & Rate-Limit Integration Testing

## Overview
Enforces real-container integration testing (`pgvector/pgvector:pg16` + `redis:7-alpine`) and deterministic LLM/Embedding test doubles so SQL metadata filters, cross-department cache isolation, SSE streaming, and Bucket4j Lua scripts are verified with `>= 75%` coverage.

**REQUIRED BACKGROUND:** Follow `superpowers:test-driven-development` (write failing test before production code).

## Container & Test Double Contract

1. **Real Infrastructure Only (Singleton Testcontainers):**
   - PostgreSQL + `pgvector`: `PostgreSQLContainer<>("pgvector/pgvector:pg16")` with `@ServiceConnection`.
   - Redis 7: `GenericContainer<>("redis:7-alpine")` with `@ServiceConnection(name = "redis")`.
   - **Prohibited:** Mocking `VectorStore`, `JdbcTemplate`, `StringRedisTemplate`, or Bucket4j `ProxyManager`. Mocking hides SQL `<=>` + JSONB `WHERE` filter bugs and Redis serialization issues.
2. **Deterministic AI Stubs (Never Unstubbed `@MockBean EmbeddingModel`):**
   - Register a `@TestConfiguration` `@Primary` deterministic `EmbeddingModel` returning fixed-dimension normalized `float[]` matching the `vector(N)` column (an unstubbed mock returns `null`/`float[0]` and crashes `pgvector`).
   - Stub/spy `ChatModel` so token streaming is fast (`< 10ms`) and call counts (`verify(chatModel, times(1))`) are exact.
3. **SSE Endpoint Client:**
   - Test `text/event-stream` (`Flux<ServerSentEvent<?>>`) with `WebTestClient` (or `MockMvc` `asyncDispatch`), asserting `citations`, `token`, and `done` events in order.

## Mandatory RBAC Truth Matrix (`@ParameterizedTest`)

Seed 4 documents (`hr-public`, `hr-dept`, `hr-confidential`, `fin-dept`) and assert **both** Retrieval visibility AND Management (`POST/PUT/DELETE`) permissions:

| Caller Role | Caller Dept | `hr-public` | `hr-dept` | `hr-confidential` | `fin-dept` | Upload/Delete in `HR` | Upload/Delete in `FIN` |
|---|---|---|---|---|---|---|---|
| `SYSTEM_ADMIN` | `IT` | Visible | Visible | Visible | Visible | `200/202` | `200/202` |
| `DEPARTMENT_MANAGER` | `HR` | Visible | Visible | Visible | **Hidden** | `200/202` | **`403`** |
| `STANDARD_EMPLOYEE` | `HR` | Visible | Visible | **Hidden** | **Hidden** | **`403`** | **`403`** |
| `STANDARD_EMPLOYEE` | `FIN` | **Visible** | **Hidden** | **Hidden** | Visible | **`403`** | **`403`** |

## Mandatory Cache & Rate-Limit Scenarios

1. **Latency & Zero-LLM Cache Hit:**
   - Same authorized user asks identical/semantically equivalent question twice -> 2nd call completes in `< 50ms` (Redis lookup) / `< 100ms` HTTP end-to-end, `ChatModel` invoked **once** (`times(1)`), and `AuditLog` records `cacheHit = true` with `0` new LLM tokens.
2. **Cross-Department & Clearance Cache Poisoning Prevention (CRITICAL):**
   - Cache keys MUST bind `(departmentId, allowedAccessLevels)` — never `question` alone or `departmentId` alone.
   - `HR` Manager asks `"Chính sách lương thưởng Q3?"` (populates Redis cache from `hr-dept` or `hr-confidential`).
   - `FIN` Employee (and `HR` `STANDARD_EMPLOYEE` when source is `hr-confidential`) asks the **exact same string** `"Chính sách lương thưởng Q3?"`.
   - **Assertion:** Must be a **Cache MISS** for the lower/other RBAC scope and return `"Không tìm thấy thông tin trong tài liệu nội bộ"` — never the cached manager answer!
3. **Bucket4j Per-User & Per-Role Quota:**
   - Exhaust `STANDARD_EMPLOYEE` quota -> next request returns `429 Too Many Requests` with `Retry-After` header, while another user or `SYSTEM_ADMIN` with higher tier still receives `200 OK`.

## Common Mistakes

| Mistake | Fix |
|---|---|
| Marking `PUBLIC` docs invisible to other departments | `PUBLIC` docs (`hr-public`) MUST be visible to `FIN` viewers too; only `DEPARTMENT` and `CONFIDENTIAL` restrict by `department_id`. |
| Testing cache hit only with the same user | Leaves `hash(question)` cache-key vulnerability undetected; always assert cross-department identical query isolation. |
| Using synchronous `MockMvc` without `asyncDispatch` on `Flux` | Assertions run before reactive stream completes; use `WebTestClient.bindToServer()` / `MockMvcWebTestClient`. |
| Skipping JaCoCo check | Configure `jacoco-maven-plugin` `check` goal enforcing `>= 0.75` line/instruction coverage on core packages. |
