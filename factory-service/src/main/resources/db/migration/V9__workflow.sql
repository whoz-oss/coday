-- V9__workflow.sql
--
-- Workflow aggregate A6 (Workflow definitions / instances / projections /
-- evidence / human interactions / SSE) port to the Kotlin Spring Boot service.
--
-- This migration completes the durable surface the A6 workflow package needs and
-- that V1/V3/V5 did not yet declare:
--
--   * `workflow_projections` — the declarative WorkflowProjection v1/v2 document
--     (published through `PUT /api/factory/workflows/{id}/projection`). It is a
--     mutable aggregate carrying an optimistic-locking `revision`; the publication
--     command's optional `expectedRevision` is the compare-and-swap precondition
--     and is deliberately NOT stored/part of the canonical payload.
--   * `workflow_code_transitions` — append-only log of deterministic code/transition
--     executions (`POST /code-transitions`) attached to a governed instance.
--
-- Conventions (see `factory/infra/README.md` and V1/V3/V5):
--   * Every row is tenant-scoped by a non-null `organization_id` and
--     `workstream_id`; identity is composite
--     `(organization_id, workstream_id, namespace_id, workflow_id, …)` so two
--     tenants / workstreams / namespaces can never collide on a business key.
--   * Structural children carry the full instance identity so a composite FK can
--     never bridge two tenants (Jalon B Amendment 8). The database — not only the
--     application — rejects orphan or cross-tenant children and cascades their
--     removal with the parent.
--   * Append-only tables carry NO `updated_at` column and NO update trigger.
--   * Mutable aggregates carry a `revision` column used for optimistic locking;
--     `CHECK (revision >= 1)` keeps the counter meaningful.
--   * Everything is `IF NOT EXISTS` so the migration is idempotent and safe to
--     replay against an already-migrated database.
--   * `updated_at` is maintained by the shared `set_updated_at()` trigger
--     function created in V2.

-- --------------------------------------------------------------------------
-- workflow_projections — declarative WorkflowProjection v1/v2 store
--
-- One row per `(organization, workstream, namespace, workflowId)`. `revision`
-- starts at 1 for the first successful publication (the Node contract publishes
-- the creation with `expectedRevision: 0`) and increments on every semantically
-- changed publication. `projection_hash` is the SHA-256 of the canonical
-- (recursively key-sorted) projection JSON. The full snapshot lives in
-- `projection_json`; the governing execution references live beside it and are
-- excluded from `projection_hash`, exactly like the Node store.
--
-- `lifecycle_state` mirrors the Node `ACTIVE -> REMOVED -> ACTIVE` (restore) and
-- `REMOVED -> PURGED` lifecycle without ever deleting the projection row, so a
-- purged workflow keeps its tombstone for identity/audit purposes.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_projections (
  organization_id      VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id        VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id         VARCHAR(255) NOT NULL,
  workflow_id          VARCHAR(255) NOT NULL,
  schema_version       VARCHAR(8)   NOT NULL DEFAULT '1',
  revision             INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  projection_hash      VARCHAR(64)  NOT NULL,
  status               VARCHAR(64)  NOT NULL DEFAULT 'pending',
  projection_json      JSONB        NOT NULL DEFAULT '{}'::jsonb,
  instance_json        JSONB,
  governance_mode      VARCHAR(32),
  definition_version   VARCHAR(64),
  definition_hash      VARCHAR(64),
  relations_json       JSONB,
  controller_execution JSONB,
  lifecycle_state      VARCHAR(32)  NOT NULL DEFAULT 'active',
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_projections_pkey
    PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id),
  CONSTRAINT workflow_projections_hash_format CHECK (projection_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT workflow_projections_schema_version_check CHECK (schema_version IN ('1', '2')),
  CONSTRAINT workflow_projections_lifecycle_check CHECK (lifecycle_state IN ('active', 'removed', 'purged'))
);

-- Namespace listing by lifecycle state (cockpit list, removed list).
CREATE INDEX IF NOT EXISTS idx_workflow_projections_namespace
  ON workflow_projections (organization_id, workstream_id, namespace_id, lifecycle_state);

-- Direct workflow lookup across namespaces (identity resolution).
CREATE INDEX IF NOT EXISTS idx_workflow_projections_lookup
  ON workflow_projections (namespace_id, workflow_id);

DROP TRIGGER IF EXISTS trg_workflow_projections_updated_at ON workflow_projections;
CREATE TRIGGER trg_workflow_projections_updated_at
  BEFORE UPDATE ON workflow_projections
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

-- --------------------------------------------------------------------------
-- workflow_code_transitions — append-only deterministic code transition log
--
-- One immutable row per code/transition execution attached to a governed
-- instance. The composite FK `(organization_id, workstream_id, namespace_id,
-- workflow_id)` references the `workflow_instances` primary key, so a code
-- transition can never be attached to an instance of another tenant, workstream
-- or namespace, and is removed with its instance.
--
-- Append-only: NO `updated_at` column and NO update trigger.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_code_transitions (
  organization_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id     VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id      VARCHAR(255) NOT NULL,
  workflow_id       VARCHAR(255) NOT NULL,
  code_transition_id VARCHAR(255) NOT NULL,
  step_id           VARCHAR(255) NOT NULL,
  outcome           VARCHAR(64)  NOT NULL,
  exit_code         INTEGER,
  payload           JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_code_transitions_pkey
    PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, code_transition_id),
  CONSTRAINT workflow_code_transitions_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE
);

-- Code transition history for an instance, oldest first.
CREATE INDEX IF NOT EXISTS idx_workflow_code_transitions_instance
  ON workflow_code_transitions (organization_id, workstream_id, namespace_id, workflow_id, created_at);
