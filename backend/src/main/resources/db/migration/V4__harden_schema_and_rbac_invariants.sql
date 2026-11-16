-- ============================================================================
-- DocuMind AI — V4 Schema Hardening, Chunk RBAC Sync & Parent Propagation
-- Additive post-V1..V3 migration preserving Flyway immutability on V1/V2.
-- ============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS uq_departments_code_lower
    ON departments (LOWER(code));

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower
    ON users (LOWER(email));

ALTER TABLE documents
    ADD CONSTRAINT uq_documents_storage_key UNIQUE (storage_key);

ALTER TABLE audit_logs
    ADD CONSTRAINT chk_audit_logs_cache_consistency CHECK (
        (cache_hit = FALSE AND cache_tier = 'NONE')
        OR (cache_hit = TRUE AND cache_tier IN ('L1', 'L2'))
    ) NOT VALID;

ALTER TABLE audit_logs
    VALIDATE CONSTRAINT chk_audit_logs_cache_consistency;

ALTER TABLE semantic_cache
    ADD CONSTRAINT uq_semantic_cache_scope_hash UNIQUE (rbac_scope, scope_version, query_hash);

CREATE OR REPLACE FUNCTION sync_document_chunk_metadata()
RETURNS TRIGGER AS $$
DECLARE
    v_document_id_text   TEXT;
    v_department_id_text TEXT;
    v_file_name_text     TEXT;
    v_access_level       TEXT;
    v_doc_version_text   TEXT;
    v_chunk_index_text   TEXT;
    v_page_number_text   TEXT;
    v_department_id      UUID;
    v_parent_dept_id     UUID;
    v_parent_access      VARCHAR(32);
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

    SELECT d.department_id, d.access_level
    INTO v_parent_dept_id, v_parent_access
    FROM documents d
    WHERE d.id = NEW.document_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Parent document % not found for document_chunks', NEW.document_id
            USING ERRCODE = '23503';
    END IF;

    IF v_department_id <> v_parent_dept_id OR v_access_level <> v_parent_access THEN
        RAISE EXCEPTION 'document_chunks RBAC metadata mismatches parent document %', NEW.document_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE document_chunks
    ADD CONSTRAINT chk_document_chunks_rbac_metadata CHECK (
        NULLIF(BTRIM(metadata->>'document_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'department_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'file_name'), '') IS NOT NULL
        AND (metadata->>'access_level') IN ('PUBLIC', 'DEPARTMENT', 'CONFIDENTIAL')
        AND NULLIF(BTRIM(metadata->>'doc_version'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'chunk_index'), '') IS NOT NULL
        AND (metadata ? 'page_number')
    ) NOT VALID;

ALTER TABLE document_chunks
    VALIDATE CONSTRAINT chk_document_chunks_rbac_metadata;

ALTER TABLE document_chunks
    ADD CONSTRAINT uq_document_chunks_doc_version_index UNIQUE (document_id, doc_version, chunk_index);

CREATE OR REPLACE FUNCTION propagate_document_rbac_to_chunks()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.department_id IS DISTINCT FROM OLD.department_id
        OR NEW.access_level IS DISTINCT FROM OLD.access_level THEN
        UPDATE document_chunks
        SET metadata = jsonb_set(
            jsonb_set(
                metadata,
                '{department_id}',
                to_jsonb(NEW.department_id::text),
                true
            ),
            '{access_level}',
            to_jsonb(NEW.access_level::text),
            true
        )
        WHERE document_id = NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_propagate_document_rbac_to_chunks
AFTER UPDATE OF department_id, access_level ON documents
FOR EACH ROW EXECUTE FUNCTION propagate_document_rbac_to_chunks();
