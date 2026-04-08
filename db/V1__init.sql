-- =============================================================================
-- Pentaho Migration Framework — PostgreSQL Schema
-- =============================================================================

-- Enable pgcrypto for gen_random_uuid() on PostgreSQL < 16.
-- On PostgreSQL 16+ gen_random_uuid() is built-in; this line is a no-op.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- projects
--   One row per uploaded Pentaho project (1 KJB + its KTR files).
-- ---------------------------------------------------------------------------
CREATE TABLE projects (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    -- UPLOADED | CONVERTING | CONVERTED | CONVERSION_FAILED
    status      VARCHAR(30)  NOT NULL DEFAULT 'UPLOADED',
    error_message TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_projects_status ON projects (status);

-- ---------------------------------------------------------------------------
-- project_files
--   Raw file content for every uploaded file belonging to a project.
--   Stored as bytea so conversion can be re-run without re-upload.
-- ---------------------------------------------------------------------------
CREATE TABLE project_files (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    filename    VARCHAR(500) NOT NULL,
    -- KJB | KTR
    file_type   VARCHAR(10)  NOT NULL CHECK (file_type IN ('KJB', 'KTR')),
    content     BYTEA        NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_project_files_project ON project_files (project_id);

-- ---------------------------------------------------------------------------
-- yaml_definitions
--   Generated YAML job/transformation definitions, one row per converted file.
-- ---------------------------------------------------------------------------
CREATE TABLE yaml_definitions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    filename    VARCHAR(500) NOT NULL,   -- e.g. "daily_etl.yaml"
    -- JOB | TRANSFORMATION  (derived from the source extension)
    definition_type VARCHAR(20) NOT NULL CHECK (definition_type IN ('JOB', 'TRANSFORMATION')),
    content     TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_yaml_definitions_project ON yaml_definitions (project_id);

-- ---------------------------------------------------------------------------
-- job_executions
--   One row per "Run Job" invocation for a project.
-- ---------------------------------------------------------------------------
CREATE TABLE job_executions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- PENDING | RUNNING | COMPLETED | FAILED
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    duration_ms   BIGINT,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_executions_project ON job_executions (project_id);
CREATE INDEX idx_job_executions_status  ON job_executions (status);

-- ---------------------------------------------------------------------------
-- Trigger: automatically update projects.updated_at on every row update
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_projects_updated_at
    BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
