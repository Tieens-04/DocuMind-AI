# DA-7 Design Spec — Day 3: MinIO S3 Streaming, SHA-256 Deduplication & Document CRUD API

- **Jira Issue:** `DA-7` (Parent Epic: `DA-1`, Sprint 1)
- **Date:** 2026-11-16
- **Prerequisites:** `DA-5` (Docker MinIO `documind-documents`, `S3Client` + `S3Presigner`), `DA-6` (Flyway `V1..V3`, `DocumentEntity`, `uq_documents_department_hash`, `DepartmentRepository`, `AuditLogRepository`)

---

## 1. Goal & Acceptance Criteria

Implement zero-Heap-bloat MinIO S3 streaming (`<= 50 MiB`), single-pass `SHA-256` hashing with per-department deduplication (`409 Conflict`), bounded `64 KiB` Apache Tika real-MIME inspection (`.pdf`, `.docx`, `.txt`, `.md`), and the complete Document CRUD REST API (`/api/v1/documents`) ready for downstream extraction (`DA-8`), Virtual Thread async embedding (`DA-9`), JWT RBAC (`DA-12`/`DA-13`), versioned re-indexing & preview (`DA-17`), and 2-tier cache invalidation (`DA-19`).

### Acceptance Criteria
1. **Single-Pass S3 Streaming (`<= 50 MiB`, Heap-Safe):** Uploads up to `52,428,800` bytes (`50 MiB`) stream directly to MinIO (`documind-documents`) via AWS SDK v2 `RequestBody.fromInputStream(...)` wrapped in a single `DigestInputStream("SHA-256")` pass without materializing full file `byte[]` buffers in JVM Heap RAM. Uploading a ~`40 MiB` PDF succeeds on MinIO (`localhost:9000` / Console `localhost:9001`) with bounded Heap allocation.
2. **Fast `202 Accepted` & Race-Safe `409 Conflict` Deduplication:** Standard document uploads persist `DocumentEntity(status = PENDING)` and return `202 Accepted` in `< 300ms`. Re-uploading an identical `SHA-256` payload within the same `department_id` returns `409 Conflict` (`ProblemDetail` including `existingDocumentId`) and deletes the staged MinIO object so zero orphan objects remain; uploading the same file to a different department succeeds (`202 Accepted`).
3. **Real Content MIME Validation (Anti-Spoofing):** Only `.pdf`, `.docx`, `.txt`, and `.md` are accepted. Apache Tika `Detector` inspects a bounded `64 KiB` (`mark`/`reset`) prefix stream — plus OOXML ZIP entry verification for `.docx` and binary/null-byte rejection for `.txt`/`.md` — returning `415 Unsupported Media Type` on unsupported extensions or spoofed files (e.g., `.png` renamed to `.pdf`, arbitrary `.zip` renamed to `.docx`, `.exe` renamed to `.txt`).
4. **Complete CRUD & Governance Integration:**
   - `POST /api/v1/documents/upload` -> `202 Accepted` (`DocumentResponse`, `status = PENDING`, increments `departments.cache_version`, logs `AuditLog(DOC_UPLOAD)`, publishes `DocumentUploadedEvent` for `DA-9`).
   - `GET /api/v1/documents` -> `200 OK` (`PageResponse<DocumentResponse>` with `page`, `size`, `departmentId`, `status`, `accessLevel` filters).
   - `GET /api/v1/documents/{id}/status` -> `200 OK` (`DocumentStatusResponse` for realtime polling).
   - `DELETE /api/v1/documents/{id}` -> `204 No Content` (hard-deletes `documents` cascading `document_chunks`, increments `departments.cache_version`, logs `AuditLog(DOC_DELETE)`, and deletes the MinIO object strictly **after** DB commit).
   - `MinioStorageService.generatePresignedGetUrl(storageKey, expiry)` generates valid `15`-minute inline GET URLs (`response-content-disposition: inline`), and `openStream(storageKey)` exposes raw S3 streams for `DA-8`.

---

## 2. Architectural Decisions

### 2.1 Two-Phase Non-Transactional S3 I/O + Short `TransactionTemplate` Commit
- **Problem:** Wrapping `s3Client.putObject(...)` inside `@Transactional` holds a HikariCP PostgreSQL connection throughout a 40–50 MiB network stream. Under `20` concurrent Virtual Thread uploads (`DA-29` target), a 10-connection pool would starve all chat and auth traffic.
- **Decision:** Execute S3 streaming **outside** any database transaction, then open a `< 10ms` `TransactionTemplate` block solely for atomic DB persistence, `cache_version` increment, `AuditLog` insertion, and domain event publication:
  1. **Pre-check (Read-only, instant connection release):** Resolve caller context (`departmentId`, `userId`, `ipAddress`) and verify referenced `DepartmentEntity` (and optional `UserEntity`) exist (`404 Not Found` if missing).
  2. **Phase 1 — Stream & Hash (Zero DB Connections Held):** Validate filename/size (`0 < size <= 52,428,800`), inspect the first `64 KiB` via `BufferedInputStream` (`mark`/`reset`) + Tika/OOXML checks, generate a unique `uploadUuid = UUID.randomUUID()`, and stream once through `HashingBoundedInputStream` (`DigestInputStream("SHA-256")` + byte-count guard) into MinIO at `departments/{departmentId}/{uploadUuid}/v1/{sanitizedFileName}`.
  3. **Phase 2 — Short Atomic DB Commit (`TransactionTemplate`):** Check `findByDepartmentIdAndFileHash`, persist `DocumentEntity(storageKey = ..., status = PENDING)` (keeping JPA's `@GeneratedValue(strategy = GenerationType.UUID)` untouched), bump `departments.cache_version`, write `AuditLogEntity(DOC_UPLOAD)`, and publish `DocumentUploadedEvent`.
  4. **Compensating S3 Cleanup:** If Phase 2 throws `DuplicateDocumentException` (`409`), `DataIntegrityViolationException` (concurrent duplicate race resolved into `DuplicateDocumentException`), or any runtime error, immediately call `minioStorageService.deleteObjectQuietly(storageKey)` before propagating the exception. Because every upload attempt uses its own `uploadUuid` in `storageKey`, two concurrent uploads of the same file never overwrite each other's S3 object, and the losing racer safely deletes only its own staged object.

### 2.2 Bounded `64 KiB` Tika MIME & OOXML Container Validation
- **Why not `TikaInputStream.get(InputStream)`:** `TikaInputStream` spools entire ZIP/OOXML streams (`50 MiB`) to temporary files when inspecting ZIP central directories at EOF, defeating single-pass zero-spool streaming.
- **Bounded Prefix Strategy (`FileValidationService`):**
  - Wrap the upload `InputStream` in a `BufferedInputStream` (`65,536`-byte buffer), call `mark(65_536)`, read up to `65,536` bytes (`headerBytes`), inspect, and call `reset()`.
  - Allowed lowercase extensions: `pdf`, `docx`, `txt`, `md` (any other extension or missing extension -> `415 Unsupported Media Type`).
  - Size bounds: `0` bytes -> `400 Bad Request`; `> 52,428,800` bytes (`50 MiB`) -> `413 Payload Too Large`.
  - Extension-to-content rules (using Tika `DefaultDetector` on `headerBytes` **without** filename hints so extension spoofing cannot influence Tika):
    - **`pdf`:** Tika media type must equal `application/pdf` and header must start with `%PDF-`. Stored `mimeType = "application/pdf"`.
    - **`docx`:** Header must start with `PK\x03\x04`, Tika media type must be `application/zip`, `application/x-tika-ooxml`, or `application/vnd.openxmlformats-officedocument.wordprocessingml.document`, AND iterating local entries via `ZipInputStream(new ByteArrayInputStream(headerBytes))` must encounter at least one OOXML entry (`[Content_Types].xml`, `_rels/.rels`, or prefix `word/`, `docProps/`) with zero `..` path-traversal segments. Stored `mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"`.
    - **`txt` / `md`:** Header must contain zero `0x00` null bytes, and Tika media type must have `type = "text"` (rejecting `application/pdf`, `image/*`, `application/zip`, `application/x-msdownload`, `application/octet-stream` binary signatures, etc.). Stored `mimeType = "text/plain"` for `txt` and `"text/markdown"` for `md`.

### 2.3 Pre-JWT `CallerContextResolver` Seam (`DA-7` -> `DA-12`/`DA-13`)
- Defines `CallerContext(UUID departmentId, UUID userId, boolean explicitDepartment, String ipAddress)` and interface `CallerContextResolver`.
- `DevFallbackCallerContextResolver` (active until `DA-12` introduces `SecurityContextCallerContextResolver`):
  - **Department resolution priority:** request parameter `departmentId` -> header `X-Department-Id` -> (when active profiles do NOT include `prod`) seeded `V3` `IT` department `11111111-1111-1111-1111-111111111111`. In `prod` with neither parameter nor header, throws `InvalidDocumentRequestException` (`400 Bad Request`).
  - **User resolution priority:** header `X-User-Id` -> (when non-`prod` and seeded Admin `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` exists) seeded Admin UUID, else `null`.
  - **Ownership guard (`GET /{id}/status` & `DELETE /{id}`):** When caller explicitly passes `X-Department-Id` (or `departmentId`) that differs from `document.getDepartmentId()`:
    - `DELETE /{id}` -> `403 Forbidden` (`DocumentAccessDeniedException`).
    - `GET /{id}/status` -> allowed if `document.getAccessLevel() == AccessLevel.PUBLIC`, otherwise `403 Forbidden`.

### 2.4 Post-Commit MinIO Deletion & Presigned Inline URLs
- **Delete (`DELETE /api/v1/documents/{id}`):** Inside `TransactionTemplate`, verify existence (`404`) and department ownership (`403`), increment `departments.cache_version` (`incrementAllCacheVersions()` if `PUBLIC`, else `incrementCacheVersion(deptId)`), insert `AuditLogEntity(DOC_DELETE)`, delete `DocumentEntity` (`ON DELETE CASCADE` purges `document_chunks`), and register `TransactionSynchronization.afterCommit(() -> minioStorageService.deleteObjectQuietly(storageKey))`. If DB rolls back, the MinIO object stays intact.
- **Presigned GET URL (`MinioStorageService.generatePresignedGetUrl`):** Uses `S3Presigner.presignGetObject` with default `15`-minute TTL (`StorageProperties.presignedExpiry`) and `GetObjectRequest.responseContentDisposition("inline; filename=\"" + asciiFallbackName + "\"")` so browsers open PDFs inline (`#page=X` in `DA-17`) rather than forcing file downloads.
- **Per-Request Upload Timeout:** Keeps `MinioConfig` global client timeouts at `15s` / `5s` (preserving fast Actuator health checks and existing `MinioHealthIndicatorTest`), while overriding `AwsRequestOverrideConfiguration` on `PutObjectRequest` with `apiCallTimeout = 60s` and `apiCallAttemptTimeout = 60s` (`StorageProperties.uploadTimeout`) to accommodate 50 MiB streams.

---

## 3. Package Structure & Component Interfaces

```text
backend/src/main/java/com/documind/
├── controller/
│   ├── DocumentController.java
│   ├── dto/
│   │   ├── DocumentResponse.java
│   │   ├── DocumentStatusResponse.java
│   │   └── PageResponse.java
│   └── advice/
│       └── GlobalExceptionHandler.java
├── domain/
│   └── event/
│       └── DocumentUploadedEvent.java
├── exception/
│   ├── DocumentAccessDeniedException.java
│   ├── DocumentNotFoundException.java
│   ├── DocumentStorageException.java
│   ├── DocumentTooLargeException.java
│   ├── DuplicateDocumentException.java
│   ├── InvalidDocumentRequestException.java
│   ├── ResourceNotFoundException.java
│   └── UnsupportedDocumentTypeException.java
├── infrastructure/
│   └── storage/
│       ├── HashingBoundedInputStream.java
│       ├── MinioConfig.java                    # unchanged 15s/5s defaults
│       ├── MinioHealthIndicator.java           # unchanged
│       ├── MinioStorageService.java
│       └── StorageProperties.java              # + uploadTimeout (60s), presignedExpiry (15m)
├── repository/
│   └── DocumentRepository.java                 # + findByDepartmentIdAndFileHash + Specification/filtered Page query
└── service/
    └── document/
        ├── CallerContext.java
        ├── CallerContextResolver.java
        ├── DevFallbackCallerContextResolver.java
        ├── DocumentService.java
        ├── FileValidationResult.java
        └── FileValidationService.java
```

---

## 4. REST API & Error Contracts

### 4.1 Endpoints (`/api/v1/documents`)

| Method & Path | Query / Multipart / Header Inputs | Success Status & Body |
| :--- | :--- | :--- |
| `POST /api/v1/documents/upload` | `multipart/form-data`: `file` (required), `accessLevel` (optional, default `DEPARTMENT`), `departmentId` (optional `UUID`); headers `X-Department-Id`, `X-User-Id` (optional) | `202 Accepted` — `DocumentResponse` (`status = "PENDING"`, `fileHash`, `fileSizeBytes`, `mimeType`, `fileExtension`, `currentVersion = 1`, `chunkCount = 0`) |
| `GET /api/v1/documents` | Query: `departmentId` (optional `UUID`), `status` (optional `DocumentStatus`), `accessLevel` (optional `AccessLevel`), `page` (default `0`), `size` (default `20`, max `100`); header `X-Department-Id` (optional) | `200 OK` — `PageResponse<DocumentResponse>` sorted by `createdAt DESC` |
| `GET /api/v1/documents/{id}/status` | Path: `id` (`UUID`); optional header `X-Department-Id` / query `departmentId` | `200 OK` — `DocumentStatusResponse(id, status, chunkCount, currentVersion, errorMessage, updatedAt)` |
| `DELETE /api/v1/documents/{id}` | Path: `id` (`UUID`); optional header `X-Department-Id` / query `departmentId`, `X-User-Id` | `204 No Content` |

### 4.2 RFC 7807 `ProblemDetail` Error Mapping (`GlobalExceptionHandler`)

| Condition | Exception | HTTP Status | Extra `ProblemDetail` Properties |
| :--- | :--- | :--- | :--- |
| Missing/blank file, `0`-byte file, invalid UUID/enum/page size, missing `departmentId` in `prod` | `InvalidDocumentRequestException` / `MethodArgumentTypeMismatchException` / `MissingServletRequestPartException` | `400 Bad Request` | `errorCode = "INVALID_DOCUMENT_REQUEST"` |
| Cross-department delete or non-`PUBLIC` status lookup | `DocumentAccessDeniedException` | `403 Forbidden` | `errorCode = "DOCUMENT_ACCESS_DENIED"` |
| Unknown `documentId`, `departmentId`, or `userId` | `DocumentNotFoundException` / `ResourceNotFoundException` | `404 Not Found` | `errorCode = "RESOURCE_NOT_FOUND"` |
| Duplicate `(department_id, file_hash)` | `DuplicateDocumentException` | `409 Conflict` | `errorCode = "DUPLICATE_DOCUMENT"`, `existingDocumentId`, `fileHash` |
| File `> 52,428,800` bytes (`50 MiB`) | `DocumentTooLargeException` / `MaxUploadSizeExceededException` | `413 Payload Too Large` | `errorCode = "DOCUMENT_TOO_LARGE"`, `maxSizeBytes = 52428800` |
| Disallowed extension or spoofed Tika MIME / invalid `.docx` ZIP | `UnsupportedDocumentTypeException` | `415 Unsupported Media Type` | `errorCode = "UNSUPPORTED_DOCUMENT_TYPE"`, `detectedMimeType` |
| MinIO S3 unreachable / I/O failure | `DocumentStorageException` | `502 Bad Gateway` | `errorCode = "STORAGE_UNAVAILABLE"` |

### 4.3 Configuration Additions (`application.yml`)

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 52MB
      file-size-threshold: 2MB

documind:
  storage:
    upload-timeout: 60s
    presigned-expiry: 15m
```

---

## 5. Testing & Verification Strategy

1. **Unit Tests (Fast, Zero Containers):**
   - `FileValidationServiceTest`: verifies `.pdf`, `.docx` (real minimal OOXML ZIP entries), `.txt` (Vietnamese UTF-8), `.md` (Vietnamese Markdown), plus rejection of `0`-byte streams (`400`), `> 50 MiB` (`413`), `.exe`/`.png` extensions (`415`), PNG bytes disguised as `report.pdf` (`415`), plain `.zip` without OOXML entries disguised as `spec.docx` (`415`), ZIP slip `../` entry in `.docx` (`415`), null-byte binary disguised as `notes.txt` (`415`), and verifies `InputStream` position resets cleanly to byte `0`.
   - `HashingBoundedInputStreamTest`: verifies exact single-pass `SHA-256` hex match against `MessageDigest`, byte counting, and mid-stream `> 50 MiB` abort (`DocumentTooLargeException`) when declared length is forged or exceeded.
   - `MinioStorageServiceTest`: verifies per-request `60s` timeout override on `PutObjectRequest`, `inline` `Content-Disposition` on presigned GET URLs, and safe `deleteObjectQuietly` behavior.
2. **Testcontainers Integration Suite (`DocumentCrudAndMinioStreamingIntegrationTest`):**
   - Extends test infrastructure (`StorageAndPostgresTestcontainersConfig`) with both `pgvector/pgvector:pg16` and a real **MinIO container** (auto-creating bucket `documind-documents` before tests).
   - Verifies end-to-end `POST /upload` (`202 Accepted`, `< 300ms` on standard PDF/MD, `DocumentUploadedEvent` captured after commit, `AuditLog(DOC_UPLOAD)` written, `departments.cache_version` incremented), Presigned GET URL round-trip via `HttpClient` returning exact uploaded bytes with `inline` header, `409 Conflict` on duplicate upload in `IT` **with verification via `s3Client.listObjectsV2` that the duplicate's staged S3 object was deleted**, `202 Accepted` when uploading the same file to `HR`, filtered `GET /api/v1/documents` pagination, `GET /api/v1/documents/{id}/status` (`200` + `403` cross-department check), and `DELETE /api/v1/documents/{id}` (`204`, cascades `document_chunks`, deletes MinIO object post-commit).
   - **~40 MiB Streaming & Heap Safety Test:** Streams a deterministically generated `40 MiB` valid `%PDF-1.4` payload on-the-fly via `java.net.http.HttpClient` `BodyPublishers.ofInputStream(...)` against `SpringBootTest.WebEnvironment.RANDOM_PORT` (avoiding `MockMultipartFile`'s client-side `byte[]` allocation), asserting `202 Accepted`, exact `41,943,040` byte size on MinIO `headObject`, and bounded post-GC Heap delta (`< 35 MiB` net retained growth).
3. **Live Smoke Verification (`Day1InfrastructureAndAiSmokeTest`, `-Dsmoke=true`):**
   - Streams a generated `~40 MiB` PDF to the live backend connected to `documind-minio` (`localhost:9000` / Console `9001`) and `documind-postgres` (`localhost:5432`), asserts `202 Accepted`, verifies duplicate upload returns `409 Conflict`, verifies `headObject` on `documind-documents`, and deletes the test document via `DELETE /api/v1/documents/{id}` leaving the live environment clean.
