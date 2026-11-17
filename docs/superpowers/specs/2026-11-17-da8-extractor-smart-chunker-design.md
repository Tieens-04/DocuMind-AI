# DA-8 Design Spec — Day 4: Composite Document Extractor & Smart Token Chunker

- **Jira Issue:** `DA-8` (Parent Epic: `DA-1`, Sprint 1)
- **Date:** 2026-11-17
- **Prerequisites:** `DA-5` (Spring AI `1.0.3` BOM, `MinioConfig`), `DA-6` (Flyway `V1..V6`, `document_chunks` RBAC/metadata triggers, `uq_document_chunks_doc_version_index`), `DA-7` (`MinioStorageService.openStream`, `FileValidationService`, `DocumentUploadedEvent`)
- **Downstream Consumers:** `DA-9` (`DocumentIngestionWorker` on Virtual Threads with `@Retryable` batch embedding), `DA-10` (Core RAG citations & anti-hallucination retrieval), `DA-17` (Document re-indexing & PDF `#page=X` preview)

---

## 1. Goal & Acceptance Criteria

Build the multi-format document extraction engine (`CompositeDocumentExtractor`) and boundary-safe overlapping token chunker (`SmartChunkingService`) so Vietnamese `.pdf`, `.docx`, `.md`, and `.txt` files stored in MinIO are converted into Spring AI `Document` chunks with exact `page_number` (for PDFs) or hierarchical `section_heading` (for structured non-PDFs), deterministic idempotent chunk IDs, and 100% `V4`/`V5` PostgreSQL `document_chunks` trigger compliance.

### Acceptance Criteria
1. **Multi-Format Vietnamese Extraction (`CompositeDocumentExtractor`):** Successfully extracts precomposed `NFC` UTF-8 Vietnamese text with full diacritics across `.pdf`, `.docx`, `.md`, and `.txt` from `MinioStorageService.openStream(storageKey)`.
2. **Page-Preserving PDF Pipeline & Scanned-PDF Fail-Fast:** Extracts `.pdf` with exact `1`-based `page_number` via `PagePdfDocumentReader` (closing all PDFBox/file handles cleanly on Windows), rescuing layout-clipped or empty pages via per-page PDFBox `PDFTextStripper` and Apache Tika `PDFParser` (`NO_OCR`) `<div class="page">` fallback without merging pages. Skips blank separator pages without renumbering subsequent pages; fails fast with `DocumentExtractionException` on password-protected or `0`-text scanned image PDFs.
3. **Hierarchical `section_heading` & OOXML Anti-ZIP-Bomb Hardening:**
   - `.docx`: Enforces full-stream `ZipSecureFile` + Tika `OfficeParserConfig` inflation/entry limits (closing the `DA-7` deferral) and extracts hierarchical `<h1>..<h6>` breadcrumbs (`"Chương 1 > Điều 2"`) + pipe-formatted tables via a custom SAX `ContentHandler` (falling back to plain `TikaDocumentReader` + structural heading heuristics if no Word Heading styles exist).
   - `.md`: Splits ATX (`#`..`######`) and Setext (`===` / `---`) sections into hierarchical `section_heading` breadcrumbs while ignoring `#` inside fenced code blocks (`` ``` `` / `~~~`) and keeping tables/code intact.
   - `.txt`: Strips UTF-8 BOM (`\uFEFF`), splits standalone Vietnamese structural headings (`PHẦN`, `CHƯƠNG`, `MỤC`, `ĐIỀU`, numbered `I.`, `1.`, `1.1.`), and leaves `section_heading = null` on unstructured prose.
4. **Boundary-Safe `800 / 150` Token Chunking (`SmartChunkingService`):** Extends `TokenTextSplitter` (`jtokkit` `CL100K_BASE`) with configurable `chunkSize = 800` and `chunkOverlap = 150` tokens, snapping boundaries (`\n\n` -> `\n` -> sentence punctuation -> whitespace) with zero mid-word cuts and zero `U+FFFD` replacement characters in Vietnamese. Isolates chunks strictly per PDF page / per `section_heading`, prepends `"<section_heading>\n\n"` within the `800`-token budget when absent from a sub-chunk's start, assigns monotonic `0..K-1` `chunk_index`, generates deterministic `Document.id` UUIDs for idempotent `DA-9` retries, and stamps all mandatory `V4`/`V5` RBAC metadata keys.
5. **Unit + Testcontainers Contract Verification (Strict `DA-9` Seam):** Fast unit tests cover all 4 Vietnamese formats, edge cases, and overlap invariants; a MinIO + `pgvector/pgvector:pg16` Testcontainers integration test proves end-to-end compatibility with `V4`/`V5` DB triggers and idempotent double-`VectorStore.add(chunks)` upsert, without premature `@Async` worker wiring (`DA-9` scope).

---

## 2. Architectural Decisions

### 2.1 Hybrid Temp-File Spooling & Windows File-Lock Safety
- **Problem:** Bytecode inspection of Spring AI `1.0.3` `PagePdfDocumentReader` shows it loads `resource.getInputStream()` into an in-memory `RandomAccessReadBuffer` and **never closes** either `InputStream` or `protected final PDDocument document`. On a `16 GB RAM` host running `20` concurrent `50 MiB` Virtual Thread ingestions (`DA-9`), pure in-memory buffering risks `1 GB+` Heap spikes, S3 `ResponseInputStream` cannot rewind for multi-tier PDF fallback, and unclosed `FileInputStream` handles prevent `Files.deleteIfExists(tempFile)` on Windows (`FileSystemException`).
- **Decision:**
  - **Binary Formats (`.pdf`, `.docx`):** Spool `MinioStorageService.openStream(storageKey)` once to `Files.createTempFile("documind-extract-", "." + ext)` with a hard `maxSpoolBytes = 52_428_800L` (`50 MiB`) byte counter, closing the MinIO HTTP stream immediately after spooling and guaranteeing `Files.deleteIfExists(tempFile)` in `finally`.
  - **Windows Handle Closure (`CloseablePagePdfDocumentReader`):** Subclass `PagePdfDocumentReader` implementing `AutoCloseable`; wrap `FileSystemResource(tempFile)` in a tracking `Resource` that records and closes every opened `InputStream` alongside `this.document.close()` inside `close()`, ensuring zero locked temp files on Windows.
  - **Text Formats (`.md`, `.txt`):** Stream directly from `MinioStorageService.openStream(storageKey)` through a bounded UTF-8 reader (`<= 50 MiB` raw bytes, `<= 5,000,000` extracted characters) with zero disk spooling.

### 2.2 Three-Tier Page-Preserving PDF Extraction
- **Why Not Plain `TikaDocumentReader` Fallback:** `TikaDocumentReader` concatenates all PDF pages into a single `Document`, destroying `page_number` required for citations (`DA-10`) and `#page=X` inline PDF preview (`DA-17`).
- **Tiered Strategy (`PdfDocumentExtractor`):**
  1. **Pre-flight & Page Count (`Loader.loadPDF(File)` on PDFBox `3.0.3`):** Reject encrypted PDFs immediately (`ENCRYPTED_DOCUMENT`, catching `InvalidPasswordException` or `pdDocument.isEncrypted()` when unreadable) and `0`-page PDFs (`EMPTY_DOCUMENT`). Record `totalPages`.
  2. **Tier 1 — `CloseablePagePdfDocumentReader`:** Run with `PdfDocumentReaderConfig` (`pagesPerDocument = 1`, `pageTopMargin = 0`, `pageBottomMargin = 0`) to collect `Map<Integer, String>` keyed by `1`-based `page_number`.
  3. **Tier 2 — Per-Page `PDFTextStripper` Rescue:** If Tier 1 throws (e.g., `PDFLayoutTextStripperByArea` region crop failure) or any page `p` in `1..totalPages` yielded blank text in Tier 1, extract page `p` via `PDFTextStripper` (`setSortByPosition(true)`, `setStartPage(p)`, `setEndPage(p)`).
  4. **Tier 3 — Tika `PDFParser` SAX `<div class="page">` Rescue:** If zero non-blank pages were recovered after Tier 2, run Apache Tika `PDFParser` (`PDFParserConfig`: `setOcrStrategy(NO_OCR)` so Tika never shells out to external Tesseract, `setExtractAcroFormContent(true)`, `setExtractAnnotationText(true)`, `setSortByPosition(true)`) with a custom SAX handler splitting `<div class="page">` elements into `1`-based pages `1..N`.
  5. **Blank Separator vs. Scanned Image Rule:** Emit `ExtractedSegment(nfcText, pageNumber, null)` for every non-blank page in ascending `pageNumber` order (skipping blank separator pages without shifting subsequent `pageNumber`s). If all pages are blank across all 3 tiers, throw `DocumentExtractionException(NO_EXTRACTABLE_TEXT, ...)`.

### 2.3 Structural `section_heading` Breadcrumbs & OOXML ZIP-Bomb Defense
- **`.docx` (`DocxDocumentExtractor`):**
  - Enforces `ZipSecureFile` static floors (`minInflateRatio = 0.01d`, `maxEntrySize = 100 MiB`, `maxFileCount = 1000`, `maxTextSize = 10 MiB`) plus per-call `OfficeParserConfig` in Tika `ParseContext` and a hard `maxExtractedChars = 5_000_000` ceiling inside the SAX handler (`EXTRACTION_LIMIT_EXCEEDED` on breach).
  - Parses `TikaInputStream.get(tempFile)` via `AutoDetectParser` + `HeadingAwareXhtmlContentHandler`:
    - Intercepts `<h1>`..`<h6>` emitted from Word `Heading 1`..`Heading 6` styles, maintaining a 6-slot breadcrumb stack joined with `" > "` (e.g., `"Chương 2: Phúc lợi > Điều 5: Nghỉ phép năm"`). Entering `<hN>` clears slots `N..6` and flushes any accumulated body text under the prior heading.
    - Formats `<table>`/`<tr>`/`<td>`/`<th>` rows as pipe-delimited lines (`| Cột 1 | Cột 2 |`) so tabular policies remain readable to `bge-m3` and LLMs.
    - Fallback: If the `.docx` contains zero `<h1>..<h6>` tags, falls back to `TikaDocumentReader` (`BodyContentHandler(-1)`) normalized output passed through `PlainTextSectionExtractor` structural heading detection so unstyled Word SOPs still recover `CHƯƠNG`/`ĐIỀU` headings (or `section_heading = null` if flat prose).
- **`.md` (`MarkdownSectionExtractor`):**
  - Line-by-line state machine tracking fenced code delimiters (3+ `` ` `` or `~` opening/closing matching fence) so `# comments` inside Bash/Python/YAML code blocks are never misparsed as headings.
  - Supports ATX headings (`#`..`######`) and Setext underlines (`===` -> H1, `---` -> H2 when preceded by a non-blank paragraph line and not a Markdown table separator `| --- |`), maintaining the `" > "` breadcrumb stack (`section_heading = null` for any preamble text before the first heading).
- **`.txt` (`PlainTextSectionExtractor`):**
  - Strips leading UTF-8 BOM (`\uFEFF`), normalizes `\r\n`/`\r` to `\n`, and detects standalone structural heading lines (`<= 140` chars, not ending in terminal sentence punctuation `.`/`?`/`!`) matching Vietnamese enterprise conventions:
    - Level 1: `^(?i)(phần|chương)\s+[IVXLCDM\d]+.*`
    - Level 2: `^(?i)(mục|điều)\s+\d+.*` or Roman numeral `^[IVX]+\.\s+\S+.*`
    - Level 3: Decimal clause `^\d+(\.\d+){0,2}\.?\s+\p{Lu}.*` (when preceded by a blank line or start-of-file)
  - Plain `.txt` files with zero matching structural headings emit a single `ExtractedSegment` with `section_heading = null`.
- **Universal Text Normalization:** All extractors normalize output via `Normalizer.normalize(text, Normalizer.Form.NFC)` (unifying decomposed `NFD` Vietnamese accents from macOS/PDF exports into precomposed `NFC`), strip `\u0000` and soft hyphens `\u00AD`, trim trailing line spaces, and collapse `3+` consecutive newlines to `\n\n`.

### 2.4 `OverlappingTokenTextSplitter` (`800 / 150`) & Contextual Header Prefix
- **Problem:** Spring AI `1.0.3` `TokenTextSplitter` uses `jtokkit` `CL100K_BASE` (`chunkSize = 800`) with **zero overlap support** (`overlap = 0`), drops short segments (`<= 5` chars), and raw BPE byte-token slicing can split multi-token UTF-8 Vietnamese characters (`ệ`, `ữ`, `ắ`) into `U+FFFD` (`\uFFFD`).
- **Algorithm (`OverlappingTokenTextSplitter extends TokenTextSplitter`):**
  1. Encode segment body with `CL100K_BASE`. If `tokens.size() <= effectiveChunkSize`, emit as a single chunk.
  2. For sliding window `[startToken, min(startToken + effectiveChunkSize, totalTokens))`:
     - Trim any leading/trailing broken UTF-8 BPE byte-tokens whose `encoding.decode(...)` starts or ends with `\uFFFD`.
     - If not at `totalTokens`, scan backward within the upper `35%` of the candidate character window (above `65%` length floor) for the highest-priority natural break: `\n\n` -> `\n` -> sentence end (`. `, `? `, `! `, `… `, `; `) -> whitespace (` `, `\t`).
     - Compute `consumedTokens = encode(snappedChunk).size()`. If `startToken + consumedTokens >= totalTokens`, emit final chunk and stop.
     - Compute `rawNextStart = startToken + Math.max(1, consumedTokens - effectiveOverlap)`, then snap `nextStart` forward (bounded strictly below `startToken + consumedTokens`) to the nearest clean paragraph/line/sentence/word boundary free of `\uFFFD`.
     - **Tail Deduplication:** If `totalTokens - nextStart <= effectiveOverlap` and all remaining tokens were already included in the emitted chunk, stop instead of emitting a redundant duplicate tail fragment.
- **Strict Isolation + Contextual Header Prefix (`SmartChunkingService`):**
  - Never merges text across two `ExtractedSegment`s (preserving 100% single-page / single-section provenance).
  - When `segment.sectionHeading()` is non-blank and a chunk's text does not already start with `sectionHeading`: prepends `"<headerPrefix>\n\n"` (`headerPrefix` capped at `chunkSize / 4` tokens on word boundary if abnormally long, while full `sectionHeading` stays in metadata), reducing `effectiveChunkSize = chunkSize - headerPrefixTokens` so final `Document.text` never exceeds `800` `CL100K_BASE` tokens.

### 2.5 Idempotent Chunk IDs & `V4`/`V5` Metadata Parity (`DA-8` -> `DA-9` Contract)
- **Deterministic UUID (`ChunkIdGenerator`):** `PgVectorStore.add(batch)` upserts on `id` (`ON CONFLICT (id) DO UPDATE`), while `V4` enforces `UNIQUE (document_id, doc_version, chunk_index)`. Generating `UUID.nameUUIDFromBytes(("documind:chunk:" + documentId + ":" + docVersion + ":" + chunkIndex).getBytes(UTF_8))` ensures any `@Retryable` batch retry in `DA-9` updates existing chunk rows cleanly instead of throwing `DataIntegrityViolationException`.
- **Mandatory Metadata Map:**
  - `document_id`: `context.documentId().toString()`
  - `department_id`: `context.departmentId().toString()`
  - `file_name`: `context.originalName()` (**exact** match to `documents.original_name` enforced by `V5` `sync_document_chunk_metadata()`)
  - `access_level`: `context.accessLevel().name()` (`PUBLIC` | `DEPARTMENT` | `CONFIDENTIAL`)
  - `doc_version`: `context.docVersion()` (`int >= 1`)
  - `chunk_index`: document-global `0..K-1` (`int`)
  - `page_number`: `Integer >= 1` for PDF; omitted when `null` on non-PDFs (normalized to `NULL` column + `'null'::jsonb` by `V4`/`V5` trigger)
  - `section_heading`: `String` when non-blank on `.docx`/`.md`/`.txt`; omitted when `null`

---

## 3. Package Structure & Component Interfaces

```text
backend/src/main/java/com/documind/
├── exception/
│   ├── DocumentExtractionErrorCode.java
│   └── DocumentExtractionException.java
└── service/
    └── ingestion/
        ├── ChunkIdGenerator.java
        ├── ChunkMetadataKeys.java
        ├── CompositeDocumentExtractor.java
        ├── DocxDocumentExtractor.java
        ├── DocumentIngestionContext.java
        ├── ExtractedSegment.java
        ├── IngestionProperties.java
        ├── MarkdownSectionExtractor.java
        ├── OverlappingTokenTextSplitter.java
        ├── PdfDocumentExtractor.java
        ├── PlainTextSectionExtractor.java
        └── SmartChunkingService.java
```

### Core Public Signatures
- `DocumentIngestionContext(UUID documentId, UUID departmentId, String storageKey, String originalName, String fileExtension, AccessLevel accessLevel, int docVersion)` with `static DocumentIngestionContext from(DocumentUploadedEvent event)`
- `ExtractedSegment(String content, Integer pageNumber, String sectionHeading)`
- `CompositeDocumentExtractor`:
  - `List<ExtractedSegment> extract(DocumentIngestionContext context)` (opens stream from `MinioStorageService`)
  - `List<ExtractedSegment> extractFromStream(String originalName, String fileExtension, InputStream rawStream)` (direct unit-test & helper entry point)
- `SmartChunkingService`:
  - `List<Document> chunk(DocumentIngestionContext context, List<ExtractedSegment> segments)`
- `DocumentExtractionException`:
  - `DocumentExtractionErrorCode errorCode()` (`EMPTY_DOCUMENT`, `NO_EXTRACTABLE_TEXT`, `ENCRYPTED_DOCUMENT`, `CORRUPT_DOCUMENT`, `EXTRACTION_LIMIT_EXCEEDED`, `UNSUPPORTED_FORMAT`)

---

## 4. Configuration Additions (`application.yml`)

```yaml
documind:
  ingestion:
    extraction:
      max-spool-bytes: 52428800
      max-extracted-chars: 5000000
      zip-min-inflate-ratio: 0.01
      zip-max-entry-size-bytes: 104857600
      zip-max-file-count: 1000
    chunking:
      chunk-size: 800
      chunk-overlap: 150
      max-chunks-per-document: 10000
```

Zero new Maven dependencies are required in `backend/pom.xml` (`spring-ai-pdf-document-reader:1.0.3`, `spring-ai-tika-document-reader:1.0.3`, `pdfbox:3.0.3`, `tika-core`/`tika-parsers-standard-package:3.1.0`, `poi-ooxml:5.4.0`, and `jtokkit:1.1.0` are already in `compile` scope).

---

## 5. Error Mapping Contract (For Downstream `DA-9` `documents.error_message`)

| Failure Condition | `DocumentExtractionErrorCode` | Behavior |
| :--- | :--- | :--- |
| `0`-byte stream, `0`-page PDF, or whitespace-only `.docx`/`.md`/`.txt` | `EMPTY_DOCUMENT` | Throws `DocumentExtractionException` with human-readable message |
| Image-only / scanned PDF with zero extractable text after all 3 tiers | `NO_EXTRACTABLE_TEXT` | Throws `DocumentExtractionException` (`NO_OCR` deterministic fail-fast) |
| Password-protected / encrypted PDF or OOXML package | `ENCRYPTED_DOCUMENT` | Throws `DocumentExtractionException` immediately without retry |
| Truncated/corrupt PDF xref or malformed OOXML XML | `CORRUPT_DOCUMENT` | Throws `DocumentExtractionException` |
| Stream `> 50 MiB`, OOXML ZIP-bomb (`ZipSecureFile` ratio/count/size), `> 5M` chars, or `> 10,000` chunks | `EXTRACTION_LIMIT_EXCEEDED` | Aborts immediately & cleans up temp file |
| Unknown extension outside `pdf`, `docx`, `md`, `txt` | `UNSUPPORTED_FORMAT` | Throws `DocumentExtractionException` |
| MinIO S3 unreachable / network drop | (`DocumentStorageException`) | Propagates existing `DocumentStorageException` so `DA-9` can distinguish transient storage failures |

---

## 6. Testing & Verification Strategy

1. **Unit Tests (Zero Containers, Portable Across Windows & Linux CI):**
   - `IngestionContractsAndChunkIdTest`: validates `IngestionProperties` defaults/bounds, `DocumentIngestionContext.from(DocumentUploadedEvent)`, and `ChunkIdGenerator` determinism + collision isolation across `(documentId, docVersion, chunkIndex)`.
   - `MarkdownAndPlainTextExtractorTest`: tests nested ATX/Setext Vietnamese headings, `#` inside ```` ```python ```` / `~~~bash` fences, Markdown tables, UTF-8 BOM `\uFEFF`, `NFD -> NFC` Vietnamese normalization, `CHƯƠNG`/`ĐIỀU`/`1.1` `.txt` headings vs `null` on plain prose, whitespace-only rejection (`EMPTY_DOCUMENT`), and `maxExtractedChars` enforcement (`EXTRACTION_LIMIT_EXCEEDED`).
   - `CompositeDocumentExtractorTest`:
     - Generates multi-page Vietnamese Unicode PDFs on-the-fly using PDFBox `3.0.3` + bundled `/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf`: verifies exact `page_number` (`1, 3` when page `2` is blank), Tier 2/Tier 3 rescue when Tier 1 returns empty/throws, scanned image-only PDF rejection (`NO_EXTRACTABLE_TEXT`), encrypted PDF rejection (`ENCRYPTED_DOCUMENT`), and verifies **zero residual `documind-extract-*` temp files** on Windows even on exception.
     - Generates real OOXML `.docx` fixtures with `Heading1`/`Heading2` styles + tables, unstyled `.docx` fallback, and high-compression OOXML ZIP-bomb fixture rejected with `EXTRACTION_LIMIT_EXCEEDED`.
   - `SmartChunkingServiceTest`: verifies `800 / 150` `CL100K_BASE` token ceiling & overlap continuity on long Vietnamese policy text, zero `\uFFFD` characters, linguistic boundary snapping, single-page/single-section isolation, smart `<section_heading>\n\n` prefix injection (without duplicating when already present, staying `<= 800` tokens), monotonic `0..K-1` `chunk_index`, and full metadata stamping.
2. **Testcontainers Integration Suite (`ExtractorAndChunkerMinioPgVectorIntegrationTest`):**
   - Uses `PostgresTestcontainersConfig` + `MinioTestcontainersConfig`.
   - Uploads all 4 Vietnamese formats (`.pdf`, `.docx`, `.md`, `.txt`) into real MinIO (`documind-documents`), persists parent `DocumentEntity` rows, runs `CompositeDocumentExtractor.extract(ctx)` -> `SmartChunkingService.chunk(ctx, segments)` -> `vectorStore.add(chunks)`.
   - Asserts `V4`/`V5` PostgreSQL triggers synchronize `document_chunks.document_id`, `doc_version`, `chunk_index`, `page_number` (`1, 3` for PDF; `NULL` for `.docx`/`.md`/`.txt`), and JSONB `section_heading`.
   - Calls `vectorStore.add(chunks)` a **second time** on the exact same chunk list to prove deterministic UUID upsert idempotency (`0` duplicate errors, unchanged row count).
   - Executes RBAC-filtered `vectorStore.similaritySearch(...)` verifying citations carry `file_name`, `page_number`, and `section_heading`.
