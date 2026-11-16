package com.documind.infrastructure.vector;

import com.documind.domain.entity.DepartmentEntity;
import com.documind.domain.entity.DocumentEntity;
import com.documind.domain.entity.UserEntity;
import com.documind.domain.enums.AccessLevel;
import com.documind.domain.enums.DocumentStatus;
import com.documind.domain.model.CitationPayload;
import com.documind.repository.DepartmentRepository;
import com.documind.repository.DocumentRepository;
import com.documind.repository.UserRepository;
import com.documind.support.PostgresTestcontainersConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestcontainersConfig.class)
class VectorStoreAndHnswIndexIntegrationTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private DocumentChunkVectorRepository chunkVectorRepository;

    @Autowired
    private SemanticCacheVectorRepository semanticCacheRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUpTestRows() {
        jdbcTemplate.update("DELETE FROM semantic_cache");
        jdbcTemplate.update("DELETE FROM documents");
        jdbcTemplate.update("UPDATE departments SET cache_version = 1");
    }

    @Test
    void pgVectorStoreSyncsMetadataFiltersByDepartmentPrunesOldVersionsAndCascadesOnDelete() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity sales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        UUID hrId = hr.getId();

        DocumentEntity hrDoc = new DocumentEntity();
        hrDoc.setDepartment(hr);
        hrDoc.setUploadedBy(hrManager);
        hrDoc.setOriginalName("handbook.pdf");
        hrDoc.setStorageKey("hr/handbook.pdf");
        hrDoc.setFileExtension("pdf");
        hrDoc.setMimeType("application/pdf");
        hrDoc.setFileSizeBytes(4096L);
        hrDoc.setFileHash("c".repeat(64));
        hrDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        hrDoc.setStatus(DocumentStatus.COMPLETED);
        hrDoc.setCurrentVersion(1);
        hrDoc = documentRepository.saveAndFlush(hrDoc);
        UUID docId = hrDoc.getId();

        DocumentEntity salesDoc = new DocumentEntity();
        salesDoc.setDepartment(sales);
        salesDoc.setUploadedBy(hrManager);
        salesDoc.setOriginalName("sales-playbook.pdf");
        salesDoc.setStorageKey("sales/sales-playbook.pdf");
        salesDoc.setFileExtension("pdf");
        salesDoc.setMimeType("application/pdf");
        salesDoc.setFileSizeBytes(2048L);
        salesDoc.setFileHash("d".repeat(64));
        salesDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        salesDoc.setStatus(DocumentStatus.COMPLETED);
        salesDoc.setCurrentVersion(1);
        salesDoc = documentRepository.saveAndFlush(salesDoc);

        Document chunk0 = new Document(
            "nghỉ phép",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "handbook.pdf",
                "page_number", 3,
                "chunk_index", 0,
                "doc_version", 1
            )
        );
        Document chunk1 = new Document(
            "Quy định nghỉ phép năm cho nhân viên chính thức tại công ty",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "handbook.pdf",
                "page_number", 3,
                "chunk_index", 1,
                "doc_version", 1
            )
        );
        Document salesChunk = new Document(
            "nghỉ phép",
            Map.of(
                "document_id", salesDoc.getId().toString(),
                "department_id", sales.getId().toString(),
                "access_level", "DEPARTMENT",
                "file_name", "sales-playbook.pdf",
                "page_number", 1,
                "chunk_index", 0,
                "doc_version", 1
            )
        );

        vectorStore.add(List.of(chunk0, chunk1, salesChunk));

        assertThat(chunkVectorRepository.countByDocumentId(docId)).isEqualTo(2L);
        List<Map<String, Object>> persistedRows = jdbcTemplate.queryForList(
            "SELECT document_id, page_number, doc_version, chunk_index FROM document_chunks WHERE document_id = ? ORDER BY chunk_index",
            docId
        );
        assertThat(persistedRows).hasSize(2);
        assertThat(persistedRows).allSatisfy(row -> {
            assertThat(row.get("document_id")).isEqualTo(docId);
            assertThat(row.get("page_number")).isEqualTo(3);
            assertThat(row.get("doc_version")).isEqualTo(1);
        });

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<Document> hrHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("nghỉ phép")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression(builder.eq("department_id", hrId.toString()).build())
                .build()
        );
        assertThat(hrHits).isNotEmpty();
        assertThat(hrHits).allSatisfy(hit -> {
            assertThat(hit.getMetadata().get("department_id")).isEqualTo(hrId.toString());
            assertThat(hit.getMetadata().get("document_id")).isEqualTo(docId.toString());
        });

        List<Document> salesHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("nghỉ phép")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression(builder.eq("department_id", sales.getId().toString()).build())
                .build()
        );
        assertThat(salesHits).hasSize(1);
        assertThat(salesHits.getFirst().getMetadata().get("document_id")).isEqualTo(salesDoc.getId().toString());

        Document v2Chunk = new Document(
            "Chính sách nghỉ phép năm cập nhật phiên bản 2",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "handbook.pdf",
                "page_number", 4,
                "chunk_index", 0,
                "doc_version", 2
            )
        );
        vectorStore.add(List.of(v2Chunk));
        assertThat(chunkVectorRepository.countByDocumentId(docId)).isEqualTo(3L);

        int pruned = chunkVectorRepository.deleteByDocumentIdAndVersionLessThan(docId, 2);
        assertThat(pruned).isEqualTo(2);
        assertThat(chunkVectorRepository.countByDocumentId(docId)).isEqualTo(1L);
        Integer remainingVersion = jdbcTemplate.queryForObject(
            "SELECT doc_version FROM document_chunks WHERE document_id = ?",
            Integer.class,
            docId
        );
        assertThat(remainingVersion).isEqualTo(2);

        documentRepository.deleteById(docId);
        assertThat(chunkVectorRepository.countByDocumentId(docId)).isZero();
    }

    @Test
    void rejectsOrphanVectorChunkWithoutDocumentIdAtDatabaseLevel() {
        Document orphanWithoutDocId = new Document(
            "orphan chunk without document_id",
            Map.of("access_level", "PUBLIC")
        );

        assertThatThrownBy(() -> vectorStore.add(List.of(orphanWithoutDocId)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document orphanWithUnknownDocId = new Document(
            "orphan chunk with unknown document_id",
            Map.of(
                "document_id", UUID.randomUUID().toString(),
                "department_id", UUID.randomUUID().toString(),
                "file_name", "missing.pdf",
                "access_level", "PUBLIC",
                "doc_version", 1,
                "chunk_index", 0
            )
        );

        assertThatThrownBy(() -> vectorStore.add(List.of(orphanWithUnknownDocId)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pgVectorStoreUpsertUpdatesColumnsAndEnforcesRbacMetadataConstraints() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity sales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        UUID hrId = hr.getId();

        DocumentEntity hrDoc = new DocumentEntity();
        hrDoc.setDepartment(hr);
        hrDoc.setUploadedBy(hrManager);
        hrDoc.setOriginalName("policy-upsert.md");
        hrDoc.setStorageKey("hr/policy-upsert.md");
        hrDoc.setFileExtension("md");
        hrDoc.setMimeType("text/markdown");
        hrDoc.setFileSizeBytes(1024L);
        hrDoc.setFileHash("b".repeat(64));
        hrDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        hrDoc.setStatus(DocumentStatus.COMPLETED);
        hrDoc = documentRepository.saveAndFlush(hrDoc);
        UUID docId = hrDoc.getId();

        UUID chunkUuid = UUID.randomUUID();
        String chunkId = chunkUuid.toString();

        Document initialChunk = new Document(
            chunkId,
            "Nội dung phiên bản 1 có số trang",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "page_number", 5,
                "chunk_index", 2,
                "doc_version", 1
            )
        );
        vectorStore.add(List.of(initialChunk));

        // Upsert same chunk ID without page_number -> trigger must clear stale page_number to NULL and normalize metadata key
        Document upsertedChunkWithoutPage = new Document(
            chunkId,
            "Nội dung phiên bản 2 định dạng markdown không phân trang",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "chunk_index", 0,
                "doc_version", 2
            )
        );
        vectorStore.add(List.of(upsertedChunkWithoutPage));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT document_id, page_number, doc_version, chunk_index, jsonb_exists(metadata, 'page_number') AS has_page_key FROM document_chunks WHERE id = ?",
            chunkUuid
        );
        assertThat(row.get("document_id")).isEqualTo(docId);
        assertThat(row.get("page_number")).isNull();
        assertThat(row.get("doc_version")).isEqualTo(2);
        assertThat(row.get("chunk_index")).isEqualTo(0);
        assertThat(row.get("has_page_key")).isEqualTo(Boolean.TRUE);

        // Upsert same chunk ID omitting document_id must fail instead of silently retaining OLD.document_id
        Document upsertMissingDocId = new Document(
            chunkId,
            "Nội dung thiếu document_id khi upsert",
            Map.of(
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 0
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(upsertMissingDocId)))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Mandatory metadata CHECK / trigger checks: department_id, file_name, canonical access_level, doc_version, chunk_index
        Document missingDepartmentId = new Document(
            "Chunk thiếu department_id",
            Map.of(
                "document_id", docId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(missingDepartmentId)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document missingFileName = new Document(
            "Chunk thiếu file_name",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(missingFileName)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document invalidAccessLevel = new Document(
            "Chunk sai chuẩn chữ hoa access_level",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "Department",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(invalidAccessLevel)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document missingDocVersion = new Document(
            "Chunk thiếu doc_version",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(missingDocVersion)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document missingChunkIndex = new Document(
            "Chunk thiếu chunk_index",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "doc_version", 2
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(missingChunkIndex)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document mismatchedDepartment = new Document(
            "Chunk sai department_id so với document cha",
            Map.of(
                "document_id", docId.toString(),
                "department_id", sales.getId().toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(mismatchedDepartment)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document mismatchedAccessLevel = new Document(
            "Chunk sai access_level so với document cha",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "PUBLIC",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(mismatchedAccessLevel)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document mismatchedFileName = new Document(
            "Chunk sai file_name so với document cha",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "spoofed-filename.md",
                "doc_version", 2,
                "chunk_index", 1
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(mismatchedFileName)))
            .isInstanceOf(DataIntegrityViolationException.class);

        Document duplicateDocVersionChunkIndex = new Document(
            UUID.randomUUID().toString(),
            "Chunk trùng (document_id, doc_version, chunk_index)",
            Map.of(
                "document_id", docId.toString(),
                "department_id", hrId.toString(),
                "access_level", "DEPARTMENT",
                "file_name", "policy-upsert.md",
                "doc_version", 2,
                "chunk_index", 0
            )
        );
        assertThatThrownBy(() -> vectorStore.add(List.of(duplicateDocVersionChunkIndex)))
            .isInstanceOf(DataIntegrityViolationException.class);

        long hrCacheVersionBefore = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion();
        long itCacheVersionBefore = departmentRepository.findByCodeIgnoreCase("IT").orElseThrow().getCacheVersion();

        // Parent update on documents.access_level and original_name must propagate to document_chunks and bump cache_version
        hrDoc.setAccessLevel(AccessLevel.PUBLIC);
        hrDoc.setOriginalName("policy-public-renamed.md");
        documentRepository.saveAndFlush(hrDoc);
        Map<String, Object> propagated = jdbcTemplate.queryForMap(
            "SELECT metadata->>'access_level' AS access_level, metadata->>'file_name' AS file_name FROM document_chunks WHERE id = ?",
            chunkUuid
        );
        assertThat(propagated.get("access_level")).isEqualTo("PUBLIC");
        assertThat(propagated.get("file_name")).isEqualTo("policy-public-renamed.md");
        assertThat(departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion())
            .isEqualTo(hrCacheVersionBefore + 1L);
        assertThat(departmentRepository.findByCodeIgnoreCase("IT").orElseThrow().getCacheVersion())
            .isEqualTo(itCacheVersionBefore + 1L);
    }

    @Test
    void iterativeScanReturnsFullTopKForSmallDepartmentAndFilteredExplainUsesHnsw() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity sales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        UUID hrId = hr.getId();

        DocumentEntity salesDoc = new DocumentEntity();
        salesDoc.setDepartment(sales);
        salesDoc.setUploadedBy(hrManager);
        salesDoc.setOriginalName("sales-flood.pdf");
        salesDoc.setStorageKey("sales/sales-flood.pdf");
        salesDoc.setFileExtension("pdf");
        salesDoc.setMimeType("application/pdf");
        salesDoc.setFileSizeBytes(4096L);
        salesDoc.setFileHash("e".repeat(64));
        salesDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        salesDoc.setStatus(DocumentStatus.COMPLETED);
        salesDoc = documentRepository.saveAndFlush(salesDoc);

        DocumentEntity hrDoc = new DocumentEntity();
        hrDoc.setDepartment(hr);
        hrDoc.setUploadedBy(hrManager);
        hrDoc.setOriginalName("hr-target.pdf");
        hrDoc.setStorageKey("hr/hr-target.pdf");
        hrDoc.setFileExtension("pdf");
        hrDoc.setMimeType("application/pdf");
        hrDoc.setFileSizeBytes(2048L);
        hrDoc.setFileHash("f".repeat(64));
        hrDoc.setAccessLevel(AccessLevel.DEPARTMENT);
        hrDoc.setStatus(DocumentStatus.COMPLETED);
        hrDoc = documentRepository.saveAndFlush(hrDoc);

        float[] queryVector = embeddingModel.embed("nghỉ phép sales");
        List<String> positiveHrTexts = new ArrayList<>(5);
        for (int candidate = 0; positiveHrTexts.size() < 5 && candidate < 200; candidate++) {
            String hrText = "Chính sách nhân sự đặc thù mục " + candidate;
            float[] vec = embeddingModel.embed(hrText);
            double dot = 0.0d;
            for (int d = 0; d < queryVector.length; d++) {
                dot += (double) queryVector[d] * vec[d];
            }
            if (dot > 0.01d && dot < 0.5d) {
                positiveHrTexts.add(hrText);
            }
        }
        assertThat(positiveHrTexts).hasSize(5);

        List<Document> batch = new ArrayList<>(85);
        for (int i = 0; i < 80; i++) {
            batch.add(new Document(
                "nghỉ phép sales",
                Map.of(
                    "document_id", salesDoc.getId().toString(),
                    "department_id", sales.getId().toString(),
                    "access_level", "DEPARTMENT",
                    "file_name", "sales-flood.pdf",
                    "page_number", 1,
                    "chunk_index", i,
                    "doc_version", 1
                )
            ));
        }
        for (int i = 0; i < positiveHrTexts.size(); i++) {
            batch.add(new Document(
                positiveHrTexts.get(i),
                Map.of(
                    "document_id", hrDoc.getId().toString(),
                    "department_id", hrId.toString(),
                    "access_level", "DEPARTMENT",
                    "file_name", "hr-target.pdf",
                    "page_number", i + 1,
                    "chunk_index", i,
                    "doc_version", 1
                )
            ));
        }
        vectorStore.add(batch);

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<Document> hrHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("nghỉ phép sales")
                .topK(5)
                .similarityThreshold(0.0)
                .filterExpression(builder.eq("department_id", hrId.toString()).build())
                .build()
        );
        assertThat(hrHits).hasSize(5);
        assertThat(hrHits).allSatisfy(hit ->
            assertThat(hit.getMetadata().get("department_id")).isEqualTo(hrId.toString())
        );

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            // Force index plan check because PostgreSQL favors sequential scan on tiny test tables (< hundreds of pages)
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            List<String> filteredPlan = chunkVectorRepository.explainFilteredCosineSearch(hrId, queryVector, 5);
            assertThat(String.join("\n", filteredPlan))
                .contains("Index Scan using idx_document_chunks_embedding_hnsw");
        });
    }

    @Test
    void semanticCacheUpsertsDuplicateQueryHashAndSupportsReadOnlyCallerTransactions() {
        UUID hrId = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getId();
        String managerScope = "DEPT:" + hrId + ":MANAGER";
        String queryHash = "12345678".repeat(8);
        String queryText = "Chính sách làm việc từ xa Hybrid";
        float[] queryVector = embeddingModel.embed(queryText);
        List<CitationPayload> citations = List.of(
            new CitationPayload(UUID.randomUUID(), "hybrid-work.pdf", 2, 0.9510d, "Tối đa 2 ngày làm việc từ xa mỗi tuần.")
        );

        UUID firstId = semanticCacheRepository.save(
            managerScope,
            1L,
            queryHash,
            queryText,
            queryVector,
            "Câu trả lời lần đầu.",
            citations,
            "groq",
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)
        );

        UUID upsertedId = semanticCacheRepository.save(
            managerScope,
            1L,
            queryHash,
            queryText,
            queryVector,
            "Câu trả lời đã được làm mới idempotent.",
            citations,
            "gemini",
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)
        );
        assertThat(upsertedId).isEqualTo(firstId);

        Integer rowCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM semantic_cache WHERE rbac_scope = ? AND scope_version = 1 AND query_hash = ?",
            Integer.class,
            managerScope,
            queryHash
        );
        assertThat(rowCount).isEqualTo(1);

        TransactionTemplate readOnlyTx = new TransactionTemplate(transactionManager);
        readOnlyTx.setReadOnly(true);

        Optional<SemanticCacheEntry> hitInReadOnlyTx = readOnlyTx.execute(
            status -> semanticCacheRepository.findBestMatch(managerScope, 1L, queryVector, 0.94d)
        );
        assertThat(hitInReadOnlyTx).isPresent();
        assertThat(hitInReadOnlyTx.orElseThrow().responseContent())
            .isEqualTo("Câu trả lời đã được làm mới idempotent.");
        assertThat(hitInReadOnlyTx.orElseThrow().modelProvider()).isEqualTo("gemini");

        semanticCacheRepository.flushPendingHitCounts();
        Integer hitCount = jdbcTemplate.queryForObject(
            "SELECT hit_count FROM semantic_cache WHERE id = ?",
            Integer.class,
            upsertedId
        );
        assertThat(hitCount).isEqualTo(1);
    }

    @Test
    void semanticCacheIsolatesByRbacScopeAndScopeVersionAndTracksHits() {
        UUID hrId = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getId();
        String managerScope = "DEPT:" + hrId + ":MANAGER";
        String employeeScope = "DEPT:" + hrId + ":EMPLOYEE";
        String queryText = "Quy trình phê duyệt nghỉ phép đặc biệt";
        float[] queryVector = embeddingModel.embed(queryText);
        float[] unrelatedVector = embeddingModel.embed("Quy trình cấp phát máy tính xách tay mới");
        List<CitationPayload> citations = List.of(
            new CitationPayload(UUID.randomUUID(), "hr-confidential.pdf", 7, 0.9625d, "Trích đoạn bảo mật HR.")
        );

        UUID savedId = semanticCacheRepository.save(
            managerScope,
            1L,
            "e".repeat(64),
            queryText,
            queryVector,
            "Quản lý nhân sự phê duyệt tối đa 5 ngày phép đặc biệt.",
            citations,
            "groq",
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(2)
        );
        assertThat(savedId).isNotNull();

        semanticCacheRepository.save(
            managerScope,
            1L,
            "f".repeat(64),
            "Câu hỏi đã hết hạn TTL",
            unrelatedVector,
            "Nội dung đã hết hạn.",
            List.of(),
            "groq",
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        );

        Optional<SemanticCacheEntry> hit = semanticCacheRepository.findBestMatch(managerScope, 1L, queryVector, 0.94d);
        assertThat(hit).isPresent();
        SemanticCacheEntry entry = hit.orElseThrow();
        assertThat(entry.id()).isEqualTo(savedId);
        assertThat(entry.rbacScope()).isEqualTo(managerScope);
        assertThat(entry.scopeVersion()).isEqualTo(1L);
        assertThat(entry.similarityScore()).isGreaterThanOrEqualTo(0.99d);
        assertThat(entry.citations()).containsExactlyElementsOf(citations);
        assertThat(entry.modelProvider()).isEqualTo("groq");

        Integer hitCount = jdbcTemplate.queryForObject(
            "SELECT hit_count FROM semantic_cache WHERE id = ?",
            Integer.class,
            savedId
        );
        assertThat(hitCount).isEqualTo(1);

        assertThat(semanticCacheRepository.findBestMatch(employeeScope, 1L, queryVector, 0.94d)).isEmpty();
        assertThat(semanticCacheRepository.findBestMatch(managerScope, 2L, queryVector, 0.94d)).isEmpty();
        assertThat(semanticCacheRepository.findBestMatch(managerScope, 1L, unrelatedVector, 0.94d)).isEmpty();

        float[] permanentVector = embeddingModel.embed("Quy định bảo mật nội bộ không hết hạn");
        UUID permanentId = semanticCacheRepository.save(
            "GLOBAL:ADMIN",
            1L,
            "9".repeat(64),
            "Quy định bảo mật nội bộ",
            permanentVector,
            "Nội dung vĩnh viễn cho admin.",
            null,
            null,
            null
        );
        Optional<SemanticCacheEntry> permanentHit = semanticCacheRepository.findBestMatch(
            "GLOBAL:ADMIN", 1L, permanentVector, 0.94d
        );
        assertThat(permanentHit).isPresent();
        assertThat(permanentHit.orElseThrow().id()).isEqualTo(permanentId);
        assertThat(permanentHit.orElseThrow().citations()).isEmpty();
        assertThat(permanentHit.orElseThrow().modelProvider()).isNull();
    }

    @Test
    void explainCosineSearchUsesHnswIndexesOnDocumentChunksAndSemanticCache() {
        UUID hrId = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getId();
        String managerScope = "DEPT:" + hrId + ":MANAGER";
        float[] sampleVector = embeddingModel.embed("Truy vấn kiểm tra HNSW index");
        String vectorLiteral = new PGvector(sampleVector).toString();

        // Populate enough rows and refresh planner stats so HNSW beats B-Tree (rbac_scope, scope_version) + Sort
        jdbcTemplate.update("""
                INSERT INTO semantic_cache (
                    rbac_scope, scope_version, query_hash, query_text, query_embedding, response_content
                )
                SELECT ?, 1, md5(gs::text), 'cached query ' || gs, CAST(? AS vector), 'cached response ' || gs
                FROM generate_series(1, 1000) AS gs
                """,
            managerScope,
            vectorLiteral
        );
        jdbcTemplate.execute("ANALYZE semantic_cache");

        transactionTemplate.executeWithoutResult(status -> {
            // Force index plan check because PostgreSQL favors sequential scan on small test datasets
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");

            List<String> chunkPlan = chunkVectorRepository.explainCosineSearch(sampleVector, 5);
            assertThat(String.join("\n", chunkPlan))
                .contains("Index Scan using idx_document_chunks_embedding_hnsw");

            List<String> cachePlan = semanticCacheRepository.explainCosineSearch(managerScope, 1L, sampleVector, 1);
            assertThat(String.join("\n", cachePlan))
                .contains("Index Scan using idx_semantic_cache_embedding_hnsw");
        });
    }

    @Test
    void rejectsZeroNormAndNonFiniteVectorsInPgVectorRepositories() {
        float[] zeroVector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
        float[] nanVector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
        nanVector[0] = 1.0f;
        nanVector[1] = Float.NaN;
        float[] posInfVector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
        posInfVector[0] = 1.0f;
        posInfVector[1] = Float.POSITIVE_INFINITY;
        float[] negInfVector = new float[PostgresTestcontainersConfig.EMBEDDING_DIMENSIONS];
        negInfVector[0] = 1.0f;
        negInfVector[1] = Float.NEGATIVE_INFINITY;
        float[] wrongDimensionVector = new float[]{1.0f, 0.0f, 0.0f};

        List<float[]> invalidVectors = List.of(zeroVector, nanVector, posInfVector, negInfVector, wrongDimensionVector);

        assertThatThrownBy(() -> PgVectorLiterals.toNonZeroFiniteVectorLiteral(null, "queryVector"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PgVectorLiterals.toNonZeroFiniteVectorLiteral(new float[0], "queryVector"))
            .isInstanceOf(IllegalArgumentException.class);

        for (float[] invalidVector : invalidVectors) {
            assertThatThrownBy(() -> PgVectorLiterals.toNonZeroFiniteVectorLiteral(invalidVector, "queryVector"))
                .isInstanceOf(IllegalArgumentException.class);

            // Spring @Repository PersistenceExceptionTranslationPostProcessor wraps IllegalArgumentException in InvalidDataAccessApiUsageException
            assertThatThrownBy(() -> chunkVectorRepository.explainCosineSearch(invalidVector, 5))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> semanticCacheRepository.save(
                "GLOBAL:ADMIN",
                1L,
                "a".repeat(64),
                "invalid vector query",
                invalidVector,
                "unused",
                List.of(),
                "groq",
                null
            ))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> semanticCacheRepository.findBestMatch("GLOBAL:ADMIN", 1L, invalidVector, 0.94d))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> semanticCacheRepository.explainCosineSearch("GLOBAL:ADMIN", 1L, invalidVector, 1))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void concurrentChunkInsertAndParentReclassificationSerializesViaParentShareLock() throws Exception {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        UUID hrId = hr.getId();

        DocumentEntity docA = new DocumentEntity();
        docA.setDepartment(hr);
        docA.setUploadedBy(hrManager);
        docA.setOriginalName("race-a.pdf");
        docA.setStorageKey("hr/race-a.pdf");
        docA.setFileExtension("pdf");
        docA.setMimeType("application/pdf");
        docA.setFileSizeBytes(2048L);
        docA.setFileHash("4".repeat(64));
        docA.setAccessLevel(AccessLevel.DEPARTMENT);
        docA.setStatus(DocumentStatus.COMPLETED);
        docA = documentRepository.saveAndFlush(docA);

        DocumentEntity docB = new DocumentEntity();
        docB.setDepartment(hr);
        docB.setUploadedBy(hrManager);
        docB.setOriginalName("race-b.pdf");
        docB.setStorageKey("hr/race-b.pdf");
        docB.setFileExtension("pdf");
        docB.setMimeType("application/pdf");
        docB.setFileSizeBytes(2048L);
        docB.setFileHash("5".repeat(64));
        docB.setAccessLevel(AccessLevel.DEPARTMENT);
        docB.setStatus(DocumentStatus.COMPLETED);
        docB = documentRepository.saveAndFlush(docB);

        String vecLiteral = new PGvector(embeddingModel.embed("Concurrency RBAC lock test")).toString();
        String insertSql = """
            INSERT INTO document_chunks (id, document_id, doc_version, chunk_index, page_number, content, metadata, embedding)
            VALUES (?, ?, 1, 0, 1, 'Chunk content', CAST(? AS jsonb), CAST(? AS vector))
            """;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Direction A: Uncommitted chunk insert holds FOR SHARE on parent; concurrent reclassification blocks, then propagates
            UUID docAId = docA.getId();
            UUID chunkAId = UUID.randomUUID();
            String metadataA = objectMapper.writeValueAsString(Map.of(
                "document_id", docAId.toString(),
                "department_id", hrId.toString(),
                "file_name", "race-a.pdf",
                "access_level", "DEPARTMENT",
                "doc_version", 1,
                "chunk_index", 0,
                "page_number", 1
            ));
            long cacheBeforeA = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion();

            try (Connection conn1 = dataSource.getConnection();
                 Connection conn2 = dataSource.getConnection()) {
                conn1.setAutoCommit(false);
                conn2.setAutoCommit(false);

                try (PreparedStatement insertStmt = conn1.prepareStatement(insertSql)) {
                    insertStmt.setObject(1, chunkAId);
                    insertStmt.setObject(2, docAId);
                    insertStmt.setString(3, metadataA);
                    insertStmt.setString(4, vecLiteral);
                    insertStmt.executeUpdate();
                }

                Future<?> reclassifyFuture = executor.submit(() -> {
                    try (PreparedStatement updateStmt = conn2.prepareStatement(
                        "UPDATE documents SET access_level = 'CONFIDENTIAL' WHERE id = ?"
                    )) {
                        updateStmt.setObject(1, docAId);
                        updateStmt.executeUpdate();
                        conn2.commit();
                    }
                    return null;
                });

                Thread.sleep(150L);
                assertThat(reclassifyFuture.isDone()).isFalse();

                conn1.commit();
                reclassifyFuture.get(5, TimeUnit.SECONDS);
            }

            String chunkAccessAfter = jdbcTemplate.queryForObject(
                "SELECT metadata->>'access_level' FROM document_chunks WHERE id = ?",
                String.class,
                chunkAId
            );
            assertThat(chunkAccessAfter).isEqualTo("CONFIDENTIAL");
            assertThat(departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getCacheVersion())
                .isEqualTo(cacheBeforeA + 1L);

            // Direction B: Uncommitted parent reclassification blocks concurrent DEPARTMENT chunk insert, which then fails 23514
            UUID docBId = docB.getId();
            UUID chunkBId = UUID.randomUUID();
            String metadataB = objectMapper.writeValueAsString(Map.of(
                "document_id", docBId.toString(),
                "department_id", hrId.toString(),
                "file_name", "race-b.pdf",
                "access_level", "DEPARTMENT",
                "doc_version", 1,
                "chunk_index", 0,
                "page_number", 1
            ));

            try (Connection conn1 = dataSource.getConnection();
                 Connection conn2 = dataSource.getConnection()) {
                conn1.setAutoCommit(false);
                conn2.setAutoCommit(false);

                try (PreparedStatement updateStmt = conn1.prepareStatement(
                    "UPDATE documents SET access_level = 'CONFIDENTIAL' WHERE id = ?"
                )) {
                    updateStmt.setObject(1, docBId);
                    updateStmt.executeUpdate();
                }

                Future<?> insertFuture = executor.submit(() -> {
                    try (PreparedStatement insertStmt = conn2.prepareStatement(insertSql)) {
                        insertStmt.setObject(1, chunkBId);
                        insertStmt.setObject(2, docBId);
                        insertStmt.setString(3, metadataB);
                        insertStmt.setString(4, vecLiteral);
                        insertStmt.executeUpdate();
                        conn2.commit();
                    } catch (SQLException ex) {
                        conn2.rollback();
                        throw ex;
                    }
                    return null;
                });

                Thread.sleep(150L);
                assertThat(insertFuture.isDone()).isFalse();

                conn1.commit();
                assertThatThrownBy(() -> insertFuture.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(SQLException.class)
                    .satisfies(ex -> assertThat(((SQLException) ex.getCause()).getSQLState()).isEqualTo("23514"));
            }
            assertThat(chunkVectorRepository.countByDocumentId(docBId)).isZero();
        }
    }

    @Test
    void roleBasedPgVectorFilterEnforcesStandardEmployeeAndManagerVisibility() {
        DepartmentEntity hr = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow();
        DepartmentEntity sales = departmentRepository.findByCodeIgnoreCase("SALES").orElseThrow();
        UserEntity hrManager = userRepository.findByEmailIgnoreCase("manager.hr@documind.ai").orElseThrow();
        UUID hrId = hr.getId();
        UUID salesId = sales.getId();

        DocumentEntity hrPublic = saveDoc(hr, hrManager, "hr-pub.pdf", "hr/pub.pdf", "1", AccessLevel.PUBLIC);
        DocumentEntity hrDept = saveDoc(hr, hrManager, "hr-dept.pdf", "hr/dept.pdf", "2", AccessLevel.DEPARTMENT);
        DocumentEntity hrConf = saveDoc(hr, hrManager, "hr-conf.pdf", "hr/conf.pdf", "3", AccessLevel.CONFIDENTIAL);
        DocumentEntity salesPublic = saveDoc(sales, hrManager, "sales-pub.pdf", "sales/pub.pdf", "4", AccessLevel.PUBLIC);
        DocumentEntity salesDept = saveDoc(sales, hrManager, "sales-dept.pdf", "sales/dept.pdf", "5", AccessLevel.DEPARTMENT);
        DocumentEntity salesConf = saveDoc(sales, hrManager, "sales-conf.pdf", "sales/conf.pdf", "6", AccessLevel.CONFIDENTIAL);

        String sharedTopic = "Chính sách lương thưởng năm 2026";
        vectorStore.add(List.of(
            chunkDoc(hrPublic, sharedTopic),
            chunkDoc(hrDept, sharedTopic),
            chunkDoc(hrConf, sharedTopic),
            chunkDoc(salesPublic, sharedTopic),
            chunkDoc(salesDept, sharedTopic),
            chunkDoc(salesConf, sharedTopic)
        ));

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var standardEmployeeFilter = b.or(
            b.eq("access_level", "PUBLIC"),
            b.and(b.eq("department_id", hrId.toString()), b.eq("access_level", "DEPARTMENT"))
        ).build();

        List<Document> employeeHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(sharedTopic)
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression(standardEmployeeFilter)
                .build()
        );
        assertThat(employeeHits)
            .extracting(doc -> doc.getMetadata().get("document_id").toString())
            .containsExactlyInAnyOrder(
                hrPublic.getId().toString(),
                hrDept.getId().toString(),
                salesPublic.getId().toString()
            )
            .doesNotContain(hrConf.getId().toString(), salesDept.getId().toString(), salesConf.getId().toString());

        var managerFilter = b.or(
            b.eq("access_level", "PUBLIC"),
            b.eq("department_id", hrId.toString())
        ).build();

        List<Document> managerHits = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(sharedTopic)
                .topK(10)
                .similarityThreshold(0.0)
                .filterExpression(managerFilter)
                .build()
        );
        assertThat(managerHits)
            .extracting(doc -> doc.getMetadata().get("document_id").toString())
            .containsExactlyInAnyOrder(
                hrPublic.getId().toString(),
                hrDept.getId().toString(),
                hrConf.getId().toString(),
                salesPublic.getId().toString()
            )
            .doesNotContain(salesDept.getId().toString(), salesConf.getId().toString());
        assertThat(salesId).isNotEqualTo(hrId);
    }

    @Test
    void semanticCacheHitIncrementDoesNotStarveSaturatedPoolInReadOnlyTransactions() throws Exception {
        UUID hrId = departmentRepository.findByCodeIgnoreCase("HR").orElseThrow().getId();
        String managerScope = "DEPT:" + hrId + ":POOL_TEST";
        String queryText = "Kiểm tra tránh cạn kết nối HikariCP khi cache hit";
        float[] queryVector = embeddingModel.embed(queryText);

        UUID cachedId = semanticCacheRepository.save(
            managerScope,
            1L,
            "9".repeat(64),
            queryText,
            queryVector,
            "Kết quả cached an toàn với pool bão hòa.",
            List.of(),
            "groq",
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)
        );

        HikariDataSource mainPool = dataSource.unwrap(HikariDataSource.class);
        HikariConfig tinyPoolConfig = new HikariConfig();
        tinyPoolConfig.setJdbcUrl(mainPool.getJdbcUrl());
        tinyPoolConfig.setUsername(mainPool.getUsername());
        tinyPoolConfig.setPassword(mainPool.getPassword());
        tinyPoolConfig.setMaximumPoolSize(2);
        tinyPoolConfig.setConnectionTimeout(1_000L);
        tinyPoolConfig.setConnectionInitSql("SET hnsw.iterative_scan = 'relaxed_order'");

        try (HikariDataSource tinyPool = new HikariDataSource(tinyPoolConfig);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            DataSourceTransactionManager tinyTxManager = new DataSourceTransactionManager(tinyPool);
            SemanticCacheVectorRepository pooledRepo = new SemanticCacheVectorRepository(
                JdbcClient.create(tinyPool),
                tinyTxManager,
                objectMapper
            );
            TransactionTemplate readOnlyTx = new TransactionTemplate(tinyTxManager);
            readOnlyTx.setReadOnly(true);

            CyclicBarrier bothConnectionsHeld = new CyclicBarrier(2);
            List<Future<Optional<SemanticCacheEntry>>> futures = new ArrayList<>(2);
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> readOnlyTx.execute(status -> {
                    Optional<SemanticCacheEntry> hit = pooledRepo.findBestMatch(managerScope, 1L, queryVector, 0.94d);
                    try {
                        // Hold both 2/2 pool connections simultaneously to prove hit_count write never self-deadlocks
                        bothConnectionsHeld.await(5, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Barrier failed while holding read-only pool connections", ex);
                    }
                    return hit;
                })));
            }

            for (Future<Optional<SemanticCacheEntry>> future : futures) {
                Optional<SemanticCacheEntry> hit = future.get(5, TimeUnit.SECONDS);
                assertThat(hit).isPresent();
                assertThat(hit.orElseThrow().id()).isEqualTo(cachedId);
            }

            pooledRepo.flushPendingHitCounts();
            Integer hitCount = jdbcTemplate.queryForObject(
                "SELECT hit_count FROM semantic_cache WHERE id = ?",
                Integer.class,
                cachedId
            );
            assertThat(hitCount).isEqualTo(2);
        }
    }

    private DocumentEntity saveDoc(
        DepartmentEntity dept,
        UserEntity uploader,
        String originalName,
        String storageKey,
        String hashFill,
        AccessLevel accessLevel
    ) {
        DocumentEntity doc = new DocumentEntity();
        doc.setDepartment(dept);
        doc.setUploadedBy(uploader);
        doc.setOriginalName(originalName);
        doc.setStorageKey(storageKey);
        doc.setFileExtension("pdf");
        doc.setMimeType("application/pdf");
        doc.setFileSizeBytes(2048L);
        doc.setFileHash(hashFill.repeat(64));
        doc.setAccessLevel(accessLevel);
        doc.setStatus(DocumentStatus.COMPLETED);
        return documentRepository.saveAndFlush(doc);
    }

    private static Document chunkDoc(DocumentEntity doc, String content) {
        return new Document(
            content,
            Map.of(
                "document_id", doc.getId().toString(),
                "department_id", doc.getDepartmentId().toString(),
                "access_level", doc.getAccessLevel().name(),
                "file_name", doc.getOriginalName(),
                "page_number", 1,
                "chunk_index", 0,
                "doc_version", 1
            )
        );
    }
}
