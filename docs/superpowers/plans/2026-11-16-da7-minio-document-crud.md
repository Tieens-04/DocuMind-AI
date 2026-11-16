# DA-7 MinIO S3 Streaming, SHA-256 Deduplication & Document CRUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver zero-Heap-bloat MinIO S3 streaming (`<= 50 MiB`), single-pass `SHA-256` per-department deduplication (`409 Conflict`), bounded `64 KiB` Apache Tika real-MIME validation (`.pdf`, `.docx`, `.txt`, `.md`), Presigned inline GET URLs (`15m`), and the full `/api/v1/documents` CRUD API verified against real PostgreSQL + MinIO Testcontainers and live `~40 MiB` PDF streaming.

**Architecture:** `DocumentService` executes S3 streaming **outside** any database transaction so Virtual Thread uploads never hold HikariCP connections during network I/O: first validating filename/size and inspecting a `64 KiB` `BufferedInputStream` (`mark`/`reset`) via Apache Tika + OOXML ZIP header checks, then streaming once through `HashingBoundedInputStream` (`DigestInputStream("SHA-256")` + `50 MiB` byte guard) into MinIO (`departments/{deptId}/{uploadUuid}/v1/{safeName}`), and finally opening a `< 10ms` `TransactionTemplate` block to persist `DocumentEntity(PENDING)`, increment `departments.cache_version`, log `AuditLog(DOC_UPLOAD)`, and publish `DocumentUploadedEvent` (with compensating S3 deletion on `409` or rollback, and `TransactionSynchronization.afterCommit` S3 deletion on `DELETE`).

**Tech Stack:** Java 21 (Virtual Threads, Records), Spring Boot `3.4.5`, Spring Data JPA, AWS SDK for Java S3 v2 (`2.30.26`), Apache Tika `3.1.0`, PostgreSQL 16 (`pgvector/pgvector:pg16`), MinIO (`cgr.dev/chainguard/minio`), Testcontainers (`1.21.4`), Maven Wrapper (`.\mvnw.cmd`).

**Spec:** `docs/superpowers/specs/2026-11-16-da7-minio-document-crud-design.md`

## Global Constraints

- **Host Coexistence (Never Touch FlintFlow):** Never stop or collide with `flintflow-*` containers; live Postgres runs on `localhost:5432`, Redis on `6380`, MinIO on `9000`/`9001` (`documind-documents`), Backend on `8081`.
- **Maven Wrapper Only:** Always execute `.\mvnw.cmd` inside `backend/` in PowerShell (global `mvn` is not installed).
- **Exact 9-Table Contract (`DA-6`):** Reuse existing `documents`, `departments`, `users`, `audit_logs`, and `document_chunks` (`ON DELETE CASCADE`) without adding Flyway migrations or extra tables.
- **Byte-Exact `50 MiB` Ceiling:** `MAX_FILE_SIZE_BYTES = 52_428_800L` (`50 * 1024 * 1024`), matching `chk_documents_size` in `V1__init_relational_schema.sql` and `spring.servlet.multipart.max-file-size: 50MB`.
- **Preserve Existing Health Timeout Contract:** Keep `MinioConfig` default `S3Client` timeouts at `15s` / `5s` (`MinioHealthIndicatorTest`), applying the `60s` `uploadTimeout` override per-request on `PutObjectRequest`.

## Review Focus

1. **Forged or chunked stream exceeding `52,428,800` bytes (`50 MiB`) or streaming `0` bytes:** `HashingBoundedInputStream` must count actual bytes read during the single `SHA-256` pass, aborting immediately with `DocumentTooLargeException` (`413`) at byte `52,428,801` and rejecting `0`-byte streams with `InvalidDocumentRequestException` (`400`) (`Task 1: HashingBoundedInputStreamTest`).
2. **Arbitrary `.zip` or Zip-Slip (`../`) archive renamed to `.docx` bypassing `PK\x03\x04` magic bytes:** `FileValidationService` must scan local ZIP entry headers within the `64 KiB` `mark`/`reset` buffer to require at least one OOXML entry (`[Content_Types].xml`, `_rels/.rels`, `word/`, or `docProps/`), reject `..` path segments (`415`), and `reset()` the stream cleanly to byte `0` (`Task 2: FileValidationServiceTest`).
3. **Concurrent identical uploads in the same department racing into `uq_documents_department_hash`:** Each upload attempt must use its own `uploadUuid` in `storageKey`; when the losing racer catches `DataIntegrityViolationException`, `DocumentService` must resolve the winner's `existingDocumentId`, delete **only** the loser's staged S3 object, and throw `DuplicateDocumentException` (`409 Conflict`) (`Task 3: DocumentServiceTest` & `Task 4: DocumentCrudAndMinioStreamingIntegrationTest`).
4. **Transaction rollback during `DELETE /api/v1/documents/{id}` deleting a still-live S3 object:** `DocumentService.deleteDocument` must schedule `minioStorageService.deleteObjectQuietly(storageKey)` inside `TransactionSynchronization.afterCommit(...)` so a rolled-back DB transaction never deletes the MinIO object (`Task 3: DocumentServiceTest`).
5. **`MockMultipartFile` masking Heap RAM usage or `1MB` default multipart limit blocking `~40 MiB` PDFs:** Configure `multipart` (`50MB`/`52MB`/`2MB`) and stream an on-the-fly `40 MiB` `%PDF-1.4` `InputStream` via `java.net.http.HttpClient` against `RANDOM_PORT` + real MinIO Testcontainer, asserting `202 Accepted` and `< 35 MiB` post-GC Heap delta (`Task 4: DocumentCrudAndMinioStreamingIntegrationTest`).

---

### Task 1: Domain Exceptions, Multipart/Storage Config, `HashingBoundedInputStream` & `MinioStorageService`

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/documind/infrastructure/storage/StorageProperties.java`
- Create: `backend/src/main/java/com/documind/exception/InvalidDocumentRequestException.java`
- Create: `backend/src/main/java/com/documind/exception/DocumentTooLargeException.java`
- Create: `backend/src/main/java/com/documind/exception/UnsupportedDocumentTypeException.java`
- Create: `backend/src/main/java/com/documind/exception/DuplicateDocumentException.java`
- Create: `backend/src/main/java/com/documind/exception/DocumentNotFoundException.java`
- Create: `backend/src/main/java/com/documind/exception/ResourceNotFoundException.java`
- Create: `backend/src/main/java/com/documind/exception/DocumentAccessDeniedException.java`
- Create: `backend/src/main/java/com/documind/exception/DocumentStorageException.java`
- Create: `backend/src/main/java/com/documind/infrastructure/storage/HashingBoundedInputStream.java`
- Create: `backend/src/main/java/com/documind/infrastructure/storage/MinioStorageService.java`
- Test: `backend/src/test/java/com/documind/infrastructure/storage/HashingBoundedInputStreamTest.java`
- Test: `backend/src/test/java/com/documind/infrastructure/storage/MinioStorageServiceTest.java`

**Interfaces:**
- Produces:
  - Domain exceptions (`RuntimeException` subclasses):
    - `InvalidDocumentRequestException(String message)`
    - `DocumentTooLargeException(long maxSizeBytes, String message)` (`long maxSizeBytes()`)
    - `UnsupportedDocumentTypeException(String detectedMimeType, String message)` (`String detectedMimeType()`)
    - `DuplicateDocumentException(UUID existingDocumentId, String fileHash, String message)` (`UUID existingDocumentId()`, `String fileHash()`)
    - `DocumentNotFoundException(UUID documentId)` (`UUID documentId()`)
    - `ResourceNotFoundException(String message)`
    - `DocumentAccessDeniedException(String message)`
    - `DocumentStorageException(String message, Throwable cause)`
  - `StorageProperties(String endpoint, String accessKey, String secretKey, String bucket, String region, Duration uploadTimeout, Duration presignedExpiry)` with `@ConstructorBinding` canonical constructor (defaulting null `uploadTimeout` to `Duration.ofSeconds(60)` and null `presignedExpiry` to `Duration.ofMinutes(15)`) plus backwards-compatible 5-arg constructor `(endpoint, accessKey, secretKey, bucket, region)`.
  - `HashingBoundedInputStream extends FilterInputStream`:
    - `public static final long MAX_FILE_SIZE_BYTES = 52_428_800L;`
    - `public HashingBoundedInputStream(InputStream delegate, long maxSizeBytes)`
    - `public long bytesRead()`
    - `public String sha256Hex()` (64-char lowercase hex; throws `InvalidDocumentRequestException` if `bytesRead == 0`)
  - `MinioStorageService`:
    - `public String buildStorageKey(UUID departmentId, UUID uploadUuid, int version, String sanitizedFileName)` -> `"departments/" + departmentId + "/" + uploadUuid + "/v" + version + "/" + sanitizedFileName`
    - `public void uploadStream(String storageKey, InputStream inputStream, long contentLength, String contentType)`
    - `public ResponseInputStream<GetObjectResponse> openStream(String storageKey)`
    - `public void deleteObject(String storageKey)`
    - `public void deleteObjectQuietly(String storageKey)`
    - `public URL generatePresignedGetUrl(String storageKey, String inlineFileName)` and `generatePresignedGetUrl(String storageKey, String inlineFileName, Duration expiry)`

- [ ] **Step 1: Write failing `HashingBoundedInputStreamTest` and `MinioStorageServiceTest`**

Assert in `HashingBoundedInputStreamTest`:
- Single pass over `"Quy chế nhân sự DocuMind AI"` UTF-8 bytes yields exact `MessageDigest("SHA-256")` lowercase 64-char hex and exact `bytesRead()`.
- Reading `maxSizeBytes + 1` throws `DocumentTooLargeException` immediately at the boundary byte.
- Calling `sha256Hex()` on a `0`-byte stream throws `InvalidDocumentRequestException`.

Assert in `MinioStorageServiceTest` (mocking `S3Client` and `S3Presigner`):
- `buildStorageKey(deptId, uploadId, 1, "quy_che.pdf")` returns `"departments/" + deptId + "/" + uploadId + "/v1/quy_che.pdf"`.
- `uploadStream(...)` invokes `s3Client.putObject(PutObjectRequest, RequestBody)` with `bucket == "documind-documents"`, `contentType == "application/pdf"`, `contentLength`, and `PutObjectRequest.overrideConfiguration()` containing `apiCallTimeout = 60s` and `apiCallAttemptTimeout = 60s`.
- `generatePresignedGetUrl(...)` sets `GetObjectRequest.responseContentDisposition()` starting with `"inline; filename=\""` and `signatureDuration == Duration.ofMinutes(15)`.
- `deleteObjectQuietly(...)` swallows `SdkException` without throwing, while `uploadStream(...)` wraps `SdkException` in `DocumentStorageException`.

- [ ] **Step 2: Run tests to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=HashingBoundedInputStreamTest,MinioStorageServiceTest,MinioHealthIndicatorTest"`
Expected: FAIL (classes do not exist yet).

- [ ] **Step 3: Implement exceptions, config updates, `HashingBoundedInputStream`, and `MinioStorageService`**

- Update `backend/src/main/resources/application.yml`: add `spring.servlet.multipart` (`max-file-size: 50MB`, `max-request-size: 52MB`, `file-size-threshold: 2MB`) and `documind.storage` (`upload-timeout: 60s`, `presigned-expiry: 15m`).
- Update `StorageProperties` with `@ConstructorBinding` on the 7-arg canonical constructor (defaulting `uploadTimeout = Duration.ofSeconds(60)`, `presignedExpiry = Duration.ofMinutes(15)` when null) and keep the 5-arg delegating constructor.
- Implement `HashingBoundedInputStream` wrapping `DigestInputStream(delegate, MessageDigest.getInstance("SHA-256"))`, overriding `read()`, `read(byte[], int, int)` to increment `bytesRead` and throw `DocumentTooLargeException` when `bytesRead > maxSizeBytes`, and formatting `HexFormat.of().formatHex(digest.digest())`.
- Implement `MinioStorageService` using `RequestBody.fromInputStream(inputStream, contentLength)` and `AwsRequestOverrideConfiguration.builder().apiCallTimeout(props.uploadTimeout()).apiCallAttemptTimeout(props.uploadTimeout()).build()`.

- [ ] **Step 4: Run tests to verify pass**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=HashingBoundedInputStreamTest,MinioStorageServiceTest,MinioHealthIndicatorTest"`
Expected: PASS (`0` failures; `MinioHealthIndicatorTest` remains green).

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/resources/application.yml backend/src/main/java/com/documind/exception backend/src/main/java/com/documind/infrastructure/storage backend/src/test/java/com/documind/infrastructure/storage
git commit -m "feat(storage): add single-pass SHA-256 stream, exceptions, and MinioStorageService (DA-7)"
```

---

### Task 2: Bounded `64 KiB` Tika MIME & OOXML Container Validator (`FileValidationService`)

**Files:**
- Create: `backend/src/main/java/com/documind/service/document/FileValidationResult.java`
- Create: `backend/src/main/java/com/documind/service/document/FileValidationService.java`
- Test: `backend/src/test/java/com/documind/service/document/FileValidationServiceTest.java`

**Interfaces:**
- Consumes: `HashingBoundedInputStream.MAX_FILE_SIZE_BYTES`, `InvalidDocumentRequestException`, `DocumentTooLargeException`, `UnsupportedDocumentTypeException` from Task 1.
- Produces:
  - `public record FileValidationResult(String originalName, String sanitizedFileName, String fileExtension, String canonicalMimeType, long declaredSizeBytes, BufferedInputStream rewindedStream)`
  - `FileValidationService`:
    - `public static final int HEADER_INSPECTION_BYTES = 65_536;`
    - `public FileValidationResult validateAndPrepareStream(String rawOriginalFilename, long declaredSizeBytes, InputStream rawStream)`

- [ ] **Step 1: Write failing `FileValidationServiceTest`**

Test all cases in `FileValidationServiceTest`:
- Valid `.pdf` (`%PDF-1.4\n...`), valid `.docx` (generated in-memory ZIP containing `[Content_Types].xml` and `word/document.xml`), valid `.txt` (Vietnamese UTF-8 `"Tài liệu nội bộ phòng Nhân sự"`), and valid `.md` (`"# Quy trình nghỉ phép\n- 12 ngày/năm"`) succeed with expected `fileExtension` (`pdf`, `docx`, `txt`, `md`) and `canonicalMimeType`.
- Reading `result.rewindedStream().readAllBytes()` after validation returns **100% identical bytes** to the original input (confirming `mark(65536)` + `reset()` preserved byte `0`).
- Windows path `"C:\\fakepath\\Báo cáo Q3 #1.pdf"` normalizes `originalName` to `"Báo cáo Q3 #1.pdf"` and `sanitizedFileName` replaces unsafe characters (`#`, spaces, etc.) with `_`.
- Rejections:
  - `declaredSizeBytes == 0` or empty stream -> `InvalidDocumentRequestException`
  - `declaredSizeBytes > 52_428_800L` -> `DocumentTooLargeException`
  - Disallowed extension (`malware.exe`, `photo.png`, `noext`) -> `UnsupportedDocumentTypeException`
  - Spoofed PNG magic bytes (`\x89PNG\r\n\x1a\n...`) named `fake.pdf` -> `UnsupportedDocumentTypeException`
  - Plain `.zip` containing only `payload.exe` named `fake.docx` -> `UnsupportedDocumentTypeException`
  - `.docx` ZIP containing Zip-Slip entry `../evil.xml` -> `UnsupportedDocumentTypeException`
  - Binary stream containing `0x00` null bytes or `%PDF-1.4` named `notes.txt` / `readme.md` -> `UnsupportedDocumentTypeException`

- [ ] **Step 2: Run test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=FileValidationServiceTest"`
Expected: FAIL (`FileValidationService` not defined).

- [ ] **Step 3: Implement `FileValidationResult` and `FileValidationService`**

- Strip path prefixes (`/` and `\`), validate `originalName` non-blank and `<= 255` chars, extract lowercase extension (`pdf`, `docx`, `txt`, `md`), and build `sanitizedFileName` (`<= 180` chars, `[\\p{Cntrl}/\\\\#?%\\s]+` -> `_`).
- Check `declaredSizeBytes <= 0` (`InvalidDocumentRequestException`) and `declaredSizeBytes > MAX_FILE_SIZE_BYTES` (`DocumentTooLargeException`).
- Wrap `rawStream` in `new BufferedInputStream(rawStream, HEADER_INSPECTION_BYTES)`, `mark(HEADER_INSPECTION_BYTES)`, read up to `65,536` bytes (`readNBytes`), and `reset()`.
- Detect magic MIME via `TikaConfig.getDefaultConfig().getDetector().detect(TikaInputStream.get(headerBytes), new Metadata())` (byte-array backed only — zero temp file spooling, zero filename hint):
  - `pdf`: require `%PDF-` prefix and `application/pdf` -> canonical `"application/pdf"`.
  - `docx`: require `PK\x03\x04` prefix, Tika type in `{"application/zip", "application/x-tika-ooxml", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"}`, and `ZipInputStream` scan over `headerBytes` finding at least one entry matching `[Content_Types].xml`, `_rels/.rels`, `word/*`, or `docProps/*` while rejecting any entry name containing `..` -> canonical `"application/vnd.openxmlformats-officedocument.wordprocessingml.document"`.
  - `txt` / `md`: require zero `0x00` bytes in `headerBytes` and `"text".equals(mediaType.getType())` -> canonical `"text/plain"` (`txt`) or `"text/markdown"` (`md`).

- [ ] **Step 4: Run test to verify pass**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=FileValidationServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/documind/service/document/FileValidationResult.java backend/src/main/java/com/documind/service/document/FileValidationService.java backend/src/test/java/com/documind/service/document/FileValidationServiceTest.java
git commit -m "feat(validation): add bounded 64KiB Tika MIME and OOXML validator (DA-7)"
```

---

### Task 3: `CallerContextResolver`, Filtered Repository, DTOs, `DocumentUploadedEvent` & Two-Phase `DocumentService`

**Files:**
- Modify: `backend/src/main/java/com/documind/repository/DocumentRepository.java`
- Create: `backend/src/main/java/com/documind/domain/event/DocumentUploadedEvent.java`
- Create: `backend/src/main/java/com/documind/controller/dto/DocumentResponse.java`
- Create: `backend/src/main/java/com/documind/controller/dto/DocumentStatusResponse.java`
- Create: `backend/src/main/java/com/documind/controller/dto/PageResponse.java`
- Create: `backend/src/main/java/com/documind/service/document/CallerContext.java`
- Create: `backend/src/main/java/com/documind/service/document/CallerContextResolver.java`
- Create: `backend/src/main/java/com/documind/service/document/DevFallbackCallerContextResolver.java`
- Create: `backend/src/main/java/com/documind/service/document/DocumentService.java`
- Test: `backend/src/test/java/com/documind/service/document/DevFallbackCallerContextResolverTest.java`
- Test: `backend/src/test/java/com/documind/service/document/DocumentServiceTest.java`

**Interfaces:**
- Consumes: `MinioStorageService`, `HashingBoundedInputStream`, `FileValidationService`, `DepartmentRepository`, `UserRepository`, `AuditLogRepository`, `DocumentEntity`.
- Produces:
  - `DocumentRepository` additions:
    - `Optional<DocumentEntity> findByDepartmentIdAndFileHash(UUID departmentId, String fileHash);`
    - `@Query("""SELECT d FROM DocumentEntity d WHERE (:departmentId IS NULL OR d.departmentId = :departmentId) AND (:status IS NULL OR d.status = :status) AND (:accessLevel IS NULL OR d.accessLevel = :accessLevel)""") Page<DocumentEntity> findAllFiltered(@Param("departmentId") UUID departmentId, @Param("status") DocumentStatus status, @Param("accessLevel") AccessLevel accessLevel, Pageable pageable);`
  - `public record DocumentUploadedEvent(UUID documentId, UUID departmentId, String storageKey, String originalName, String fileExtension, AccessLevel accessLevel, int version)`
  - `public record CallerContext(UUID departmentId, UUID userId, boolean explicitDepartment, String ipAddress)`
  - `public interface CallerContextResolver { CallerContext resolve(UUID requestDepartmentId, HttpServletRequest request); }`
  - `public record DocumentResponse(UUID id, UUID departmentId, UUID uploadedBy, String originalName, String fileExtension, String mimeType, long fileSizeBytes, String fileHash, AccessLevel accessLevel, DocumentStatus status, int currentVersion, int chunkCount, String errorMessage, OffsetDateTime createdAt, OffsetDateTime updatedAt)` (with `static DocumentResponse from(DocumentEntity entity)`)
  - `public record DocumentStatusResponse(UUID id, DocumentStatus status, int chunkCount, int currentVersion, String errorMessage, OffsetDateTime updatedAt)` (with `static DocumentStatusResponse from(DocumentEntity entity)`)
  - `public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages)` (with `static <T> PageResponse<T> from(Page<T> page)`)
  - `DocumentService`:
    - `public DocumentResponse uploadDocument(MultipartFile file, AccessLevel accessLevel, CallerContext caller)`
    - `public PageResponse<DocumentResponse> listDocuments(UUID filterDepartmentId, DocumentStatus status, AccessLevel accessLevel, int page, int size, CallerContext caller)`
    - `public DocumentStatusResponse getDocumentStatus(UUID documentId, CallerContext caller)`
    - `public void deleteDocument(UUID documentId, CallerContext caller)`

- [ ] **Step 1: Write failing `DevFallbackCallerContextResolverTest` and `DocumentServiceTest`**

Assert in `DevFallbackCallerContextResolverTest`:
- Precedence: `requestDepartmentId` overrides `X-Department-Id`, which overrides dev fallback `11111111-1111-1111-1111-111111111111` (`explicitDepartment == false` only on fallback).
- Under `prod` profile (`MockEnvironment().withProperty("spring.profiles.active", "prod")`), missing both `requestDepartmentId` and `X-Department-Id` throws `InvalidDocumentRequestException`.
- Malformed UUID in `X-Department-Id` or `X-User-Id` throws `InvalidDocumentRequestException`.

Assert in `DocumentServiceTest`:
- `uploadDocument`: streams via `MinioStorageService.uploadStream` **before** `TransactionTemplate` runs, saves `DocumentEntity(status = PENDING)`, increments `DepartmentRepository.incrementCacheVersion(deptId)` (or `incrementAllCacheVersions()` when `PUBLIC`), persists `AuditLogEntity(DOC_UPLOAD)`, and publishes `DocumentUploadedEvent`.
- Race condition simulation: when `documentRepository.saveAndFlush(...)` throws `DataIntegrityViolationException`, `DocumentService` looks up `findByDepartmentIdAndFileHash` for `existingDocumentId`, calls `minioStorageService.deleteObjectQuietly(storageKey)`, and throws `DuplicateDocumentException` (`existingDocumentId` populated).
- Rollback safety on `deleteDocument`: register a real `TransactionSynchronizationManager` scope in test; verify `minioStorageService.deleteObjectQuietly` is **not** called before `afterCommit()`, IS called on commit, and is **never** called if transaction rolls back!

- [ ] **Step 2: Run tests to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=DevFallbackCallerContextResolverTest,DocumentServiceTest"`
Expected: FAIL (classes not defined yet).

- [ ] **Step 3: Implement repository methods, DTOs, `CallerContextResolver`, and `DocumentService`**

- Add `findByDepartmentIdAndFileHash` and `findAllFiltered` to `DocumentRepository`.
- Implement `DevFallbackCallerContextResolver` reading `Environment.acceptsProfiles(Profiles.of("prod"))`, headers `X-Department-Id`, `X-User-Id`, and `X-Forwarded-For` / `request.getRemoteAddr()`.
- Implement `DocumentService`:
  - Default `accessLevel` to `AccessLevel.DEPARTMENT` when null; validate `0 <= page` and `1 <= size <= 100`.
  - Pre-check `DepartmentEntity` (`ResourceNotFoundException` if missing) and optional `UserEntity` (`ResourceNotFoundException` if explicit `X-User-Id` missing; null out fallback Admin if absent in custom test DBs).
  - Phase 1 (non-transactional): `FileValidationService.validateAndPrepareStream(...)` -> `HashingBoundedInputStream` -> `minioStorageService.uploadStream(...)`.
  - Phase 2 (`transactionTemplate.execute(...)`): check `findByDepartmentIdAndFileHash`, `saveAndFlush(entity)`, bump `cache_version`, save `AuditLogEntity`, `eventPublisher.publishEvent(new DocumentUploadedEvent(...))`. Wrap Phase 2 in `try / catch (DataIntegrityViolationException | RuntimeException)` calling `minioStorageService.deleteObjectQuietly(storageKey)` on any failure (converting `DataIntegrityViolationException` to `DuplicateDocumentException` when `findByDepartmentIdAndFileHash` finds the winner).
  - `listDocuments`: resolve effective `departmentId` filter (`filterDepartmentId != null ? filterDepartmentId : (caller.explicitDepartment() ? caller.departmentId() : null)`), query `documentRepository.findAllFiltered(..., PageRequest.of(page, size, Sort.by(DESC, "createdAt")))`, and map to `PageResponse<DocumentResponse>`.
  - `getDocumentStatus` & `deleteDocument`: enforce cross-department guard when `caller.explicitDepartment()` is `true` and `!caller.departmentId().equals(doc.getDepartmentId())` (`getDocumentStatus` permits `AccessLevel.PUBLIC`, `deleteDocument` always throws `DocumentAccessDeniedException`). Schedule S3 deletion in `TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { minioStorageService.deleteObjectQuietly(storageKey); } })`.

- [ ] **Step 4: Run tests to verify pass**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=DevFallbackCallerContextResolverTest,DocumentServiceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/documind/repository/DocumentRepository.java backend/src/main/java/com/documind/domain/event backend/src/main/java/com/documind/controller/dto backend/src/main/java/com/documind/service/document backend/src/test/java/com/documind/service/document
git commit -m "feat(document): implement two-phase DocumentService, caller resolver, and events (DA-7)"
```

---

### Task 4: `DocumentController`, RFC 7807 `GlobalExceptionHandler` & Real MinIO + Postgres Testcontainers (`~40 MiB` Streaming)

**Files:**
- Create: `backend/src/main/java/com/documind/controller/DocumentController.java`
- Create: `backend/src/main/java/com/documind/controller/advice/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/documind/support/MinioTestcontainersConfig.java`
- Create: `backend/src/test/java/com/documind/controller/DocumentCrudAndMinioStreamingIntegrationTest.java`

**Interfaces:**
- Consumes: `DocumentService`, `CallerContextResolver`, `MinioStorageService`, DTOs, and domain exceptions from Tasks 1–3.
- Produces:
  - `DocumentController` (`@RestController @RequestMapping("/api/v1/documents")`):
    - `@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` -> `ResponseEntity<DocumentResponse>` (`202 Accepted`)
    - `@GetMapping` -> `PageResponse<DocumentResponse>` (`200 OK`)
    - `@GetMapping("/{id}/status")` -> `DocumentStatusResponse` (`200 OK`)
    - `@DeleteMapping("/{id}")` -> `ResponseEntity<Void>` (`204 No Content`)
  - `GlobalExceptionHandler` (`@RestControllerAdvice`): maps all 7 error classes + `MaxUploadSizeExceededException`, `MissingServletRequestPartException`, and `MethodArgumentTypeMismatchException` to `ProblemDetail` with `errorCode` and domain-specific properties (`existingDocumentId`, `fileHash`, `maxSizeBytes`, `detectedMimeType`).
  - `MinioTestcontainersConfig` (`@TestConfiguration(proxyBeanMethods = false)`): starts `GenericContainer<?>` with `cgr.dev/chainguard/minio:latest@sha256:634c989a2f1029162fc2676c263caa181213b9410449968532f750a6fb77532d` (`server /data`, port `9000`, `Wait.forHttp("/minio/health/live").forPort(9000).forStatusCode(200)`), binds `documind.storage.*` via Spring Boot 3.4 `DynamicPropertyRegistrar` (avoiding duplicate `@ConfigurationPropertiesScan` beans), and ensures bucket `documind-documents` exists via `S3Client` on startup.

- [ ] **Step 1: Write failing `DocumentCrudAndMinioStreamingIntegrationTest`**

Create `MinioTestcontainersConfig` and `DocumentCrudAndMinioStreamingIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `@Import({PostgresTestcontainersConfig.class, MinioTestcontainersConfig.class})`) asserting:
1. **Standard Upload (`< 300ms` post-warmup) & Presigned GET Round-Trip:** Warm up with 1 small `.txt` upload, then measure `POST /api/v1/documents/upload` for a Vietnamese `.md` / `.pdf` file -> `202 Accepted` in `< 300ms`, `status == PENDING`, `DocumentUploadedEvent` captured by a `@TestConfiguration` `@TransactionalEventListener(phase = AFTER_COMMIT)` collector, `AuditLog(DOC_UPLOAD)` persisted, `departments.cache_version` incremented, and `GET` on `minioStorageService.generatePresignedGetUrl(...)` via `HttpClient` returns `200 OK` with `Content-Disposition` starting with `inline` and matching bytes.
2. **Per-Department Deduplication & Orphan S3 Cleanup:** Re-uploading the identical file to `IT` returns `409 Conflict` with `ProblemDetail.properties.existingDocumentId == firstDocId`, AND `s3Client.listObjectsV2` under `departments/11111111-.../` contains **exactly 1 object** (proving compensating delete removed the staged duplicate!). Uploading the exact same file with `departmentId = 22222222-2222-2222-2222-222222222222` (`HR`) returns `202 Accepted`.
3. **Spoofed MIME & Validation Statuses:** PNG bytes uploaded as `fake.pdf` -> `415 Unsupported Media Type` (`errorCode == "UNSUPPORTED_DOCUMENT_TYPE"`); `0`-byte file -> `400 Bad Request`.
4. **Filtered Pagination, Cross-Department `403` & Hard Delete Cascade (`204`):** `GET /api/v1/documents?departmentId=...&status=PENDING` returns matching page; `GET /api/v1/documents/{itDocId}/status` with header `X-Department-Id: 22222222-...` returns `403 Forbidden` (for `DEPARTMENT` access level); inserting a vector chunk via `VectorStore.add(...)` then calling `DELETE /api/v1/documents/{itDocId}` returns `204 No Content`, cascades `document_chunks` count to `0`, records `AuditLog(DOC_DELETE)`, and `s3Client.headObject` throws `NoSuchKeyException`.
5. **~40 MiB On-The-Fly PDF Streaming & Heap Safety:** Stream a generated `40 MiB` (`41_943_040` bytes) valid `%PDF-1.4` `InputStream` inside a streaming `multipart/form-data` body via `java.net.http.HttpClient.BodyPublishers.ofInputStream(...)` (zero `40 MiB` `byte[]` in test client or server!). Measure `System.gc()` Heap usage before and after: assert `202 Accepted`, `s3Client.headObject` `contentLength() == 41_943_040L`, and post-GC Heap delta `< 35 MiB`.

- [ ] **Step 2: Run integration test to verify failure**

Run: `Set-Location backend; .\mvnw.cmd test "-Dtest=DocumentCrudAndMinioStreamingIntegrationTest"`
Expected: FAIL (`DocumentController` and `GlobalExceptionHandler` do not exist yet).

- [ ] **Step 3: Implement `DocumentController` and `GlobalExceptionHandler`**

- Implement `DocumentController` wiring `DocumentService` and `CallerContextResolver`, adding concise `@Tag(name = "Documents")` and `@Operation` OpenAPI annotations.
- Implement `GlobalExceptionHandler` returning `ResponseEntity<ProblemDetail>` across all mapped exceptions (`400`, `403`, `404`, `409`, `413`, `415`, `502`).

- [ ] **Step 4: Run full automated test suite to verify pass**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: PASS (`BUILD SUCCESS`, all unit + Postgres/MinIO Testcontainers tests green).

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/documind/controller backend/src/test/java/com/documind/support/MinioTestcontainersConfig.java backend/src/test/java/com/documind/controller/DocumentCrudAndMinioStreamingIntegrationTest.java
git commit -m "feat(api): add DocumentController, ProblemDetail advice, and 40MiB MinIO Testcontainers suite (DA-7)"
```

---

### Task 5: Live Day-3 `~40 MiB` MinIO Smoke Verification (`-Dsmoke=true`)

**Files:**
- Modify: `backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java`

**Interfaces:**
- Consumes: Live Docker services `documind-postgres` (`localhost:5432`) and `documind-minio` (`localhost:9000` / Console `localhost:9001`, bucket `documind-documents`), `DocumentController`, and `S3Client`.

- [ ] **Step 1: Add live `~40 MiB` PDF streaming + `409` duplicate + `204` cleanup smoke test**

Add `@Test void liveMinioStreams40MbPdfRejectsDuplicateAndCleansUp()` to `Day1InfrastructureAndAiSmokeTest` (injecting `@LocalServerPort int port` and `@Autowired S3Client s3Client`):
- Streams a `40 MiB` (`41_943_040` bytes) generated `%PDF-1.4` payload to `http://localhost:{port}/api/v1/documents/upload` (`departmentId = 11111111-1111-1111-1111-111111111111`), asserting `202 Accepted`, querying `SELECT storage_key FROM documents WHERE id = ?` via `jdbcTemplate`, and verifying `s3Client.headObject` on `documind-documents` reports `41_943_040L` bytes.
- Streams a small Vietnamese `.md` file twice: asserts first upload returns `202 Accepted` in `< 300ms` and second upload returns `409 Conflict`.
- Calls `DELETE /api/v1/documents/{id}` for both smoke documents (`204 No Content`) in a `finally` block so `documind-postgres` and `documind-minio` stay clean.

- [ ] **Step 2: Ensure Docker services are healthy and execute live smoke test**

Run: `docker ps --filter "name=documind-"; Set-Location backend; .\mvnw.cmd test "-Dsmoke=true" "-Dtest=Day1InfrastructureAndAiSmokeTest#liveMinioStreams40MbPdfRejectsDuplicateAndCleansUp"`
Expected: PASS (`202 Accepted` for `40 MiB` PDF on live MinIO, `< 300ms` standard upload, `409 Conflict` duplicate, `204 No Content` cleanup).

- [ ] **Step 3: Run full `.\mvnw.cmd test` regression gate and commit**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: PASS (`BUILD SUCCESS`).

```powershell
git add backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java
git commit -m "test(smoke): verify live 40MiB MinIO streaming upload, 409 deduplication, and cleanup (DA-7)"
```
