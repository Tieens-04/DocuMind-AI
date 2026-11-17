-- ============================================================================
-- DocuMind AI — V7 Strip JSON Nulls in document_chunks.metadata
-- Spring AI 1.0.3 PgVectorStore$DocumentRowMapper passes deserialized metadata
-- Maps into Document.Builder, which rejects any null value via Assert.noNullElements.
-- Omitting null keys (notably page_number on DOCX/MD/TXT) keeps SQL page_number
-- NULL while preventing similaritySearch IllegalArgumentException crashes.
-- ============================================================================

ALTER TABLE document_chunks
    DROP CONSTRAINT IF EXISTS chk_document_chunks_rbac_metadata;

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

    NEW.metadata := jsonb_strip_nulls(NEW.metadata);

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
        NEW.metadata := NEW.metadata - 'page_number';
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

UPDATE document_chunks
SET metadata = jsonb_strip_nulls(metadata)
WHERE metadata IS DISTINCT FROM jsonb_strip_nulls(metadata);

ALTER TABLE document_chunks
    ADD CONSTRAINT chk_document_chunks_rbac_metadata CHECK (
        NULLIF(BTRIM(metadata->>'document_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'department_id'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'file_name'), '') IS NOT NULL
        AND (metadata->>'access_level') IN ('PUBLIC', 'DEPARTMENT', 'CONFIDENTIAL')
        AND NULLIF(BTRIM(metadata->>'doc_version'), '') IS NOT NULL
        AND NULLIF(BTRIM(metadata->>'chunk_index'), '') IS NOT NULL
        AND metadata = jsonb_strip_nulls(metadata)
    ) NOT VALID;

ALTER TABLE document_chunks
    VALIDATE CONSTRAINT chk_document_chunks_rbac_metadata;
