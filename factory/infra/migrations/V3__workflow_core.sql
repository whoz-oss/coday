-- V3__workflow_core.sql
--
-- Workflow core extension for Jalon B2 (Tâche B2-T2). This migration extends the
-- `workflow_definitions` pilot table created in V1 and introduces the durable,
-- tenant-scoped relational surface that backs the workflow engine:
--
--   * `workflow_definition_versions` — version history / revisions of a workflow
--     definition aggregate (mutable, optimistic-locked by `revision`).
--   * `workstream_workflow_grants` — explicit per-workstream workflow access
--     grants (Jalon B Amendment 1: visibility & grants).
--   * `workflow_step_states` — per-step execution state of a workflow instance.
--   * `workflow_transitions` — append-only log of state transitions.
--
-- Conventions (see `factory/infra/README.md`):
--   * Every row is tenant-scoped by a non-null `organization_id` (DEFAULT
--     'default' matching V1/V2).
--   * Structural children carry `workstream_id` / `namespace_id` / `workflow_id`
--     so a composite foreign key can never bridge two tenants (Jalon B
--     Amendment 8: composite FKs for tenant isolation). The database — not only
--     the application — rejects orphan or cross-tenant children.
--   * Mutable aggregates carry a `revision` column used for optimistic locking;
--     `CHECK (revision >= 1)` keeps the counter meaningful.
--   * The domain payload is stored verbatim as JSONB; the relational columns are
--     the query/identity surface only.
--   * `updated_at` is maintained by the shared `set_updated_at()` trigger function
--     created in V2.

-- --------------------------------------------------------------------------
-- workflow_definitions — visibility & grant surface (Amendment 1)
--
-- V1 created the table with `organization_id`, `workstream_id`, `workflow_type`,
-- `version`, `definition_hash`, `definition_json` and `created_at`, with the PK
-- `(organization_id, workflow_type, version)`. V3 adds the visibility scope and
-- the optional target workstream used when `visibility = 'workstream'`.
-- --------------------------------------------------------------------------

-- Reassert the tenant default defensively: V1 already declares it NOT NULL
-- DEFAULT 'default', this keeps V3 self-describing without duplicating the column.
ALTER TABLE workflow_definitions
  ALTER COLUMN organization_id SET DEFAULT 'default';

ALTER TABLE workflow_definitions
  ADD COLUMN IF NOT EXISTS visibility VARCHAR(32) NOT NULL DEFAULT 'organization';

ALTER TABLE workflow_definitions
  ADD COLUMN IF NOT EXISTS owner_workstream_id VARCHAR(255);

ALTER TABLE workflow_definitions
  ADD CONSTRAINT workflow_definitions_visibility_check
  CHECK (visibility IN ('platform', 'organization', 'workstream'));

-- Scope lookup: which definitions are visible to an organization/workstream pair.
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_visibility
  ON workflow_definitions (organization_id, visibility);

-- Target workstream resolution when `visibility = 'workstream'`.
CREATE INDEX IF NOT EXISTS idx_workflow_definitions_owner_workstream
  ON workflow_definitions (organization_id, owner_workstream_id);

-- --------------------------------------------------------------------------
-- workflow_definition_versions (version history of a definition)
--
-- One row per (organization, workflow type, version, revision). The composite FK
-- pins the history to the exact definition row it belongs to; a cross-tenant or
-- orphan revision is impossible at the database level.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_definition_versions (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workflow_type   VARCHAR(255) NOT NULL,
  version         VARCHAR(64)  NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  definition_hash VARCHAR(64),
  definition_json JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_definition_versions_pkey PRIMARY KEY (organization_id, workflow_type, version, revision),
  CONSTRAINT workflow_definition_versions_definition_fk
    FOREIGN KEY (organization_id, workflow_type, version)
    REFERENCES workflow_definitions (organization_id, workflow_type, version) ON DELETE CASCADE
);

-- Definition lookup across revisions.
CREATE INDEX IF NOT EXISTS idx_workflow_definition_versions_definition
  ON workflow_definition_versions (organization_id, workflow_type, version);

-- --------------------------------------------------------------------------
-- workstream_workflow_grants (explicit access grants — Amendment 1)
--
-- The grant is scoped to a single workstream: the composite FK to
-- `workstreams (organization_id, workstream_id)` guarantees the workstream lives
-- in the same organization as the grant. `version` is an optional pin to a
-- specific definition version; `NULL` grants every version of the workflow type.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workstream_workflow_grants (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL,
  workflow_type   VARCHAR(255) NOT NULL,
  version         VARCHAR(64),
  enabled         BOOLEAN      NOT NULL DEFAULT true,
  configuration   JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workstream_workflow_grants_pkey PRIMARY KEY (organization_id, workstream_id, workflow_type),
  CONSTRAINT workstream_workflow_grants_workstream_fk
    FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams (organization_id, workstream_id) ON DELETE CASCADE
);

-- Enabled grant lookup for a workstream.
CREATE INDEX IF NOT EXISTS idx_workstream_workflow_grants_workstream
  ON workstream_workflow_grants (organization_id, workstream_id, enabled);

-- --------------------------------------------------------------------------
-- workflow_step_states (per-step execution state, Amendment 8)
--
-- The composite FK `(organization_id, workstream_id, namespace_id, workflow_id)`
-- references the `workflow_instances` primary key, so a step state can never be
-- attached to an instance of another tenant, workstream or namespace, and is
-- removed with its instance.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_step_states (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  step_id         VARCHAR(255) NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  status          VARCHAR(64)  NOT NULL DEFAULT 'pending',
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_step_states_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id),
  CONSTRAINT workflow_step_states_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE
);

-- Step listing for an instance.
CREATE INDEX IF NOT EXISTS idx_workflow_step_states_instance
  ON workflow_step_states (organization_id, workstream_id, namespace_id, workflow_id);

-- --------------------------------------------------------------------------
-- workflow_transitions (append-only transition log, Amendment 8)
--
-- Same composite FK as `workflow_step_states`: transition rows are tenant- and
-- instance-scoped, orphan or cross-tenant records are rejected by the database.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_transitions (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  transition_id   VARCHAR(255) NOT NULL,
  from_step_id    VARCHAR(255),
  to_step_id      VARCHAR(255) NOT NULL,
  event_name      VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_transitions_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, transition_id),
  CONSTRAINT workflow_transitions_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE
);

-- Transition log lookup for an instance, oldest first.
CREATE INDEX IF NOT EXISTS idx_workflow_transitions_instance
  ON workflow_transitions (organization_id, workstream_id, namespace_id, workflow_id, created_at);

-- --------------------------------------------------------------------------
-- updated_at triggers (one per table carrying `updated_at`)
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_workflow_definition_versions_updated_at ON workflow_definition_versions;
CREATE TRIGGER trg_workflow_definition_versions_updated_at
  BEFORE UPDATE ON workflow_definition_versions
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_workstream_workflow_grants_updated_at ON workstream_workflow_grants;
CREATE TRIGGER trg_workstream_workflow_grants_updated_at
  BEFORE UPDATE ON workstream_workflow_grants
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_workflow_step_states_updated_at ON workflow_step_states;
CREATE TRIGGER trg_workflow_step_states_updated_at
  BEFORE UPDATE ON workflow_step_states
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
