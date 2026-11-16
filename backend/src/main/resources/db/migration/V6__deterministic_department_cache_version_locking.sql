-- ============================================================================
-- DocuMind AI — V6 Deterministic Department Lock Ordering on Cache Propagation
-- Prevents 40P01 deadlocks on concurrent PUBLIC/cross-department updates and
-- invalidates semantic cache versions when original_name changes affect citations.
-- ============================================================================

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

        IF OLD.access_level = 'PUBLIC' OR NEW.access_level = 'PUBLIC' THEN
            PERFORM 1 FROM departments ORDER BY id FOR NO KEY UPDATE;
            UPDATE departments
            SET cache_version = cache_version + 1;
        ELSIF NEW.department_id IS DISTINCT FROM OLD.department_id THEN
            PERFORM 1 FROM departments
            WHERE id IN (OLD.department_id, NEW.department_id)
            ORDER BY id
            FOR NO KEY UPDATE;
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
