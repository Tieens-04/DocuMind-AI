-- ============================================================================
-- DocuMind AI — V2 pgvector (1024-dim) & Semantic Cache Schema
-- Compatible with Spring AI 1.0.3 PgVectorStore + ON DELETE CASCADE
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- 8. document_chunks
CREATE TABLE document_chunks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID                NOT NULL CONSTRAINT fk_document_chunks_document REFERENCES documents(id) ON DELETE CASCADE,
    doc_version INT                 NOT NULL DEFAULT 1 CONSTRAINT chk_document_chunks_version CHECK (doc_version >= 1),
    chunk_index INT                 NOT NULL DEFAULT 0 CONSTRAINT chk_document_chunks_index CHECK (chunk_index >= 0),
    page_number INT                 CONSTRAINT chk_document_chunks_page CHECK (page_number IS NULL OR page_number >= 1),
    content     TEXT                NOT NULL,
    metadata    JSONB               NOT NULL DEFAULT '{}'::jsonb,
    embedding   public.vector(1024) NOT NULL,
    created_at  TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_document_chunks_rbac_metadata CHECK (
        NULLIF(BTRIM(metadata->>'document_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'department_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'file_name'), '') IS NOT NULL
        AND (metadata->>'access_level') IN ('PUBLIC', 'DEPARTMENT', 'CONFIDENTIAL')
    )
);

CREATE OR REPLACE FUNCTION sync_document_chunk_metadata()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.metadata IS NOT NULL THEN
        IF TG_OP = 'UPDATE' OR NULLIF(BTRIM(NEW.metadata->>'document_id'), '') IS NOT NULL THEN
            NEW.document_id := NULLIF(BTRIM(NEW.metadata->>'document_id'), '')::uuid;
        END IF;
        IF TG_OP = 'UPDATE' OR NEW.metadata ? 'doc_version' THEN
            NEW.doc_version := COALESCE(NULLIF(BTRIM(NEW.metadata->>'doc_version'), '')::int, 1);
        END IF;
        IF TG_OP = 'UPDATE' OR NEW.metadata ? 'chunk_index' THEN
            NEW.chunk_index := COALESCE(NULLIF(BTRIM(NEW.metadata->>'chunk_index'), '')::int, 0);
        END IF;
        IF TG_OP = 'UPDATE' OR NEW.metadata ? 'page_number' THEN
            NEW.page_number := NULLIF(BTRIM(NEW.metadata->>'page_number'), '')::int;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_sync_document_chunk_metadata
BEFORE INSERT OR UPDATE ON document_chunks
FOR EACH ROW EXECUTE FUNCTION sync_document_chunk_metadata();

CREATE INDEX idx_document_chunks_embedding_hnsw
    ON document_chunks USING hnsw (embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_document_chunks_metadata_gin
    ON document_chunks USING gin (metadata);
CREATE INDEX idx_document_chunks_doc_version
    ON document_chunks (document_id, doc_version);

-- 9. semantic_cache
CREATE TABLE semantic_cache (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rbac_scope       VARCHAR(128)        NOT NULL,
    scope_version    BIGINT              NOT NULL CONSTRAINT chk_semantic_cache_scope_version CHECK (scope_version >= 1),
    query_hash       VARCHAR(64)         NOT NULL,
    query_text       TEXT                NOT NULL,
    query_embedding  public.vector(1024) NOT NULL,
    response_content TEXT                NOT NULL,
    citations        JSONB               NOT NULL DEFAULT '[]'::jsonb,
    model_provider   VARCHAR(64),
    hit_count        INT                 NOT NULL DEFAULT 0 CONSTRAINT chk_semantic_cache_hit_count CHECK (hit_count >= 0),
    created_at       TIMESTAMPTZ         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMPTZ,
    CONSTRAINT uq_semantic_cache_scope_hash UNIQUE (rbac_scope, scope_version, query_hash)
);

CREATE INDEX idx_semantic_cache_embedding_hnsw
    ON semantic_cache USING hnsw (query_embedding public.vector_cosine_ops) WITH (m = 16, ef_construction = 64);
CREATE INDEX idx_semantic_cache_scope_lookup
    ON semantic_cache (rbac_scope, scope_version);
