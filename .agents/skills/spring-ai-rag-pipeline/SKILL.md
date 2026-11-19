---
name: spring-ai-rag-pipeline
description: Use when implementing or modifying document upload, parsing, chunking, pgvector storage, similarity search, SSE streaming chat, citations, or low-similarity refusal in Java 21 and Spring Boot with Spring AI.
---

# Spring AI 1.0+ Enterprise RAG Pipeline

## Overview
Standardizes end-to-end RAG ingestion and retrieval on Java 21, Spring Boot 3.3+, Spring AI 1.0+ GA, and PostgreSQL `pgvector` so citations preserve page numbers, async uploads never lose temp files, RBAC filters never drop `PUBLIC` docs, and streaming preserves token usage metrics.

## Dependency & Reader Routing Contract

Use **Spring AI 1.0+ GA** starter coordinates (never legacy `0.8.x` `*-spring-boot-starter` names):

| Capability | Maven Artifact (`org.springframework.ai`) | Notes |
|---|---|---|
| BOM | `spring-ai-bom` (`1.0.0`+) | Import in `<dependencyManagement>` |
| PgVector Store | `spring-ai-starter-vector-store-pgvector` | Configure HNSW + cosine distance |
| PDF Reader (page-aware) | `spring-ai-pdf-document-reader` | Use `PagePdfDocumentReader` for `.pdf` |
| DOCX / TXT / MD Reader | `spring-ai-tika-document-reader` | Use `TikaDocumentReader` for non-PDFs |

**Reader routing rule:** Never pass multi-page `.pdf` files through `TikaDocumentReader` (it merges pages and loses `page_number`). Route `.pdf` -> page-preserving PDF extraction (`PagePdfDocumentReader` / PDFBox per-page with `page_number >= 1`), and `.docx` / `.txt` / `.md` -> structured section extractors (`DocxDocumentExtractor`, `MarkdownSectionExtractor`, `PlainTextSectionExtractor` preserving `section_heading`).

## Async Ingestion Contract (Virtual Threads)

1. **Validate & stage on HTTP thread:** Check extension (`.pdf`, `.docx`, `.txt`, `.md`), magic signature (`64 KiB` header), and size (`<= 50MB`). Stream upload bytes directly to MinIO (`documind-documents`) with single-pass `SHA-256` and persist `DocumentEntity(status = PENDING)` **before** returning `202 Accepted` (Servlet deletes `MultipartFile` temp storage when the request finishes).
2. **Process on Virtual Thread executor:** Transition `PENDING -> PROCESSING -> COMPLETED` (or `FAILED` with error message).
3. **Sentence-safe overlapping chunking (`512 / 100`) & null-free metadata (`V7`):** Split on paragraph/sentence boundaries (`OverlappingTokenTextSplitter` — never raw `String.substring` that slices UTF-8 Vietnamese words) and normalize text + filenames to Unicode `NFC`. Every chunk MUST include non-null metadata (never put `null` values in `Document.metadata`):
   - `document_id` (UUID string)
   - `file_name` (original filename, `NFC`)
   - `department_id` (owner department ID)
   - `access_level` (`PUBLIC` | `DEPARTMENT` | `CONFIDENTIAL`)
   - `doc_version` (integer >= 1) and `chunk_index` (integer >= 0)
   - `page_number` (integer >= 1 on `.pdf`; **omitted** on `.docx`/`.md`/`.txt`) and `section_heading` (when present)

## Retrieval, RBAC Filter & SSE Streaming Contract

```java
// 1. Build RBAC Filter.Expression strictly from SecurityContext (never request params)
var b = new FilterExpressionBuilder();
Filter.Expression rbacFilter = switch (principal.role()) {
    case SYSTEM_ADMIN -> b.in("access_level", "PUBLIC", "DEPARTMENT", "CONFIDENTIAL").build();
    case DEPARTMENT_MANAGER -> b.or(
        b.eq("access_level", "PUBLIC"),
        b.eq("department_id", principal.departmentId())
    ).build();
    case STANDARD_EMPLOYEE -> b.or(
        b.eq("access_level", "PUBLIC"),
        b.and(b.eq("department_id", principal.departmentId()), b.eq("access_level", "DEPARTMENT"))
    ).build();
};

// 2. Execute thresholded vector search on boundedElastic / virtual thread
SearchRequest request = SearchRequest.builder()
    .query(question)
    .topK(props.topK()) // default 5
    .similarityThreshold(props.similarityThreshold()) // default 0.58 (calibrated on bge-m3)
    .filterExpression(rbacFilter)
    .build();

List<Document> hits = vectorStore.similaritySearch(request);
if (hits.isEmpty()) {
    // Deterministic refusal: 0 LLM tokens spent, 0% hallucination
    auditService.recordRefusal(principal, question);
    return Flux.just(
        ServerSentEvent.builder().event("token").data("Không tìm thấy thông tin trong tài liệu nội bộ").build(),
        ServerSentEvent.builder().event("done").data(Map.of("abstained", true)).build()
    );
}
```

**SSE Event Protocol & Token Accounting:**
1. Emit `event: citations` (or final `citations` payload) with `fileName`, `pageNumber`, `similarityScore` (`doc.getScore()`), and `excerpt`.
2. Stream via `chatClient.prompt()...stream().chatResponse()` (NOT `.stream().content()`), accumulating `ChatResponse.getMetadata().getUsage()` on completion so `promptTokens` and `completionTokens` are persisted to `AuditLog`.
3. Emit `event: done` with `sessionId` and token usage summary.

## Common Mistakes

| Mistake | Fix |
|---|---|
| Using `.eq("department_id", dept)` alone | Excludes company-wide `PUBLIC` documents; always `OR` with `access_level == 'PUBLIC'`. |
| Reading `MultipartFile.getResource()` inside `@Async` | Copy bytes/temp file before returning HTTP `202`, or `NoSuchFileException` occurs. |
| Using `.stream().content()` | Loses `Usage` metadata (`promptTokens`/`completionTokens`) required by Audit Log. |
| Letting LLM generate the refusal message | Waste of tokens and risks hallucination; short-circuit in Java when `hits.isEmpty()`. |
