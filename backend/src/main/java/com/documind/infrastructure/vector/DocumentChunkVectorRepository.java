package com.documind.infrastructure.vector;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link JdbcClient} repository for {@code document_chunks} lifecycle counts,
 * version-safe re-index cleanup, and HNSW {@code EXPLAIN} verification.
 */
@Repository
public class DocumentChunkVectorRepository {

    private final Supplier<JdbcClient> jdbcClientSupplier;

    @Autowired
    public DocumentChunkVectorRepository(ObjectProvider<JdbcClient> jdbcClientProvider) {
        this(() -> Objects.requireNonNull(jdbcClientProvider, "jdbcClientProvider must not be null").getObject());
    }

    public DocumentChunkVectorRepository(JdbcClient jdbcClient) {
        this(() -> Objects.requireNonNull(jdbcClient, "jdbcClient must not be null"));
    }

    private DocumentChunkVectorRepository(Supplier<JdbcClient> jdbcClientSupplier) {
        this.jdbcClientSupplier = jdbcClientSupplier;
    }

    /**
     * Counts all indexed vector chunks currently associated with the given document.
     */
    public long countByDocumentId(UUID documentId) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Long count = jdbcClient().sql("""
                SELECT COUNT(*)
                FROM document_chunks
                WHERE document_id = :documentId
                """)
            .param("documentId", documentId)
            .query(Long.class)
            .single();
        return count != null ? count : 0L;
    }

    /**
     * Deletes superseded chunks belonging to {@code documentId} whose {@code doc_version} is below {@code keepVersion}.
     */
    public int deleteByDocumentIdAndVersionLessThan(UUID documentId, int keepVersion) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (keepVersion < 1) {
            throw new IllegalArgumentException("keepVersion must be >= 1");
        }
        return jdbcClient().sql("""
                DELETE FROM document_chunks
                WHERE document_id = :documentId
                  AND doc_version < :keepVersion
                """)
            .param("documentId", documentId)
            .param("keepVersion", keepVersion)
            .update();
    }

    /**
     * Runs PostgreSQL {@code EXPLAIN} on a cosine-distance top-K query against {@code document_chunks}.
     */
    public List<String> explainCosineSearch(float[] queryVector, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        String vectorLiteral = PgVectorLiterals.toNonZeroFiniteVectorLiteral(queryVector, "queryVector");
        return jdbcClient().sql("""
                EXPLAIN
                SELECT id
                FROM document_chunks
                ORDER BY embedding <=> CAST(:vec AS vector)
                LIMIT :limit
                """)
            .param("vec", vectorLiteral)
            .param("limit", limit)
            .query(String.class)
            .list();
    }

    private JdbcClient jdbcClient() {
        return jdbcClientSupplier.get();
    }
}
