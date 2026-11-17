---
name: ponytail-review
description: Use when reviewing code changes like the on-call senior engineer who will be paged in production — checking correctness, security/RBAC isolation, Virtual Thread & DB concurrency under load, resource cleanup, test authenticity, performance, and YAGNI lean code.
---

# Ponytail Review — Production On-Call & Anti-Bloat Review

Review a change like the senior engineer who will be paged at 2 AM when it breaks.
Order of importance: **correct -> safe -> holds under load -> tested -> fast -> lean**.
Lean matters: every unnecessary line, abstraction, or branch must be read, tested, and maintained later.

## 1. Understand the Connected Flow First

1. Inspect the full change: tracked diffs (`git diff <merge-base>`) **and** newly created untracked files, not just committed hunks.
2. Read the surrounding callers and callees of every modified class/method/component, plus Flyway SQL constraints/triggers (`backend/src/main/resources/db/migration/`) and `application*.yml` when persistence or config is touched.
3. Trace data end-to-end: HTTP/SSE request & `SecurityContextHolder` -> Service / Virtual Thread -> PostgreSQL (`pgvector` + JPA) / MinIO / Redis -> response & `AuditLog`.
4. Judge against DocuMind AI's expected concurrency (`AGENTS.md` / Product Overview: `100` concurrent RAG chat streams + `20` concurrent `<= 50MB` document uploads on `16GB` host / bounded container memory).

## 2. What to Hunt For

1. **Bug**: Off-by-one chunk/page index, broken UTF-8/NFC Vietnamese slicing (`\uFFFD`), null metadata crashing `PgVectorStore`, swallowed exceptions, stale React/Next.js async state on rapid session switching.
2. **Security & Isolation Risk**: Cross-department or `CONFIDENTIAL` vector leakage, Redis cache key missing `(departmentId, allowedAccessLevels)` or `scope_version`, trusting request params over `SecurityContextHolder`, ZIP-bomb / unbounded memory buffering on `50MB` uploads, secrets or raw tokens in logs.
3. **Concurrency & Resource Leaks Under Load**: Unclosed `InputStream` / `PDDocument` / temp files locking Windows disk, `MultipartFile` read after HTTP `202` return, TOCTOU races between parent `documents` status/ACL updates and `document_chunks` writes, blocking calls starving reactive pipelines or unbounded thread spawning.
4. **Fake or Missing Tests**: Mocking `VectorStore`, `JdbcTemplate`, `StringRedisTemplate`, or Bucket4j instead of running real Testcontainers; asserting happy path only while skipping `403`, `429`, timeout, or rollback paths.
5. **Bloat (YAGNI)**: Dead helpers, unused DTO fields/props, speculative interfaces with a single implementation, duplicated validation/filter code.

## 3. Rules of Engagement

- Only report findings backed by concrete `file:line` evidence in the workspace.
- Propose the **smallest safe fix** that solves the root cause. Prefer fixes that delete code over fixes that add layers.
- No style nitpicks, no vague "consider...", no hypothetical worries already prevented by PostgreSQL constraints or compiler types.

## 4. Output Structure

Start with `What this change does:` in 2–3 plain sentences.

Group numbered findings across three buckets (skip empty buckets):
- **Must fix**: Correctness bug, RBAC/cache security leak, data loss/corruption, Windows file lock / OOM / race condition under expected load.
- **Should fix**: Risky logic without real integration test, noticeable latency/token waste, duplicated logic, dead/over-engineered code that should be removed.
- **Nice to have**: Small simplifications or line reductions.

For each finding, state concisely:
- **Where**: `path/to/File.java:L40-52`
- **Problem**: Concrete failure scenario.
- **Fix**: Minimal root-cause fix.
- **If skipped**: Real operational or security impact.

End with:
- `Verdict: Ship.` or `Verdict: Fix #1, #2 first.`
- `Lean: ~<N> lines removable.` (when bloat exists)
- Lists findings only; **modifies zero files**.
