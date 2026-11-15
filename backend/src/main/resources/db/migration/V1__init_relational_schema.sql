-- ============================================================================
-- DocuMind AI — V1 Relational Schema (7 Core Tables)
-- PostgreSQL 16
-- ============================================================================

CREATE OR REPLACE FUNCTION set_updated_at_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- 1. departments
CREATE TABLE departments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(32)  NOT NULL CONSTRAINT uq_departments_code UNIQUE,
    name          VARCHAR(120) NOT NULL,
    description   TEXT,
    cache_version BIGINT       NOT NULL DEFAULT 1 CONSTRAINT chk_departments_cache_version CHECK (cache_version >= 1),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_departments_code_lower ON departments (LOWER(code));

CREATE TRIGGER trg_departments_updated_at
BEFORE UPDATE ON departments
FOR EACH ROW EXECUTE FUNCTION set_updated_at_timestamp();

-- 2. users
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id UUID         NOT NULL CONSTRAINT fk_users_department REFERENCES departments(id) ON DELETE RESTRICT,
    email         VARCHAR(255) NOT NULL CONSTRAINT uq_users_email UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(32)  NOT NULL CONSTRAINT chk_users_role CHECK (role IN ('SYSTEM_ADMIN', 'DEPARTMENT_MANAGER', 'STANDARD_EMPLOYEE')),
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_users_email_lower ON users (LOWER(email));
CREATE INDEX idx_users_department_id ON users (department_id);
CREATE INDEX idx_users_role ON users (role);

CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION set_updated_at_timestamp();

-- 3. refresh_tokens
CREATE TABLE refresh_tokens (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL CONSTRAINT fk_refresh_tokens_user REFERENCES users(id) ON DELETE CASCADE,
    token_hash       VARCHAR(64) NOT NULL CONSTRAINT uq_refresh_tokens_hash UNIQUE,
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ,
    replaced_by_hash VARCHAR(64),
    user_agent       VARCHAR(512),
    ip_address       VARCHAR(45),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refresh_tokens_user_revoked ON refresh_tokens (user_id, revoked_at);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- 4. documents
CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    department_id   UUID         NOT NULL CONSTRAINT fk_documents_department REFERENCES departments(id) ON DELETE RESTRICT,
    uploaded_by     UUID         CONSTRAINT fk_documents_uploaded_by REFERENCES users(id) ON DELETE SET NULL,
    original_name   VARCHAR(255) NOT NULL,
    storage_key     VARCHAR(512) NOT NULL CONSTRAINT uq_documents_storage_key UNIQUE,
    file_extension  VARCHAR(16)  NOT NULL CONSTRAINT chk_documents_extension CHECK (file_extension IN ('pdf', 'docx', 'txt', 'md')),
    mime_type       VARCHAR(128) NOT NULL,
    file_size_bytes BIGINT       NOT NULL CONSTRAINT chk_documents_size CHECK (file_size_bytes > 0 AND file_size_bytes <= 52428800),
    file_hash       VARCHAR(64)  NOT NULL,
    access_level    VARCHAR(32)  NOT NULL CONSTRAINT chk_documents_access_level CHECK (access_level IN ('PUBLIC', 'DEPARTMENT', 'CONFIDENTIAL')),
    status          VARCHAR(32)  NOT NULL CONSTRAINT chk_documents_status CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    current_version INT          NOT NULL DEFAULT 1 CONSTRAINT chk_documents_version CHECK (current_version >= 1),
    chunk_count     INT          NOT NULL DEFAULT 0 CONSTRAINT chk_documents_chunk_count CHECK (chunk_count >= 0),
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_documents_department_hash UNIQUE (department_id, file_hash)
);

CREATE INDEX idx_documents_dept_access_status ON documents (department_id, access_level, status);
CREATE INDEX idx_documents_status_created ON documents (status, created_at DESC);

CREATE TRIGGER trg_documents_updated_at
BEFORE UPDATE ON documents
FOR EACH ROW EXECUTE FUNCTION set_updated_at_timestamp();

-- 5. chat_sessions
CREATE TABLE chat_sessions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL CONSTRAINT fk_chat_sessions_user REFERENCES users(id) ON DELETE CASCADE,
    title      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_sessions_user_updated ON chat_sessions (user_id, updated_at DESC);

CREATE TRIGGER trg_chat_sessions_updated_at
BEFORE UPDATE ON chat_sessions
FOR EACH ROW EXECUTE FUNCTION set_updated_at_timestamp();

-- 6. chat_messages
CREATE TABLE chat_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID        NOT NULL CONSTRAINT fk_chat_messages_session REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role              VARCHAR(20) NOT NULL CONSTRAINT chk_chat_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    content           TEXT        NOT NULL,
    rewritten_query   TEXT,
    citations         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    abstained         BOOLEAN     NOT NULL DEFAULT FALSE,
    cache_tier        VARCHAR(16) NOT NULL DEFAULT 'NONE' CONSTRAINT chk_chat_messages_cache_tier CHECK (cache_tier IN ('NONE', 'L1', 'L2')),
    prompt_tokens     INT         NOT NULL DEFAULT 0 CONSTRAINT chk_chat_messages_prompt_tokens CHECK (prompt_tokens >= 0),
    completion_tokens INT         NOT NULL DEFAULT 0 CONSTRAINT chk_chat_messages_completion_tokens CHECK (completion_tokens >= 0),
    latency_ms        BIGINT      CONSTRAINT chk_chat_messages_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    model_provider    VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chat_messages_session_created ON chat_messages (session_id, created_at ASC);

-- 7. audit_logs
CREATE TABLE audit_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        CONSTRAINT fk_audit_logs_user REFERENCES users(id) ON DELETE SET NULL,
    department_id     UUID        CONSTRAINT fk_audit_logs_department REFERENCES departments(id) ON DELETE SET NULL,
    session_id        UUID        CONSTRAINT fk_audit_logs_session REFERENCES chat_sessions(id) ON DELETE SET NULL,
    action_type       VARCHAR(40) NOT NULL CONSTRAINT chk_audit_logs_action_type CHECK (action_type IN ('CHAT_QUERY', 'DOC_UPLOAD', 'DOC_REINDEX', 'DOC_DELETE', 'AUTH_LOGIN', 'AUTH_LOGOUT', 'RATE_LIMITED')),
    query_text        TEXT,
    abstained         BOOLEAN     NOT NULL DEFAULT FALSE,
    cache_hit         BOOLEAN     NOT NULL DEFAULT FALSE,
    cache_tier        VARCHAR(16) NOT NULL DEFAULT 'NONE' CONSTRAINT chk_audit_logs_cache_tier CHECK (cache_tier IN ('NONE', 'L1', 'L2')),
    prompt_tokens     INT         NOT NULL DEFAULT 0 CONSTRAINT chk_audit_logs_prompt_tokens CHECK (prompt_tokens >= 0),
    completion_tokens INT         NOT NULL DEFAULT 0 CONSTRAINT chk_audit_logs_completion_tokens CHECK (completion_tokens >= 0),
    latency_ms        BIGINT      NOT NULL DEFAULT 0 CONSTRAINT chk_audit_logs_latency CHECK (latency_ms >= 0),
    provider          VARCHAR(64),
    status_code       INT         NOT NULL DEFAULT 200,
    ip_address        VARCHAR(45),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_audit_logs_cache_consistency CHECK (
        (cache_hit = FALSE AND cache_tier = 'NONE')
        OR (cache_hit = TRUE AND cache_tier IN ('L1', 'L2'))
    )
);

CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_dept_created ON audit_logs (department_id, created_at DESC);
CREATE INDEX idx_audit_logs_action_created ON audit_logs (action_type, created_at DESC);
