-- V1__init_workflow_pilot_schema.sql
--
-- Initial schema for the Factory persistence pilot: the two aggregates migrated
-- from the filesystem in Jalon B - Vague B1.
--
-- Conventions:
--   * Versioned migrations live in `factory/infra/migrations` and are applied by
--     Flyway in lexical version order (`V<version>__<description>.sql`).
--   * Every aggregate is tenant-scoped by `organization_id` (required) and
--     `workstream_id` (`workstream_level`; nullable for definitions, which can be
--     platform-, organization- or workstream-scoped).
--   * Mutable aggregates carry a `revision` column used for optimistic locking.
--   * The domain payload is stored verbatim as JSONB; the relational columns are
--     the query/identity surface only.

-- --------------------------------------------------------------------------
-- workflow_definitions (platform / organization / workstream scoped)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_definitions (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255),
  workflow_type   VARCHAR(255) NOT NULL,
  version         VARCHAR(64)  NOT NULL,
  definition_hash VARCHAR(64)  NOT NULL,
  definition_json JSONB        NOT NULL,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_definitions_pkey PRIMARY KEY (organization_id, workflow_type, version),
  CONSTRAINT workflow_definitions_hash_format CHECK (definition_hash ~ '^[0-9a-f]{64}$')
);

-- Scope lookup: which definitions exist for an organization/workstream pair.
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_scope
  ON workflow_definitions (organization_id, workstream_id);

-- The platform scope is the fallback identity `workflowType@version`; a definition
-- declared at platform level must be unique across organizations.
CREATE UNIQUE INDEX IF NOT EXISTS uq_workflow_definitions_platform
  ON workflow_definitions (workflow_type, version)
  WHERE organization_id = 'default' AND workstream_id IS NULL;

-- --------------------------------------------------------------------------
-- workflow_instances (governed workflow state + read projection)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_instances (
  organization_id       VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id         VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id          VARCHAR(255) NOT NULL,
  workflow_id           VARCHAR(255) NOT NULL,
  revision              INTEGER      NOT NULL DEFAULT 1,
  status                VARCHAR(64)  NOT NULL DEFAULT 'active',
  instance_json         JSONB        NOT NULL,
  projection_json       JSONB        NOT NULL,
  creation_command_hash VARCHAR(64),
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_instances_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id),
  CONSTRAINT workflow_instances_revision_positive CHECK (revision >= 1),
  CONSTRAINT workflow_instances_status_known CHECK (status IN ('active', 'removed'))
);

-- Namespace listing (live projections of a case/namespace boundary).
CREATE INDEX IF NOT EXISTS idx_workflow_instances_namespace
  ON workflow_instances (organization_id, workstream_id, namespace_id);

-- Direct workflow lookup across namespaces (identity resolution).
CREATE INDEX IF NOT EXISTS idx_workflow_instances_lookup
  ON workflow_instances (namespace_id, workflow_id);

-- Keep `updated_at` truthful regardless of which writer performs the update.
CREATE OR REPLACE FUNCTION set_workflow_instance_updated_at()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = CURRENT_TIMESTAMP;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_instances_updated_at ON workflow_instances;
CREATE TRIGGER trg_workflow_instances_updated_at
  BEFORE UPDATE ON workflow_instances
  FOR EACH ROW
  EXECUTE FUNCTION set_workflow_instance_updated_at();
