# DA-6 Database Schema & HNSW Vector Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete 9-table PostgreSQL 16 + `pgvector` (`1024d`) schema via Flyway (`V1..V3`), `HNSW`/`GIN` indexes, Domain Enums, 7 JPA Entities + Repositories, 2 `JdbcClient` Vector Repositories, and automated Testcontainers + live `EXPLAIN` HNSW verification.

**Architecture:** Flyway manages `V1__init_relational_schema.sql` (7 relational tables) and `V2__init_pgvector_and_semantic_cache.sql` (`document_chunks` and `semantic_cache` with `vector(1024)`, HNSW `m=16, ef_construction=64`, GIN JSONB index, and a `BEFORE INSERT OR UPDATE` trigger synchronizing `document_id` `ON DELETE CASCADE` from Spring AI `PgVectorStore` metadata), while dev seed data (`V3__seed_dev_data.sql`) is isolated under `classpath:db/seed` (excluded in `prod`). JPA (`ddl-auto: validate`) maps only the 7 relational tables; `PgVectorStore` and `JdbcClient` repositories handle all `1024`-dim vector operations without loading embeddings into the Hibernate persistence context.

**Tech Stack:** Java 21, Spring Boot `3.4.5`, Spring Data JPA (Hibernate 6.6), Spring AI `1.0.3` GA (`PgVectorStore`), Flyway, PostgreSQL 16 (`pgvector/pgvector:pg16`), Testcontainers (`1.20.x` via Spring Boot BOM), Maven Wrapper (`.\mvnw.cmd`).

**Spec:** `docs/superpowers/specs/2026-11-15-da6-database-schema-design.md`

## Global Constraints

- **Host Coexistence (Never Stop FlintFlow):** Never stop or touch `flintflow-*` containers; live Postgres runs on `localhost:5432`, Redis on `6380`, MinIO on `9000`/`9001`, Backend on `8081`.
- **Maven Wrapper Only:** Always run `.\mvnw.cmd` from `backend/` in PowerShell (never global `mvn`).
- **Exact 9-Table Contract:** `departments`, `users`, `refresh_tokens`, `documents`, `chat_sessions`, `chat_messages`, `audit_logs`, `document_chunks`, `semantic_cache` — no extra helper tables.
- **Canonical Enums (`VARCHAR` + `CHECK`):** `Role` (`SYSTEM_ADMIN`, `DEPARTMENT_MANAGER`, `STANDARD_EMPLOYEE`), `AccessLevel` (`PUBLIC`, `DEPARTMENT`, `CONFIDENTIAL`), `DocumentStatus` (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`), `MessageRole` (`USER`, `ASSISTANT`), `CacheTier` (`NONE`, `L1`, `L2`), `AuditActionType` (`CHAT_QUERY`, `DOC_UPLOAD`, `DOC_REINDEX`, `DOC_DELETE`, `AUTH_LOGIN`, `AUTH_LOGOUT`, `RATE_LIMITED`).
- **Dual Startup Validation:** Keep `spring.ai.vectorstore.pgvector.initialize-schema=false`, enable `spring.ai.vectorstore.pgvector.schema-validation=true` and `spring.jpa.hibernate.ddl-auto=validate` (with `spring.flyway.baseline-version=0`).

## Review Focus

1. **Flyway `baseline-on-migrate: true` skipping `V1` if `baseline-version` defaults to `1`:** Pin `spring.flyway.baseline-version: 0` in `application.yml` and assert `V1`, `V2`, and `V3` all record `success = true` in `flyway_schema_history` (`FlywaySchemaAndProdIsolationIntegrationTest`).
2. **`prod` profile leaking dev seed credentials (`DocuMind@2026!`):** Configure `application-prod.yml` with `spring.flyway.locations: classpath:db/migration` and assert `users` count is `0` when migrating under `prod` (`FlywaySchemaAndProdIsolationIntegrationTest`).
3. **Duplicate `SHA-256` within the same department vs. across departments:** `uq_documents_department_hash` must throw `DataIntegrityViolationException` for duplicate `(department_id, file_hash)` in `HR` while permitting the exact same `file_hash` in `SALES` (`RelationalPersistenceIntegrationTest`).
4. **Spring AI `PgVectorStore.add()` compatibility vs. orphan-chunk prevention:** `PgVectorStore` only inserts `(id, content, metadata, embedding)`; assert `VectorStore.add()` succeeds via `trg_sync_document_chunk_metadata`, rejects chunks missing `metadata.document_id`, and cascades deletion to `0` chunks when `DocumentEntity` is deleted (`VectorStoreAndHnswIndexIntegrationTest`).
5. **PostgreSQL planner choosing `Seq Scan` on small tables during `EXPLAIN`:** Verify `Index Scan using idx_document_chunks_embedding_hnsw` and `Index Scan using idx_semantic_cache_embedding_hnsw` using `ORDER BY ... <=> :vec::vector LIMIT k` with `SET LOCAL enable_seqscan = off` inside a `@Transactional` test (`VectorStoreAndHnswIndexIntegrationTest`).

---

### Task 1: Testcontainers Harness, Flyway `V1..V3` SQL Migrations & `prod` Seed Isolation

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-prod.yml`
- Delete: `backend/src/main/resources/db/migration/.gitkeep`
- Create: `backend/src/main/resources/db/migration/V1__init_relational_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__init_pgvector_and_semantic_cache.sql`
- Create: `backend/src/main/resources/db/seed/V3__seed_dev_data.sql`
- Modify: `.env.example`
- Test: `backend/src/test/java/com/documind/support/PostgresTestcontainersConfig.java`
- Test: `backend/src/test/java/com/documind/infrastructure/db/FlywaySchemaAndProdIsolationIntegrationTest.java`

**Interfaces:**
- Produces:
  - `PostgresTestcontainersConfig` (`@TestConfiguration(proxyBeanMethods = false)`): `@Bean @ServiceConnection PostgreSQLContainer<?> postgresContainer()` (`DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")`) and `@Bean @Primary EmbeddingModel deterministicTestEmbeddingModel()` returning normalized `float[1024]`.
  - All 9 PostgreSQL tables, constraints, `set_updated_at_timestamp()` trigger, `trg_sync_document_chunk_metadata` trigger, HNSW indexes (`idx_document_chunks_embedding_hnsw`, `idx_semantic_cache_embedding_hnsw`), GIN index (`idx_document_chunks_metadata_gin`), and `V3` seeded rows (`IT`, `HR`, `SALES` + `admin@documind.ai`, `manager.hr@documind.ai`, `employee.sales@documind.ai`).

- [ ] **Step 1: Add Testcontainers dependencies to `backend/pom.xml` and write failing `FlywaySchemaAndProdIsolationIntegrationTest`**

Add `spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, and `org.testcontainers:postgresql` (`<scope>test</scope>`) to `backend/pom.xml`. Create `PostgresTestcontainersConfig.java` and `FlywaySchemaAndProdIsolationIntegrationTest.java` asserting:
```java
@Test
void appliesV1ToV3AllNineTablesAndSeededUsersWithBcryptHashes() {
    List<String> versions = jdbcTemplate.queryForList(
        "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
        String.class);
    assertThat(versions).containsExactly("1", "2", "3");

    List<String> tables = jdbcTemplate.queryForList(
        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
        String.class);
    assertThat(tables).contains(
        "departments", "users", "refresh_tokens", "documents",
        "chat_sessions", "chat_messages", "audit_logs",
        "document_chunks", "semantic_cache");

    Integer deptCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM departments", Integer.class);
    assertThat(deptCount).isEqualTo(3);

    List<String> hashes = jdbcTemplate.queryForList("SELECT password_hash FROM users ORDER BY email", String.class);
    assertThat(hashes).hasSize(3).allSatisfy(h -> assertThat(h).startsWith("$2a$10$").hasSize(60));
}

@Test
void rejectsInvalidCheckConstraintsOnUsersAndDocuments() {
    assertThatThrownBy(() -> jdbcTemplate.update("""
        INSERT INTO users (department_id, email, password_hash, full_name, role)
        VALUES ('11111111-1111-1111-1111-111111111111', 'bad@documind.ai', 'hash', 'Bad Role', 'SUPERUSER')
        """)).isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(() -> jdbcTemplate.update("""
        INSERT INTO documents (department_id, original_name, storage_key, file_extension, mime_type,
                               file_size_bytes, file_hash, access_level, status)
        VALUES ('11111111-1111-1111-1111-111111111111', 'huge.pdf', 'it/huge.pdf', 'pdf', 'application/pdf',
                52428801, repeat('a', 64), 'DEPARTMENT', 'PENDING')
        """)).isInstanceOf(DataIntegrityViolationException.class);
}

@Test
void prodFlywayLocationsExcludeSeedMigrationV3() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application-prod.yml"));
    Properties prodProps = yaml.getObject();
    assertThat(prodProps).isNotNull();
    String prodLocations = prodProps.getProperty("spring.flyway.locations");
    assertThat(prodLocations).isEqualTo("classpath:db/migration");

    jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS prod_verify");
    Flyway prodFlyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas("prod_verify")
        .defaultSchema("prod_verify")
        .locations(prodLocations.split(","))
        .load();
    prodFlyway.migrate();

    Integer prodUsers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM prod_verify.users", Integer.class);
    assertThat(prodUsers).isZero();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=FlywaySchemaAndProdIsolationIntegrationTest`
Expected: FAIL (`V1`, `V2`, `V3` and `application-prod.yml` do not exist yet).

- [ ] **Step 3: Write `V1`, `V2`, `V3` SQL migrations, `application-prod.yml`, `.env.example` seed note, and update `application.yml`**

1. Remove `backend/src/main/resources/db/migration/.gitkeep`.
2. Write `V1__init_relational_schema.sql` implementing `set_updated_at_timestamp()` and the exact 7 relational tables, named constraints, and B-Tree indexes from Spec Section 3.1.
3. Write `V2__init_pgvector_and_semantic_cache.sql` enabling `CREATE EXTENSION IF NOT EXISTS vector;`, creating `document_chunks` + `semantic_cache`, HNSW (`WITH (m = 16, ef_construction = 64)`) and GIN (`USING gin (metadata)`) indexes from Spec Section 3.2, and the `BEFORE INSERT OR UPDATE` trigger function:
```sql
CREATE OR REPLACE FUNCTION sync_document_chunk_metadata()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.metadata IS NOT NULL THEN
        IF NULLIF(NEW.metadata->>'document_id', '') IS NOT NULL THEN
            NEW.document_id := (NEW.metadata->>'document_id')::uuid;
        END IF;
        IF NULLIF(NEW.metadata->>'doc_version', '') IS NOT NULL THEN
            NEW.doc_version := (NEW.metadata->>'doc_version')::int;
        END IF;
        IF NULLIF(NEW.metadata->>'chunk_index', '') IS NOT NULL THEN
            NEW.chunk_index := (NEW.metadata->>'chunk_index')::int;
        END IF;
        IF NULLIF(NEW.metadata->>'page_number', '') IS NOT NULL THEN
            NEW.page_number := (NEW.metadata->>'page_number')::int;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_document_chunk_metadata
BEFORE INSERT OR UPDATE ON document_chunks
FOR EACH ROW EXECUTE FUNCTION sync_document_chunk_metadata();
```
4. Note for `prod_verify` schema isolation in `V2`: qualify `vector(1024)` / `vector_cosine_ops` cleanly or place extension in `public` (`SET search_path` includes `public` by default; in `prodFlywayLocationsExcludeSeedMigrationV3` configure `.schemas("prod_verify", "public")` or `CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public` and keep `public` in search path).
5. Write `backend/src/main/resources/db/seed/V3__seed_dev_data.sql` with the exact UUIDs, codes, emails, roles, and BCrypt hash `$2a$10$q6.0PtDMsJDIKJWkOmdXFefwSPRiXyK4dWz5JIOMMZUEt/e35s8yi` (`ON CONFLICT DO NOTHING`) from Spec Section 3.3, and append a comment block in `.env.example` documenting the 3 dev accounts (`DocuMind@2026!`).
6. Update `backend/src/main/resources/application.yml` (`spring.flyway.locations: classpath:db/migration,classpath:db/seed`, `spring.flyway.baseline-version: 0`, `spring.ai.vectorstore.pgvector.schema-validation: true`) and create `backend/src/main/resources/application-prod.yml` (`spring.flyway.locations: classpath:db/migration`).

- [ ] **Step 4: Run test suite to verify Task 1 passes**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=FlywaySchemaAndProdIsolationIntegrationTest,DocuMindApplicationTests`
Expected: `BUILD SUCCESS`, both Testcontainers migration checks and offline context tests pass.

- [ ] **Step 5: Commit Task 1**

```powershell
git add .env.example backend/pom.xml backend/src/
git commit -m "feat(db): add Flyway V1..V3 schema, HNSW/GIN indexes, and prod seed isolation (DA-6)"
```

---

### Task 2: Domain Enums, 7 JPA Entities (`ddl-auto: validate`), and Spring Data JPA Repositories

**Files:**
- Create: `backend/src/main/java/com/documind/domain/enums/Role.java`
- Create: `backend/src/main/java/com/documind/domain/enums/AccessLevel.java`
- Create: `backend/src/main/java/com/documind/domain/enums/DocumentStatus.java`
- Create: `backend/src/main/java/com/documind/domain/enums/MessageRole.java`
- Create: `backend/src/main/java/com/documind/domain/enums/CacheTier.java`
- Create: `backend/src/main/java/com/documind/domain/enums/AuditActionType.java`
- Create: `backend/src/main/java/com/documind/domain/model/CitationPayload.java`
- Create: `backend/src/main/java/com/documind/domain/entity/DepartmentEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/UserEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/RefreshTokenEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/DocumentEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/ChatSessionEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/ChatMessageEntity.java`
- Create: `backend/src/main/java/com/documind/domain/entity/AuditLogEntity.java`
- Create: `backend/src/main/java/com/documind/repository/DepartmentRepository.java`
- Create: `backend/src/main/java/com/documind/repository/UserRepository.java`
- Create: `backend/src/main/java/com/documind/repository/RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/documind/repository/DocumentRepository.java`
- Create: `backend/src/main/java/com/documind/repository/ChatSessionRepository.java`
- Create: `backend/src/main/java/com/documind/repository/ChatMessageRepository.java`
- Create: `backend/src/main/java/com/documind/repository/AuditLogRepository.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/documind/repository/RelationalPersistenceIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresTestcontainersConfig`, Flyway tables `departments`, `users`, `refresh_tokens`, `documents`, `chat_sessions`, `chat_messages`, `audit_logs`.
- Produces:
  - `CitationPayload(UUID documentId, String fileName, Integer pageNumber, double similarityScore, String excerpt)`
  - `DepartmentRepository`:
    - `Optional<DepartmentEntity> findByCodeIgnoreCase(String code)`
    - `@Modifying(clearAutomatically = true) int incrementCacheVersion(@Param("id") UUID id)`
    - `@Modifying(clearAutomatically = true) int incrementAllCacheVersions()`
    - `long sumCacheVersions()`
  - `UserRepository`:
    - `@EntityGraph(attributePaths = "department") Optional<UserEntity> findByEmailIgnoreCase(String email)`
    - `boolean existsByEmailIgnoreCase(String email)`
  - `RefreshTokenRepository`:
    - `Optional<RefreshTokenEntity> findByTokenHash(String tokenHash)`
    - `@Modifying(clearAutomatically = true) int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("revokedAt") OffsetDateTime revokedAt)`
  - `DocumentRepository`:
    - `boolean existsByDepartmentIdAndFileHash(UUID departmentId, String fileHash)`
    - `List<DocumentEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId)`
  - `ChatSessionRepository`:
    - `List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(UUID userId)`
    - `Optional<ChatSessionEntity> findByIdAndUserId(UUID id, UUID userId)`
  - `ChatMessageRepository`:
    - `List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(UUID sessionId)`
    - `List<ChatMessageEntity> findTop6BySessionIdOrderByCreatedAtDesc(UUID sessionId)`
  - `AuditLogRepository`:
    - `Page<AuditLogEntity> findByDepartmentIdOrderByCreatedAtDesc(UUID departmentId, Pageable pageable)`
    - `Page<AuditLogEntity> findByActionTypeOrderByCreatedAtDesc(AuditActionType actionType, Pageable pageable)`

- [ ] **Step 1: Write failing `RelationalPersistenceIntegrationTest`**

Create `RelationalPersistenceIntegrationTest` (`@SpringBootTest`, `@Import(PostgresTestcontainersConfig.class)`, `@Transactional`) asserting:
1. Context boots with `spring.jpa.hibernate.ddl-auto=validate` (`assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate")`).
2. `userRepository.findByEmailIgnoreCase("MANAGER.HR@documind.ai")` returns `Role.DEPARTMENT_MANAGER` with eagerly fetched `department.getCode() == "HR"`.
3. `departmentRepository.incrementCacheVersion(hrId)` bumps only `HR` (`1 -> 2`), `incrementAllCacheVersions()` bumps all 3 departments (`IT=2, HR=3, SALES=2`), and `sumCacheVersions()` equals `7L`.
4. `DocumentRepository` rejects duplicate `(department_id, file_hash)` within `HR` via `saveAndFlush` (`DataIntegrityViolationException`), while saving the identical `file_hash` under `SALES` succeeds.
5. `ChatMessageEntity` persists and reloads `List<CitationPayload>` via `@JdbcTypeCode(SqlTypes.JSON)` with accented Vietnamese `excerpt` intact.

- [ ] **Step 2: Run test to verify it fails**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=RelationalPersistenceIntegrationTest`
Expected: FAIL (compile error — enums, entities, and repositories do not exist yet).

- [ ] **Step 3: Implement Enums, `CitationPayload`, 7 JPA Entities, 7 `JpaRepository` interfaces, and enable `ddl-auto: validate`**

- Map timestamps as `OffsetDateTime` (`@PrePersist` initializes `createdAt` / `updatedAt` when `null`; `@PreUpdate` refreshes `updatedAt`).
- Use `@GeneratedValue(strategy = GenerationType.UUID)` on `id` (while allowing pre-assigned UUIDs if needed, or standard `GenerationType.UUID`).
- Map foreign keys with both `@ManyToOne(fetch = FetchType.LAZY)` (`@JoinColumn(name = "department_id", nullable = false)`) AND read-only / convenient ID accessors (or `department.id` Spring Data property traversal `existsByDepartment_IdAndFileHash` aliased via `@Query` so exact signature `existsByDepartmentIdAndFileHash(UUID departmentId, String fileHash)` works cleanly!).
- Annotate `ChatMessageEntity.citations` with `@JdbcTypeCode(SqlTypes.JSON)` and `@Column(name = "citations", columnDefinition = "jsonb", nullable = false)`.
- Set `spring.jpa.hibernate.ddl-auto: validate` in `backend/src/main/resources/application.yml` (and keep `spring.jpa.hibernate.ddl-auto: none` in `application-test.yml`).

- [ ] **Step 4: Run tests to verify JPA validation and all repository contracts pass**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=RelationalPersistenceIntegrationTest,FlywaySchemaAndProdIsolationIntegrationTest,DocuMindApplicationTests`
Expected: `BUILD SUCCESS`, all tests green.

- [ ] **Step 5: Commit Task 2**

```powershell
git add backend/src/
git commit -m "feat(domain): implement enums, 7 validated JPA entities, and repositories (DA-6)"
```

---

### Task 3: Vector JDBC Repositories, `PgVectorStore` Cascade Proof & `EXPLAIN` HNSW Verification

**Files:**
- Create: `backend/src/main/java/com/documind/infrastructure/vector/SemanticCacheEntry.java`
- Create: `backend/src/main/java/com/documind/infrastructure/vector/DocumentChunkVectorRepository.java`
- Create: `backend/src/main/java/com/documind/infrastructure/vector/SemanticCacheVectorRepository.java`
- Test: `backend/src/test/java/com/documind/infrastructure/vector/VectorStoreAndHnswIndexIntegrationTest.java`

**Interfaces:**
- Consumes: `JdbcClient`, `ObjectMapper`, `com.pgvector.PGvector`, `CitationPayload`, `VectorStore` (`PgVectorStore`), `DocumentRepository`.
- Produces:
  - `SemanticCacheEntry(UUID id, String rbacScope, long scopeVersion, String queryHash, String queryText, String responseContent, List<CitationPayload> citations, String modelProvider, double similarityScore)`
  - `DocumentChunkVectorRepository`:
    - `long countByDocumentId(UUID documentId)`
    - `int deleteByDocumentIdAndVersionLessThan(UUID documentId, int keepVersion)`
    - `List<String> explainCosineSearch(float[] queryVector, int limit)`
  - `SemanticCacheVectorRepository`:
    - `UUID save(String rbacScope, long scopeVersion, String queryHash, String queryText, float[] queryEmbedding, String responseContent, List<CitationPayload> citations, String modelProvider, OffsetDateTime expiresAt)`
    - `Optional<SemanticCacheEntry> findBestMatch(String rbacScope, long scopeVersion, float[] queryEmbedding, double minSimilarity)`
    - `List<String> explainCosineSearch(String rbacScope, long scopeVersion, float[] queryVector, int limit)`

- [ ] **Step 1: Write failing `VectorStoreAndHnswIndexIntegrationTest`**

Create `VectorStoreAndHnswIndexIntegrationTest` (`@SpringBootTest`, `@Import(PostgresTestcontainersConfig.class)`) testing:
1. **`PgVectorStore` + Trigger + RBAC Metadata Filter + `ON DELETE CASCADE`:**
   - Persist a `DocumentEntity` (`HR`, `AccessLevel.DEPARTMENT`, `currentVersion = 1`).
   - Add 2 `org.springframework.ai.document.Document` chunks via `vectorStore.add(...)` with metadata `Map.of("document_id", docId.toString(), "department_id", hrId.toString(), "access_level", "DEPARTMENT", "file_name", "handbook.pdf", "page_number", 3, "chunk_index", 0, "doc_version", 1)`.
   - Assert `chunkVectorRepository.countByDocumentId(docId) == 2` and `jdbcTemplate` reads `page_number == 3`, `doc_version == 1`.
   - Assert `vectorStore.similaritySearch(SearchRequest.builder().query("nghỉ phép").topK(5).filterExpression(new FilterExpressionBuilder().eq("department_id", hrId.toString()).build()).build())` returns the stored chunks.
   - Add a `doc_version = 2` chunk, run `chunkVectorRepository.deleteByDocumentIdAndVersionLessThan(docId, 2)`, and assert only the `v2` chunk remains (`count == 1`).
   - Call `documentRepository.deleteById(docId)` and assert `chunkVectorRepository.countByDocumentId(docId) == 0` (`ON DELETE CASCADE`).
2. **DB-Level Orphan Vector Rejection:**
   - Calling `vectorStore.add(List.of(new Document("orphan chunk without document_id", Map.of("access_level", "PUBLIC"))))` throws `DataIntegrityViolationException`.
3. **RBAC-Scoped `SemanticCacheVectorRepository` Isolation:**
   - Save an entry under `("DEPT:" + hrId + ":MANAGER", 1L, ...)`.
   - Assert `findBestMatch("DEPT:" + hrId + ":MANAGER", 1L, sameVector, 0.94)` hits (`similarityScore >= 0.99`), while `("DEPT:" + hrId + ":EMPLOYEE", 1L, ...)` and `("DEPT:" + hrId + ":MANAGER", 2L, ...)` both return `Optional.empty()`.
4. **`EXPLAIN` HNSW Index Usage Proof (Acceptance Criterion #2):**
   - Inside `@Transactional`: execute `jdbcTemplate.execute("SET LOCAL enable_seqscan = off")`, call `chunkVectorRepository.explainCosineSearch(sampleVector, 5)` and `semanticCacheRepository.explainCosineSearch("DEPT:" + hrId + ":MANAGER", 1L, sampleVector, 1)`, and assert plan lines contain `"Index Scan using idx_document_chunks_embedding_hnsw"` and `"Index Scan using idx_semantic_cache_embedding_hnsw"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `Set-Location backend; .\mvnw.cmd test -Dtest=VectorStoreAndHnswIndexIntegrationTest`
Expected: FAIL (compile error — `SemanticCacheEntry`, `DocumentChunkVectorRepository`, and `SemanticCacheVectorRepository` do not exist yet).

- [ ] **Step 3: Implement `SemanticCacheEntry`, `DocumentChunkVectorRepository`, and `SemanticCacheVectorRepository`**

Format `float[]` parameters via `new PGvector(vector).toString()` bound to `CAST(:vec AS vector)` so every query uses `pgvector`'s `<=>` cosine distance operator cleanly:
- Similarity score in `SemanticCacheVectorRepository.findBestMatch`: `1.0 - (query_embedding <=> CAST(:vec AS vector)) AS similarity_score`, filtered by `rbac_scope = :scope AND scope_version = :version AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) AND (1.0 - (query_embedding <=> CAST(:vec AS vector))) >= :minSimilarity ORDER BY query_embedding <=> CAST(:vec AS vector) LIMIT 1`, incrementing `hit_count` when matched.
- `explainCosineSearch`: executes `EXPLAIN SELECT id FROM document_chunks ORDER BY embedding <=> CAST(:vec AS vector) LIMIT :limit` (and equivalent on `semantic_cache`) returning `List<String>` query plan lines.

- [ ] **Step 4: Run full unit + Testcontainers test suite**

Run: `Set-Location backend; .\mvnw.cmd test`
Expected: `BUILD SUCCESS`, all unit tests and Testcontainers integration tests pass with `0` failures.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/
git commit -m "feat(vector): add JdbcClient vector repositories and verify HNSW EXPLAIN plan (DA-6)"
```

---

### Task 4: Live Docker Migration Smoke Verification (`localhost:5432`) & Jira `DA-6` Update

**Files:**
- Modify: `backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java`

**Interfaces:**
- Consumes: Live Docker stack (`postgres:5432`, `redis:6380`, `minio:9000`), Windows Ollama (`11434`), `DocumentChunkVectorRepository`, `JdbcTemplate`.
- Produces: End-to-end live verification of `DA-6` Acceptance Criteria on the developer host.

- [ ] **Step 1: Extend live smoke suite with `DA-6` Flyway `V1..V3` and live `EXPLAIN` HNSW assertions**

Add assertions to `Day1InfrastructureAndAiSmokeTest` (keeping `@EnabledIfSystemProperty(named = "smoke", matches = "true")`):
1. `flyway_schema_history` on live `localhost:5432` contains successful versions `1`, `2`, `3`.
2. `departments` has `3` rows (`IT`, `HR`, `SALES`) and `users` has `3` rows (`admin@documind.ai`, `manager.hr@documind.ai`, `employee.sales@documind.ai`).
3. Live PostgreSQL `EXPLAIN` (inside a rolled-back `TransactionTemplate` setting `SET LOCAL enable_seqscan = off`) confirms `Index Scan using idx_document_chunks_embedding_hnsw` on a real 1024-dim vector generated by Ollama `bge-m3`!

- [ ] **Step 2: Run full Testcontainers suite AND live `-Dsmoke=true` suite**

Run:
```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd test -Dsmoke=true -Dtest=Day1InfrastructureAndAiSmokeTest
```
Expected: Both commands exit `0` (`BUILD SUCCESS`), live `documind-postgres` cleanly migrated `V1..V3`, `/actuator/health` `UP`, and real `bge-m3` `float[1024]` vector query uses `idx_document_chunks_embedding_hnsw`.

- [ ] **Step 3: Commit smoke verification & update Jira `DA-6`**

```powershell
git add backend/src/test/java/com/documind/infrastructure/ai/Day1InfrastructureAndAiSmokeTest.java
git commit -m "test(smoke): verify live Flyway V1..V3 migration and bge-m3 HNSW EXPLAIN plan (DA-6)"
```
Post verification summary comment to Jira `DA-6` (via `jira-cwb`) and transition status when executing the plan.
