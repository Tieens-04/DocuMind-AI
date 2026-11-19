---
name: pr
description: Use when drafting a Pull Request description with concise architecture/flow visuals, concrete verification evidence, and merge risk assessment.
---

# Pull Request Description Generator (`DocuMind AI`)

Drafts a high-signal, scannable Pull Request body grounded in `AGENTS.md` domain terminology and real execution evidence. Skip preambles; keep prose tight.

## Template

```markdown
## Summary

<1–3 sentences stating intent + smallest diagram, call-tree, file-tree, or diff sketch that makes the change clear>

## Acceptance Criteria & Invariants Verified

- [x] **AC 1**: ...
- [x] **RBAC / RAG Safety**: <e.g., `Filter.Expression` derived from `SecurityContextHolder`, `V7` null-free chunk metadata, Redis cache scoped by `(departmentId, accessLevel)`>

## Verification Evidence

- **Backend (`.\mvnw.cmd`)**: `<exact command>` -> `<N tests passed, 0 failures, Testcontainers pgvector/redis/minio>`
- **Frontend / E2E** *(if applicable)*: `<exact command>` -> `<result>`
- **Before -> After**:
  - **Before**: <failing test / missing capability / bug symptom>
  - **After**: <passing test / verified runtime behavior>

## Merge Danger

**Door:** `<Two-way | One-way>`
<1–2 sentences: e.g., whether Flyway migration is additive/backward-compatible or irreversible, config defaults safe>

**Blast Radius:** `<Isolated | Moderate | Cross-cutting>`
<1–2 sentences naming affected modules: e.g., `service.ingestion` only, or `document_chunks` trigger + `VectorStore`>
```

## Choosing the Right Visual in `Summary`

Pick **one** compact visual matching the change type (do not stack all of them):

- **Pipeline / Async / SSE control flow** -> compact call-tree or Mermaid `sequenceDiagram`:
  ```text
  uploadDocument (HTTP 202)
    validateExtensionAndSize (<= 50MB)
    streamToMinio + saveDocument(PENDING)
    publish DocumentUploadedEvent -> VirtualThread worker
      extract (NFC) -> chunk (512/100) -> PgVectorStore.add -> COMPLETED
  ```
- **Module / package additions** -> shallow annotated file tree:
  ```text
  backend/src/main/java/com/documind/service/ingestion/
  ├── CompositeDocumentExtractor.java   # PDF (3-tier page-safe), DOCX (SAX+ZIP guard), MD, TXT
  ├── OverlappingTokenTextSplitter.java # 512/100 CL100K_BASE boundary-safe splitter
  └── SmartChunkingService.java         # Contextual heading prefix + deterministic UUIDs
  ```
- **Targeted bugfix / rule change** -> minimal `diff` snippet highlighting the behavioral delta.

## Rules

- Never fabricate test counts, coverage numbers, or benchmark latencies — copy exact numbers from verified runs (`final-review.md` / terminal output).
- Flag any new Flyway migration (`V<N>__*.sql`) or `.env.example` key explicitly under **Merge Danger**.
