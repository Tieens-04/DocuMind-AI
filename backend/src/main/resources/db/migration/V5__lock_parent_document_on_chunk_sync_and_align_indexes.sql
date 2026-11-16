-- ============================================================================
-- DocuMind AI — V5 Additive Hardening: Parent Share Lock, File Name Parity,
-- Cache-Version Propagation, Case-Insensitive Index Alignment & TTL Index
-- ============================================================================

-- Redundant case-sensitive unique constraints superseded by V4 lower() unique indexes
ALTER TABLE departments DROP CONSTRAINT IF EXISTS uq_departments_code;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_email;

-- Partial index supporting TTL cleanup of expired semantic_cache entries
CREATE INDEX IF NOT EXISTS idx_semantic_cache_expires_at
    ON semantic_cache (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE OR REPLACE FUNCTION sync_document_chunk_metadata()
RETURNS TRIGGER AS $$
DECLARE
    v_document_id_text     TEXT;
    v_department_id_text   TEXT;
    v_file_name_text       TEXT;
    v_access_level         TEXT;
    v_doc_version_text     TEXT;
    v_chunk_index_text     TEXT;
    v_page_number_text     TEXT;
    v_department_id        UUID;
    v_parent_dept_id       UUID;
    v_parent_access        VARCHAR(32);
    v_parent_original_name VARCHAR(255);
BEGIN
    IF NEW.metadata IS NULL THEN
        RAISE EXCEPTION 'document_chunks.metadata must not be null'
            USING ERRCODE = '23514';
    END IF;

    v_document_id_text := NULLIF(BTRIM(NEW.metadata->>'document_id'), '');
    v_department_id_text := NULLIF(BTRIM(NEW.metadata->>'department_id'), '');
    v_file_name_text := NULLIF(BTRIM(NEW.metadata->>'file_name'), '');
    v_access_level := NEW.metadata->>'access_level';
    v_doc_version_text := NULLIF(BTRIM(NEW.metadata->>'doc_version'), '');
    v_chunk_index_text := NULLIF(BTRIM(NEW.metadata->>'chunk_index'), '');

    IF v_document_id_text IS NULL
        OR v_department_id_text IS NULL
        OR v_file_name_text IS NULL
        OR v_access_level IS NULL
        OR v_access_level NOT IN ('PUBLIC', 'DEPARTMENT', 'CONFIDENTIAL')
        OR v_doc_version_text IS NULL
        OR v_chunk_index_text IS NULL THEN
        RAISE EXCEPTION 'document_chunks.metadata must include non-blank document_id, department_id, file_name, access_level, doc_version, and chunk_index'
            USING ERRCODE = '23514';
    END IF;

    BEGIN
        NEW.document_id := v_document_id_text::uuid;
        v_department_id := v_department_id_text::uuid;
        NEW.doc_version := v_doc_version_text::int;
        NEW.chunk_index := v_chunk_index_text::int;
    EXCEPTION
        WHEN invalid_text_representation OR numeric_value_out_of_range THEN
            RAISE EXCEPTION 'Invalid UUID or integer format in document_chunks.metadata'
                USING ERRCODE = '23514';
    END;

    IF (NEW.metadata ? 'page_number')
        AND jsonb_typeof(NEW.metadata->'page_number') <> 'null'
        AND NULLIF(BTRIM(NEW.metadata->>'page_number'), '') IS NOT NULL THEN
        v_page_number_text := BTRIM(NEW.metadata->>'page_number');
        BEGIN
            NEW.page_number := v_page_number_text::int;
        EXCEPTION
            WHEN invalid_text_representation OR numeric_value_out_of_range THEN
                RAISE EXCEPTION 'Invalid page_number in document_chunks.metadata: %', v_page_number_text
                    USING ERRCODE = '23514';
        END;
    ELSE
        NEW.page_number := NULL;
        NEW.metadata := jsonb_set(NEW.metadata, '{page_number}', 'null'::jsonb, true);
    END IF;

    -- Lock parent row FOR SHARE so concurrent reclassification waits or is visible under READ COMMITTED
    SELECT d.department_id, d.access_level, d.original_name
    INTO v_parent_dept_id, v_parent_access, v_parent_original_name
    FROM documents d
    WHERE d.id = NEW.document_id
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Parent document % not found for document_chunks', NEW.document_id
            USING ERRCODE = '23503';
    END IF;

    IF v_department_id <> v_parent_dept_id OR v_access_level <> v_parent_access THEN
        RAISE EXCEPTION 'document_chunks RBAC metadata mismatches parent document %', NEW.document_id
            USING ERRCODE = '23514';
    END IF;

    IF (NEW.metadata->>'file_name') <> v_parent_original_name THEN
        RAISE EXCEPTION 'document_chunks file_name metadata mismatches parent document %', NEW.document_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION propagate_document_rbac_to_chunks()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.department_id IS DISTINCT FROM OLD.department_id
        OR NEW.access_level IS DISTINCT FROM OLD.access_level
        OR NEW.original_name IS DISTINCT FROM OLD.original_name THEN
        UPDATE document_chunks
        SET metadata = jsonb_set(
            jsonb_set(
                jsonb_set(
                    metadata,
                    '{department_id}',
                    to_jsonb(NEW.department_id::text),
                    true
                ),
                '{access_level}',
                to_jsonb(NEW.access_level::text),
                true
            ),
            '{file_name}',
            to_jsonb(NEW.original_name::text),
            true
        )
        WHERE document_id = NEW.id;
    END IF;

    IF NEW.department_id IS DISTINCT FROM OLD.department_id
        OR NEW.access_level IS DISTINCT FROM OLD.access_level THEN
        IF OLD.access_level = 'PUBLIC' OR NEW.access_level = 'PUBLIC' THEN
            UPDATE departments
            SET cache_version = cache_version + 1;
        ELSIF NEW.department_id IS DISTINCT FROM OLD.department_id THEN
            UPDATE departments
            SET cache_version = cache_version + 1
            WHERE id IN (OLD.department_id, NEW.department_id);
        ELSE
            UPDATE departments
            SET cache_version = cache_version + 1
            WHERE id = NEW.department_id;
        END IF;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_propagate_document_rbac_to_chunks ON documents;

CREATE TRIGGER trg_propagate_document_rbac_to_chunks
AFTER UPDATE OF department_id, access_level, original_name ON documents
FOR EACH ROW EXECUTE FUNCTION propagate_document_rbac_to_chunks();
