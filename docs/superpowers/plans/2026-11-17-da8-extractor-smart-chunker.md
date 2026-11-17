# DA-8 Composite Document Extractor & Smart Token Chunker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver multi-format Vietnamese document extraction (`CompositeDocumentExtractor` for `.pdf`, `.docx`, `.md`, `.txt`) preserving PDF `page_number` and hierarchical `section_heading`, paired with boundary-safe `800 / 150` `CL100K_BASE` token chunking (`SmartChunkingService`) verified against real MinIO and PostgreSQL `pgvector` (`V4`/`V5` triggers) Testcontainers.

**Architecture:** `CompositeDocumentExtractor` streams `.md` and `.txt` directly in UTF-8 while spooling binary `.pdf` and `.docx` from `MinioStorageService.openStream(storageKey)` to bounded `<= 50 MiB` temporary files deleted deterministically in `finally` (closing all `PagePdfDocumentReader` streams and `PDDocument` handles for Windows lock safety, running a 3-tier page-preserving PDF fallback with `NO_OCR`, and enforcing `ZipSecureFile` + SAX `<h1>..<h6>` breadcrumbs on `.docx`). `SmartChunkingService` chunks each page/section in isolation via `OverlappingTokenTextSplitter extends TokenTextSplitter` (`jtokkit` `CL100K_BASE`, `chunkSize = 800`, `chunkOverlap = 150`, zero `\uFFFD` UTF-8 cuts), injects smart `<section_heading>\n\n` context within the `800`-token ceiling, assigns deterministic UUIDs (`ChunkIdGenerator`) for idempotent `DA-9` `@Retryable` upserts, and stamps all mandatory `V4`/`V5` RBAC metadata keys.

**Tech Stack:** Java 21 (Records, Virtual-Thread-safe stateless services), Spring Boot `3.4.5`, Spring AI `1.0.3` (`PagePdfDocumentReader`, `TikaDocumentReader`, `TokenTextSplitter`, `PgVectorStore`), Apache PDFBox `3.0.3`, Apache Tika `3.1.0`, Apache POI `5.4.0` (`ZipSecureFile`), `jtokkit` `1.1.0` (`CL100K_BASE`), Testcontainers (`pgvector/pgvector:pg16`, MinIO), Maven Wrapper (`.\mvnw.cmd`).

**Spec:** `docs/superpowers/specs/2026-11-17-da8-extractor-smart-chunker-design.md`

## Global Constraints

- **Host Coexistence (Never Touch FlintFlow):** Never stop or collide with `flintflow-*` containers; live Postgres runs on `localhost:5432`, Redis on `6380`, MinIO on `9000`/`9001` (`documind-documents`), Backend on `8081`.
- **Maven Wrapper Only:** Always execute `.\mvnw.cmd` inside `backend/` in PowerShell (global `mvn` is not installed).
- **Zero New Maven Dependencies & Zero Flyway DDL Changes:** Reuse existing `backend/pom.xml` `compile` dependencies and existing `V1..V6` schema/triggers without adding migrations.
- **Exact `V4`/`V5` Chunk Metadata Invariants:** Every `Document` emitted by `SmartChunkingService` must carry `document_id`, `department_id`, `file_name` (exact `documents.original_name`, never `sanitizedFileName`), `access_level` (`PUBLIC` | `DEPARTMENT` | `CONFIDENTIAL`), `doc_version` (`>= 1`), and document-global `chunk_index` (`0..K-1`), omitting `null` values for `page_number` / `section_heading`.
- **Strict `DA-8` vs `DA-9` Boundary:** Do **not** add `@TransactionalEventListener` or `@Async` `DocumentIngestionWorker` in `DA-8`; `POST /api/v1/documents/upload` must continue returning `status = PENDING` so all `DA-7` suites stay green and `DA-9` owns async worker orchestration cleanly.

## Review Focus

1. **Windows temp-file lock leak (`FileSystemException`) in `PagePdfDocumentReader`:** Spring AI `1.0.3` `PagePdfDocumentReader` never closes `Resource.getInputStream()` or `protected final PDDocument document`. `CloseablePagePdfDocumentReader` must track and close every opened `InputStream` plus `this.document.close()` before `Files.deleteIfExists(tempFile)` (`Task 3: CompositeDocumentExtractorTest`).
2. **`CL100K_BASE` byte-token slicing corrupting multi-byte Vietnamese diacritics (`ệ`, `ữ`, `ắ`) into `\uFFFD` or stalling on unbroken `> 800`-token strings:** `OverlappingTokenTextSplitter` must trim broken UTF-8 boundary tokens (`\uFFFD`), snap to linguistic boundaries, and enforce `nextStart > startToken` forward progress (`Task 4: SmartChunkingServiceTest`).
3. **Markdown `#` comments inside fenced code blocks (```` ``` ```` / `~~~`) or table separator rows (`| --- |`) misparsed as ATX/Setext `section_heading`s:** `MarkdownSectionExtractor` must track active code fences and reject pipe-table underlines as Setext headings (`Task 2: MarkdownAndPlainTextExtractorTest`).
4. **High-compression `.docx` OOXML ZIP-bomb bypassing `DA-7`'s `64 KiB` header check and exhausting Heap RAM:** `DocxDocumentExtractor` must enforce `ZipSecureFile` (`minInflateRatio = 0.01`, `maxEntrySize = 100 MiB`, `maxFileCount = 1000`) plus a `maxExtractedChars = 5,000,000` SAX write guard (`Task 3: CompositeDocumentExtractorTest`).
5. **Non-deterministic `Document.id` causing `DataIntegrityViolationException` (`uq_document_chunks_doc_version_index`) on `DA-9` `@Retryable` batch retries, or `file_name` mismatching `documents.original_name` in `V5` trigger:** `SmartChunkingService` must assign `ChunkIdGenerator.generate(documentId, docVersion, chunkIndex)` and exact `originalName`, verified by calling `vectorStore.add(chunks)` twice against real `pgvector:pg16` (`Task 5: ExtractorAndChunkerMinioPgVectorIntegrationTest`).

---

### Task 1: Feature Branch, `IngestionProperties`, Domain Records, `DocumentExtractionException` & `ChunkIdGenerator`

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/documind/exception/DocumentExtractionErrorCode.java`
- Create: `backend/src/main/java/com/documind/exception/DocumentExtractionException.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/IngestionProperties.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/ChunkMetadataKeys.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/DocumentIngestionContext.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/ExtractedSegment.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/ChunkIdGenerator.java`
- Test: `backend/src/test/java/com/documind/service/ingestion/IngestionContractsAndChunkIdTest.java`

**Interfaces:**
- Consumes: `com.documind.domain.enums.AccessLevel`, `com.documind.domain.event.DocumentUploadedEvent`.
- Produces:
  - `public enum DocumentExtractionErrorCode { EMPTY_DOCUMENT, NO_EXTRACTABLE_TEXT, ENCRYPTED_DOCUMENT, CORRUPT_DOCUMENT, EXTRACTION_LIMIT_EXCEEDED, UNSUPPORTED_FORMAT }`
  - `public class DocumentExtractionException extends RuntimeException`:
    - `public DocumentExtractionException(DocumentExtractionErrorCode errorCode, String message)`
    - `public DocumentExtractionException(DocumentExtractionErrorCode errorCode, String message, Throwable cause)`
    - `public DocumentExtractionErrorCode errorCode()`
  - `IngestionProperties` (`@Validated @ConfigurationProperties(prefix = "documind.ingestion")`):
    - `public record IngestionProperties(ExtractionProperties extraction, ChunkingProperties chunking)` (with `public static IngestionProperties defaults()`)
    - `public record ExtractionProperties(long maxSpoolBytes, int maxExtractedChars, double zipMinInflateRatio, long zipMaxEntrySizeBytes, long zipMaxFileCount)` (defaults: `52_428_800L`, `5_000_000`, `0.01d`, `104_857_600L`, `1_000L`)
    - `public record ChunkingProperties(int chunkSize, int chunkOverlap, int maxChunksPerDocument)` (defaults: `800`, `150`, `10_000`; compact constructor validates `chunkSize >= 32`, `0 <= chunkOverlap < chunkSize`, `maxChunksPerDocument >= 1`)
  - `ChunkMetadataKeys` (`final` constants class):
    - `DOCUMENT_ID = "document_id"`, `DEPARTMENT_ID = "department_id"`, `ACCESS_LEVEL = "access_level"`, `FILE_NAME = "file_name"`, `DOC_VERSION = "doc_version"`, `CHUNK_INDEX = "chunk_index"`, `PAGE_NUMBER = "page_number"`, `SECTION_HEADING = "section_heading"`
  - `public record DocumentIngestionContext(UUID documentId, UUID departmentId, String storageKey, String originalName, String fileExtension, AccessLevel accessLevel, int docVersion)` with `public static DocumentIngestionContext from(DocumentUploadedEvent event)` (normalizes `fileExtension` lowercase without leading dot; validates non-null/non-blank fields and `docVersion >= 1`)
  - `public record ExtractedSegment(String content, Integer pageNumber, String sectionHeading)` (validates non-blank `content`, `pageNumber == null || pageNumber >= 1`)
  - `ChunkIdGenerator`:
    - `public static UUID generate(UUID documentId, int docVersion, int chunkIndex)` -> `UUID.nameUUIDFromBytes(("documind:chunk:" + documentId + ":" + docVersion + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8))`

- [ ] **Step 1: Create feature branch `feat/da-8-extractor-smart-chunker` and commit spec + plan**

```powershell
git checkout -b feat/da-8-extractor-smart-chunker
git add docs/superpowers/specs/2026-11-17-da8-extractor-smart-chunker-design.md docs/superpowers/plans/2026-11-17-da8-extractor-smart-chunker.md
git commit -m "docs: add DA-8 extractor and smart chunker design spec and plan"
```

- [ ] **Step 2: Write failing `IngestionContractsAndChunkIdTest`**

Assert in `IngestionContractsAndChunkIdTest`:
- `IngestionProperties.defaults()` yields `maxSpoolBytes == 52_428_800L`, `maxExtractedChars == 5_000_000`, `zipMinInflateRatio == 0.01d`, `chunkSize == 800`, `chunkOverlap == 150`, `maxChunksPerDocument == 10_000`.
- `ChunkingProperties` rejects `chunkOverlap >= chunkSize` or negative `chunkOverlap` with `IllegalArgumentException`.
- `DocumentIngestionContext.from(DocumentUploadedEvent)` maps all 7 fields accurately, normalizes `".PDF"` -> `"pdf"`, and rejects `docVersion < 1`.
- `ChunkIdGenerator.generate(docId, 1, 0)` is 100% deterministic across repeated calls and produces distinct UUIDs when `docId`, `docVersion`, or `chunkIndex` changes.
- `ExtractedSegment` rejects blank content and `pageNumber <= 0`.

- [ ] **Step 3: Run test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=IngestionContractsAndChunkIdTest"`
Expected: FAIL (classes do not exist yet).

- [ ] **Step 4: Implement `application.yml` block, exceptions, properties, context records, and `ChunkIdGenerator`**

Add `documind.ingestion` YAML defaults to `backend/src/main/resources/application.yml` matching Spec Section 4, and implement all records/classes in `com.documind.exception` and `com.documind.service.ingestion` with `@ConstructorBinding` null-safe defaults on `IngestionProperties`.

- [ ] **Step 5: Run test to verify pass and commit**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=IngestionContractsAndChunkIdTest"`
Expected: PASS.

```powershell
git add backend/src/main/resources/application.yml backend/src/main/java/com/documind/exception/DocumentExtractionErrorCode.java backend/src/main/java/com/documind/exception/DocumentExtractionException.java backend/src/main/java/com/documind/service/ingestion backend/src/test/java/com/documind/service/ingestion/IngestionContractsAndChunkIdTest.java
git commit -m "feat(ingestion): add DA-8 config, extraction exceptions, contracts, and deterministic ChunkIdGenerator"
```

---

### Task 2: Vietnamese Text Normalizer, `MarkdownSectionExtractor` & `PlainTextSectionExtractor`

**Files:**
- Create: `backend/src/main/java/com/documind/service/ingestion/IngestionTextNormalizer.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/MarkdownSectionExtractor.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/PlainTextSectionExtractor.java`
- Test: `backend/src/test/java/com/documind/service/ingestion/MarkdownAndPlainTextExtractorTest.java`

**Interfaces:**
- Consumes: `ExtractedSegment`, `DocumentExtractionErrorCode`, `DocumentExtractionException`, `IngestionProperties`.
- Produces:
  - `IngestionTextNormalizer` (package-private utility):
    - `static String normalizeText(String raw)` -> strips `\uFEFF` leading BOM, `\u0000`, `\u00AD` (soft hyphen), converts `\r\n`/`\r` -> `\n`, normalizes `Normalizer.Form.NFC`, trims trailing horizontal whitespace per line, collapses `3+` consecutive `\n` to `\n\n`, and `trim()`s.
    - `static String readBoundedUtf8(InputStream inputStream, long maxBytes, int maxChars, String originalName)` -> enforces byte ceiling (`maxBytes`) and character ceiling (`maxChars`), throwing `DocumentExtractionException(EXTRACTION_LIMIT_EXCEEDED, ...)` on breach or `CORRUPT_DOCUMENT` on malformed binary/IO failure.
  - `MarkdownSectionExtractor` (`@Component`):
    - `public List<ExtractedSegment> extract(InputStream inputStream, String originalName)`
    - `public List<ExtractedSegment> extractFromText(String rawMarkdown, String originalName)`
  - `PlainTextSectionExtractor` (`@Component`):
    - `public List<ExtractedSegment> extract(InputStream inputStream, String originalName)`
    - `public List<ExtractedSegment> extractFromText(String rawText, String originalName)`

- [ ] **Step 1: Write failing `MarkdownAndPlainTextExtractorTest`**

Assert in `MarkdownAndPlainTextExtractorTest`:
1. **Hierarchical ATX & Setext Vietnamese Markdown:** Input with `# Chương 1: Quy định chung`, `## 1.1 Thời giờ làm việc`, `### Ca hành chính`, followed by `## 1.2 Nghỉ phép năm` produces `ExtractedSegment`s with `pageNumber == null` and exact `sectionHeading` breadcrumbs `"Chương 1: Quy định chung > 1.1 Thời giờ làm việc > Ca hành chính"` then `"Chương 1: Quy định chung > 1.2 Nghỉ phép năm"` (confirming level-2 reset clears level-3).
2. **Fenced Code Block & Pipe-Table Immunity (Review Focus #3):** Markdown containing ```` ```python\n# Đây là comment không phải heading\nx = 1\n``` ```` and a Markdown table `| Bậc | Lương |\n| --- | --- |\n| 1 | 20M |` keeps both the code block and full table inside the current section without treating `# Đây là comment` as ATX or `| --- | --- |` as a Setext heading.
3. **Vietnamese `.txt` Structural Headings + UTF-8 BOM + `NFD -> NFC`:** Input starting with `\uFEFF` and decomposed `NFD` Vietnamese (`"CHƯƠNG I: QUY ĐỊNH BẢO MẬT\n\nĐiều 1. Phạm vi áp dụng\nÁp dụng cho toàn bộ nhân sự."`) produces precomposed `NFC` content and `sectionHeading == "CHƯƠNG I: QUY ĐỊNH BẢO MẬT > Điều 1. Phạm vi áp dụng"`.
4. **Unstructured `.txt` Prose:** Plain multi-paragraph Vietnamese `.txt` without headings emits 1 `ExtractedSegment` with `sectionHeading == null` and `pageNumber == null`.
5. **Empty / Limit Guards:** Whitespace-only `.md`/`.txt` throws `DocumentExtractionException` with `errorCode() == EMPTY_DOCUMENT`; stream exceeding `maxExtractedChars` throws `EXTRACTION_LIMIT_EXCEEDED`.

- [ ] **Step 2: Run test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=MarkdownAndPlainTextExtractorTest"`
Expected: FAIL (classes not defined yet).

- [ ] **Step 3: Implement `IngestionTextNormalizer`, `MarkdownSectionExtractor`, and `PlainTextSectionExtractor`**

- Implement `IngestionTextNormalizer` with bounded `InputStream` byte counter + `InputStreamReader(..., StandardCharsets.UTF_8)` character counter and `NFC` normalization.
- Implement `MarkdownSectionExtractor` tracking active code fences (` ``` ` or `~~~` of length `>= 3`), ATX `#`..`######` headings (stripping optional closing `#`s), Setext `===`/`---` underlines (only when not inside a fence, preceded by a single non-blank non-pipe text line, and consisting solely of `=` or `-` of length `>= 3`), and a 6-level breadcrumb array joined with `" > "`.
- Implement `PlainTextSectionExtractor` matching standalone Vietnamese structural headings (`<= 140` chars, preceded by start-of-text or blank line, not ending in `.`/`?`/`!`):
  - Level 1: `^(?iu)(phần|chương)\s+[ivxlcdm\d]+([.:\-–—\s].*)?$`
  - Level 2: `^(?iu)(mục|điều)\s+\d+([.:\-–—\s].*)?$` or `^[IVX]+\.\s+\S+.*$`
  - Level 3: `^\d+(\.\d+){0,2}\.?\s+\p{Lu}.*$`

- [ ] **Step 4: Run test to verify pass and commit**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=MarkdownAndPlainTextExtractorTest"`
Expected: PASS.

```powershell
git add backend/src/main/java/com/documind/service/ingestion/IngestionTextNormalizer.java backend/src/main/java/com/documind/service/ingestion/MarkdownSectionExtractor.java backend/src/main/java/com/documind/service/ingestion/PlainTextSectionExtractor.java backend/src/test/java/com/documind/service/ingestion/MarkdownAndPlainTextExtractorTest.java
git commit -m "feat(ingestion): add NFC normalizer, fence-safe Markdown extractor, and TXT section extractor (DA-8)"
```

---

### Task 3: Tiered `PdfDocumentExtractor`, Anti-ZIP-Bomb `DocxDocumentExtractor` & `CompositeDocumentExtractor`

**Files:**
- Create: `backend/src/main/java/com/documind/service/ingestion/PdfDocumentExtractor.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/DocxDocumentExtractor.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/CompositeDocumentExtractor.java`
- Test: `backend/src/test/java/com/documind/service/ingestion/CompositeDocumentExtractorTest.java`

**Interfaces:**
- Consumes: `MinioStorageService`, `IngestionProperties`, `IngestionTextNormalizer`, `MarkdownSectionExtractor`, `PlainTextSectionExtractor`, `DocumentIngestionContext`, `ExtractedSegment`, `DocumentExtractionException`.
- Produces:
  - `PdfDocumentExtractor` (`@Component`):
    - `public List<ExtractedSegment> extract(Path pdfTempFile, String originalName)`
    - Package-private constructor overload accepting a pluggable Tier-1 `Function<Path, Map<Integer, String>>` hook so unit tests can deterministically simulate `PagePdfDocumentReader` empty/exception states to exercise Tier 2 (`PDFTextStripper`) and Tier 3 (`Tika PDFParser NO_OCR` `<div class="page">`).
  - `DocxDocumentExtractor` (`@Component`):
    - `public List<ExtractedSegment> extract(Path docxTempFile, String originalName)`
  - `CompositeDocumentExtractor` (`@Service`):
    - `public List<ExtractedSegment> extract(DocumentIngestionContext context)`
    - `public List<ExtractedSegment> extractFromStream(String originalName, String fileExtension, InputStream rawStream)`

- [ ] **Step 1: Write failing `CompositeDocumentExtractorTest`**

Build portable in-memory fixtures in `CompositeDocumentExtractorTest` (zero external files):
1. **Multi-Page Vietnamese Unicode PDF + Blank Page Skip:** Generate a 3-page PDF via PDFBox `3.0.3` + `PDType0Font.load(doc, PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"))` where Page 1 has `"Quy chế nghỉ phép năm: 12 ngày có hưởng lương"`, Page 2 is blank, and Page 3 has `"Phụ lục chế độ bảo hiểm xã hội bắt buộc"`. Assert `CompositeDocumentExtractor` returns **2** segments with `pageNumber == 1` and `pageNumber == 3` (never renumbered to `2`!), `sectionHeading == null`, and exact Vietnamese diacritics intact.
2. **PDF Tier-2 (`PDFTextStripper`) & Tier-3 (`Tika PDFParser <div class="page">`) Rescue:** Inject a failing/empty Tier-1 stub into `PdfDocumentExtractor` on the 3-page Vietnamese PDF -> assert Tier 2 still recovers exact `pageNumber == 1` and `pageNumber == 3`; also test Tier-3 Tika `<div class="page">` SAX handler directly on multi-page PDF bytes -> exact `pageNumber == 1` and `pageNumber == 3` with `OCR_STRATEGY.NO_OCR`.
3. **Scanned Image-Only PDF & Encrypted PDF Fail-Fast:** Generate a 2-page PDF containing only a `PDImageXObject` (zero text) -> throws `DocumentExtractionException` with `errorCode() == NO_EXTRACTABLE_TEXT`; generate a password-encrypted PDF (`StandardProtectionPolicy`) -> throws `ENCRYPTED_DOCUMENT`.
4. **Windows Temp-File Handle Closure (Review Focus #1):** Snapshot `Files.list(Path.of(System.getProperty("java.io.tmpdir")))` matching `documind-extract-*` before and after both successful PDF/DOCX extractions and throwing extractions -> assert **zero** new leftover temp files.
5. **Vietnamese `.docx` Breadcrumbs + Table + Unstyled Fallback + ZIP-Bomb Rejection (Review Focus #4):**
   - Generate a `.docx` via `XWPFDocument` (defining `Heading1` / `Heading2` styles in `word/styles.xml`, Vietnamese headings `"Chương 1: Nhân sự"` -> `"Điều 2: Phụ cấp"`, and a 2x2 `XWPFTable`) -> asserts breadcrumb `"Chương 1: Nhân sự > Điều 2: Phụ cấp"` and pipe-formatted table cells.
   - Generate an unstyled `.docx` containing `"CHƯƠNG I: VẬN HÀNH\n\nNội dung quy trình"` -> falls back cleanly and recovers `"CHƯƠNG I: VẬN HÀNH"`.
   - Generate an OOXML `.docx` with a highly compressible `word/document.xml` breaching `maxExtractedChars` / `zipMinInflateRatio` -> throws `DocumentExtractionException` with `errorCode() == EXTRACTION_LIMIT_EXCEEDED` and leaves zero temp files.
6. **MinIO Delegation (`extract(DocumentIngestionContext)`):** Mock `MinioStorageService.openStream(storageKey)` for `.pdf`, `.docx`, `.md`, and `.txt`, verifying `ResponseInputStream` is closed in every case and `UnsupportedFormat` (`"xlsx"`) throws `UNSUPPORTED_FORMAT`.

- [ ] **Step 2: Run test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=CompositeDocumentExtractorTest"`
Expected: FAIL (extractor classes do not exist yet).

- [ ] **Step 3: Implement `PdfDocumentExtractor`, `DocxDocumentExtractor`, and `CompositeDocumentExtractor`**

- In `PdfDocumentExtractor`:
  - Implement inner `CloseablePagePdfDocumentReader extends PagePdfDocumentReader implements AutoCloseable` backed by a stream-tracking `FileSystemResource` wrapper so `close()` closes both every opened `InputStream` and `super.document.close()`.
  - Implement `Loader.loadPDF(pdfTempFile.toFile())` pre-flight + Tier-2 per-page `PDFTextStripper` rescue (`setSortByPosition(true)`), and Tier-3 `org.apache.tika.parser.pdf.PDFParser` (`PDFParserConfig` with `setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR)`, `setSortByPosition(true)`, `setExtractAcroFormContent(true)`) + custom SAX handler tracking `<div class="page">` depth.
- In `DocxDocumentExtractor`:
  - Apply `ZipSecureFile.setMinInflateRatio(props.extraction().zipMinInflateRatio())`, `setMaxEntrySize(props.extraction().zipMaxEntrySizeBytes())`, `setMaxFileCount(props.extraction().zipMaxFileCount())`.
  - Parse `TikaInputStream.get(docxTempFile)` via `AutoDetectParser` + `HeadingAwareXhtmlContentHandler` (`<h1>`..`<h6>` breadcrumb stack, `<tr>`/`<td>`/`<th>` -> `| cell |`, enforcing `maxExtractedChars` ceiling). Map POI/Tika inflation/limit exceptions (`IOException` with `"Zip bomb"` / `"inflate ratio"` / `"MAX_FILE_COUNT"` or SAX limit breach) to `EXTRACTION_LIMIT_EXCEEDED`, encrypted OOXML (`EncryptedDocumentException`) to `ENCRYPTED_DOCUMENT`, and fallback to `TikaDocumentReader` + `PlainTextSectionExtractor` when zero `<h1>..<h6>` tags were seen.
- In `CompositeDocumentExtractor`:
  - Route `"pdf"` and `"docx"` through `spoolToTempFile(rawStream, "." + ext)` (`Files.createTempFile("documind-extract-", suffix)`, enforcing `maxSpoolBytes = 52_428_800L`, `Files.deleteIfExists(tempFile)` in `finally`), and route `"md"` and `"txt"` directly to `MarkdownSectionExtractor` / `PlainTextSectionExtractor`.

- [ ] **Step 4: Run test to verify pass and commit**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=CompositeDocumentExtractorTest"`
Expected: PASS.

```powershell
git add backend/src/main/java/com/documind/service/ingestion/PdfDocumentExtractor.java backend/src/main/java/com/documind/service/ingestion/DocxDocumentExtractor.java backend/src/main/java/com/documind/service/ingestion/CompositeDocumentExtractor.java backend/src/test/java/com/documind/service/ingestion/CompositeDocumentExtractorTest.java
git commit -m "feat(ingestion): implement CompositeDocumentExtractor for PDF, DOCX, MD, and TXT (DA-8)"
```

---

### Task 4: `OverlappingTokenTextSplitter` (`800 / 150`) & `SmartChunkingService`

**Files:**
- Create: `backend/src/main/java/com/documind/service/ingestion/OverlappingTokenTextSplitter.java`
- Create: `backend/src/main/java/com/documind/service/ingestion/SmartChunkingService.java`
- Test: `backend/src/test/java/com/documind/service/ingestion/SmartChunkingServiceTest.java`

**Interfaces:**
- Consumes: `IngestionProperties`, `ChunkMetadataKeys`, `ChunkIdGenerator`, `DocumentIngestionContext`, `ExtractedSegment`, `DocumentExtractionException`, `org.springframework.ai.transformer.splitter.TokenTextSplitter`, `org.springframework.ai.document.Document`, `com.knuddels.jtokkit.Encodings`.
- Produces:
  - `OverlappingTokenTextSplitter extends TokenTextSplitter`:
    - `public OverlappingTokenTextSplitter(int chunkSize, int chunkOverlap, int maxChunks)`
    - `public int countTokens(String text)`
    - `@Override protected List<String> splitText(String text)`
    - `public List<String> splitTextWithBudget(String text, int effectiveChunkSize, int effectiveOverlap)`
  - `SmartChunkingService` (`@Service`):
    - `public List<Document> chunk(DocumentIngestionContext context, List<ExtractedSegment> segments)`

- [ ] **Step 1: Write failing `SmartChunkingServiceTest`**

Assert in `SmartChunkingServiceTest`:
1. **`800 / 150` Sliding-Window Overlap & Vietnamese UTF-8 Integrity (Review Focus #2):** Construct a ~`1,900`-token multi-paragraph Vietnamese policy segment (`"Điều khoản bảo mật hệ thống trí tuệ nhân tạo doanh nghiệp..."`). Assert:
   - Splits into `3` chunks; every chunk satisfies `splitter.countTokens(chunk.getText()) <= 800`.
   - Consecutive chunks share an overlapping Vietnamese phrase of ~`100..150` tokens snapped cleanly on sentence/paragraph/word boundaries (never cutting mid-word, `0` occurrences of `'\uFFFD'`).
2. **Unbroken Giant Token Termination (Review Focus #2):** A synthetic `1,800`-token unbroken string without spaces (`"a1b2c3...".repeat(...)`) terminates cleanly in `< 1s` with `<= 800` tokens per chunk and zero `\uFFFD`.
3. **Contextual Header Prefix Within `800`-Token Ceiling:** Given a long segment with `sectionHeading == "Chương 3: Phúc lợi > Điều 7: Nghỉ thai sản"`, every resulting chunk starts with `"Chương 3: Phúc lợi > Điều 7: Nghỉ thai sản\n\n"`, `metadata.get("section_heading")` equals `"Chương 3: Phúc lợi > Điều 7: Nghỉ thai sản"`, AND `splitter.countTokens(chunk.getText()) <= 800` (proving prefix tokens were accounted for in the `800`-token budget). If a segment's body already begins with the heading text, the heading is not duplicated.
4. **Strict Page / Section Isolation & Global `chunk_index` + Metadata Contract:** Given PDF segments for Page `1` (`200` tokens) and Page `3` (`950` tokens):
   - Emits `3` chunks total (`1` from Page `1`, `2` from Page `3` — Page `1` and Page `3` are never merged!).
   - `chunk_index` is strictly `0, 1, 2`; `page_number` is `1, 3, 3`; `section_heading` key is absent on PDF chunks; `page_number` key is absent on `.md`/`.docx`/`.txt` chunks.
   - `document_id`, `department_id`, `file_name` (`== context.originalName()`), `access_level`, and `doc_version` match `context` on every chunk, and `chunk.getId()` equals `ChunkIdGenerator.generate(docId, docVersion, i).toString()`.
5. **`maxChunksPerDocument` Guard:** Exceeding configured `maxChunksPerDocument` throws `DocumentExtractionException` (`EXTRACTION_LIMIT_EXCEEDED`).

- [ ] **Step 2: Run test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=SmartChunkingServiceTest"`
Expected: FAIL (`OverlappingTokenTextSplitter` and `SmartChunkingService` do not exist yet).

- [ ] **Step 3: Implement `OverlappingTokenTextSplitter` and `SmartChunkingService`**

```java
// Core sliding-window invariant inside OverlappingTokenTextSplitter.splitTextWithBudget:
// 1. IntArrayList allTokens = encoding.encode(normalized);
// 2. Slice [start, min(start + budget, total)), trim leading/trailing '\uFFFD' tokens by adjusting indices inward.
// 3. If end < total, snap backward above 65% char floor: "\n\n" -> "\n" -> sentence (". ", "? ", "! ", "… ", "; ") -> whitespace.
// 4. int consumed = Math.max(1, encoding.encode(chunk).size());
// 5. if (start + consumed >= total) break;
// 6. int rawNext = start + Math.max(1, consumed - overlap);
//    Advance nextStart from rawNext (< start + consumed) to clean boundary free of '\uFFFD';
//    if (total - nextStart <= overlap && start + consumed >= total) break;
```

- In `SmartChunkingService.chunk(context, segments)`:
  - Reject empty `segments` with `DocumentExtractionException(EMPTY_DOCUMENT, ...)`.
  - For each `ExtractedSegment`, compute optional `headerPrefix` (capping `headerPrefix` at `chunkSize / 4` tokens on whitespace boundary if `sectionHeading` is abnormally long, while preserving full `sectionHeading` in `metadata`), split with `effectiveBudget = chunkSize - headerTokens`, prepend `headerPrefix` when `!body.startsWith(sectionHeading)`, assign `ChunkIdGenerator.generate(...)`, and populate `LinkedHashMap<String, Object>` metadata omitting `null` values.

- [ ] **Step 4: Run test to verify pass and commit**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=SmartChunkingServiceTest"`
Expected: PASS.

```powershell
git add backend/src/main/java/com/documind/service/ingestion/OverlappingTokenTextSplitter.java backend/src/main/java/com/documind/service/ingestion/SmartChunkingService.java backend/src/test/java/com/documind/service/ingestion/SmartChunkingServiceTest.java
git commit -m "feat(ingestion): implement OverlappingTokenTextSplitter (800/150) and SmartChunkingService (DA-8)"
```

---

### Task 5: End-to-End MinIO + PgVector (`V4`/`V5` Triggers & Idempotent Retry) Integration Suite

**Files:**
- Create: `backend/src/test/java/com/documind/service/ingestion/ExtractorAndChunkerMinioPgVectorIntegrationTest.java`

**Interfaces:**
- Consumes: `CompositeDocumentExtractor`, `SmartChunkingService`, `MinioStorageService`, `DocumentRepository`, `DepartmentRepository`, `UserRepository`, `VectorStore`, `JdbcTemplate`, `PostgresTestcontainersConfig`, `MinioTestcontainersConfig`.
- Produces: End-to-end Testcontainers verification proving `DA-8` outputs satisfy all MinIO S3, `PgVectorStore`, and `V4`/`V5` PostgreSQL trigger contracts ahead of `DA-9`.

- [ ] **Step 1: Write `ExtractorAndChunkerMinioPgVectorIntegrationTest`**

Create `ExtractorAndChunkerMinioPgVectorIntegrationTest` (`@SpringBootTest`, `@ActiveProfiles("dev")`, `@Import({PostgresTestcontainersConfig.class, MinioTestcontainersConfig.class})`) verifying:
1. **All 4 Vietnamese Formats Through Real MinIO -> Extractor -> SmartChunker -> `PgVectorStore` (`V4`/`V5` Trigger Gate, Review Focus #5):**
   - Upload 4 real Vietnamese documents to MinIO (`"Sổ tay Nhân sự #2026.pdf"` with 3 pages where page 2 is blank, `"Quy trình Phê duyệt Nghỉ phép.docx"` with `Heading1`/`Heading2` + table, `"Kiến trúc Hệ thống RAG.md"` with nested headings + fenced code block, and `"Nội quy Bảo mật CNTT.txt"` with `CHƯƠNG`/`ĐIỀU`) using `minioStorageService.uploadStream(...)` where S3 `storageKey` uses sanitized filenames while `DocumentEntity.originalName` keeps full Vietnamese spaces/symbols.
   - Run `CompositeDocumentExtractor.extract(ctx)` -> `SmartChunkingService.chunk(ctx, segments)` -> `vectorStore.add(chunks)` for all 4 documents.
   - Query `document_chunks` via `JdbcTemplate`:
     - PDF rows have `page_number IN (1, 3)`, `doc_version = 1`, contiguous `chunk_index` (`0..K-1`), and `metadata->>'file_name' == "Sổ tay Nhân sự #2026.pdf"`.
     - `.docx`, `.md`, `.txt` rows have SQL `page_number IS NULL`, `jsonb_exists(metadata, 'page_number') == true` (normalized by `V5` trigger), non-blank `metadata->>'section_heading'`, and `content` prefixed with `section_heading`.
2. **Idempotent `DA-9` Retry Proof (Review Focus #5):**
   - Invoke `vectorStore.add(pdfChunks)` and `vectorStore.add(docxChunks)` a **second time** with the exact same `SmartChunkingService` output -> succeeds with zero `DataIntegrityViolationException` (`uq_document_chunks_doc_version_index` satisfied via `ON CONFLICT (id) DO UPDATE`) and row count in `document_chunks` remains unchanged.
3. **RBAC-Filtered Vector Search Round-Trip:**
   - Execute `vectorStore.similaritySearch(...)` with `FilterExpressionBuilder` department RBAC filter -> returned `Document` hits preserve `file_name`, `page_number` (for PDF), `section_heading` (for `.docx`/`.md`/`.txt`), and `chunk_index`.

- [ ] **Step 2: Run full backend test suite (Unit + All Testcontainers Suites)**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: PASS (`BUILD SUCCESS`; all `DA-5`, `DA-6`, `DA-7`, and `DA-8` unit + Testcontainers tests pass with `0` failures).

- [ ] **Step 3: Commit integration suite**

```powershell
git add backend/src/test/java/com/documind/service/ingestion/ExtractorAndChunkerMinioPgVectorIntegrationTest.java
git commit -m "test(ingestion): add MinIO + pgvector V4/V5 trigger and idempotent upsert integration suite (DA-8)"
```
