-- V6__artifacts_oracle_agentstep.sql
--
-- Artifacts, oracle execution, agent-step attempt aggregate, and the
-- worker / environment reservation skeletons for Jalon B2 (Tâche B2-T5). This
-- migration adds the tenant-scoped persistence surface for:
--
--   * `artifacts` — mutable, content-addressed binary/large-object metadata
--     with three orthogonal status dimensions: `availability_status` (where the
--     bytes are), `retention_status` (retention window) and `legal_hold` (a
--     compliance lock). Jalon B Amendment 9 (orthogonal status dimensions) &
--     Amendment 5 (retention, purge and legal hold).
--   * `oracle_executions` — mutable aggregate root of one oracle run, with an
--     optimistic-locking `revision` and optional references to the evidence
--     and artifact it produced.
--   * `agent_step_attempts` (mutable root) plus the append-only
--     `agent_step_attempt_events`, `agent_step_results` and `result_capabilities`
--     tables. Jalon B Amendment 4: an attempt is a single aggregate whose parts
--     (attempt state, events, result, declared capabilities) share one
--     transactional boundary.
--   * `work_units`, `work_environments`, `workers` and `work_unit_leases` —
--     identifier/relationship reservations for the worker & environment control
--     plane. Jalon B Amendment 6: these are deliberately SKELETONS only (no
--     scheduler, no active lease/fencing/heartbeat mechanics).
--
-- Conventions (see `factory/infra/README.md`):
--   * Every row is tenant-scoped by a non-null `organization_id` (DEFAULT
--     'default' matching V1..V5).
--   * Identity is composite `(organization_id, workstream_id, namespace_id,
--     workflow_id, …)` so two tenants, workstreams or namespaces can never
--     collide on the same business key.
--   * Structural children carry the full parent identity so a composite FK can
--     never bridge two tenants (Jalon B Amendment 8: composite FKs for tenant
--     isolation). The database — not only the application — rejects orphan or
--     cross-tenant children and cascades their removal with the parent.
--   * Append-only tables carry NO `updated_at` column and NO update trigger; a
--     correction is a new row, never an in-place edit.
--   * Mutable aggregates carry a `revision` column used for optimistic locking;
--     `CHECK (revision >= 1)` keeps the counter meaningful.
--   * The domain payload is stored verbatim as JSONB; the relational columns are
--     the query/identity surface only.
--   * `updated_at` is maintained by the shared `set_updated_at()` trigger
--     function created in V2.

-- --------------------------------------------------------------------------
-- artifacts — mutable content-addressed artifact metadata (Amendment 9 & 5)
--
-- The three status dimensions are ORTHOGONAL and must not be collapsed into a
-- single enum:
--   * availability_status — where the bytes are (pending / uploading /
--     available / unavailable / purged);
--   * retention_status    — whether the retention window is still active;
--   * legal_hold          — a compliance lock that forbids purge entirely.
--
-- The `artifacts_legal_hold_purge_check` CHECK encodes the golden rule: an
-- artifact under legal hold can never be marked `purged`. Purging an artifact
-- keeps the row (so the reference and its `payload` metadata survive) and only
-- moves `availability_status` to `purged`, stamping `purged_at` / `purge_reason`.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS artifacts (
  organization_id     VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id       VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id        VARCHAR(255) NOT NULL,
  workflow_id         VARCHAR(255) NOT NULL,
  artifact_id         VARCHAR(255) NOT NULL,
  -- Orthogonal dimension 1: availability of the underlying bytes.
  availability_status VARCHAR(64)  NOT NULL DEFAULT 'pending',
  -- Orthogonal dimension 2: retention window state.
  retention_status    VARCHAR(64)  NOT NULL DEFAULT 'active',
  -- Orthogonal dimension 3: compliance lock (independent of the two above).
  legal_hold          BOOLEAN      NOT NULL DEFAULT FALSE,
  -- Retention / purge / hold bookkeeping.
  retention_until     TIMESTAMPTZ,
  purged_at           TIMESTAMPTZ,
  purge_reason        TEXT,
  legal_hold_reason   TEXT,
  legal_hold_set_at   TIMESTAMPTZ,
  -- Metadata & immutable storage coordinates.
  content_hash        VARCHAR(255) NOT NULL,
  size                BIGINT       NOT NULL,
  content_type        VARCHAR(255) NOT NULL,
  storage_key         VARCHAR(1024) NOT NULL,
  -- Domain metadata kept verbatim, including after a purge.
  payload             JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT artifacts_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, artifact_id),
  CONSTRAINT artifacts_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE,
  CONSTRAINT artifacts_availability_status_check
    CHECK (availability_status IN ('pending', 'uploading', 'available', 'unavailable', 'purged')),
  CONSTRAINT artifacts_retention_status_check
    CHECK (retention_status IN ('active', 'expired')),
  -- Golden rule: a legal hold forbids purge (Amendment 5).
  CONSTRAINT artifacts_legal_hold_purge_check
    CHECK (NOT (legal_hold = TRUE AND availability_status = 'purged'))
);

-- Instance artifact listing filtered by availability status.
CREATE INDEX IF NOT EXISTS idx_artifacts_instance
  ON artifacts (organization_id, workstream_id, namespace_id, workflow_id, availability_status);

-- --------------------------------------------------------------------------
-- oracle_executions — mutable oracle execution aggregate
--
-- One row per oracle run attached to a workflow instance. `evidence_id` and
-- `artifact_id` are optional soft references to the evidence / artifact the run
-- produced (the relational models live in `workflow_evidence` and `artifacts`).
-- `revision` is the optimistic-locking column writers compare-and-swap on.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oracle_executions (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  execution_id    VARCHAR(255) NOT NULL,
  oracle_id       VARCHAR(255) NOT NULL,
  status          VARCHAR(64)  NOT NULL DEFAULT 'running',
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  evidence_id     VARCHAR(255),
  artifact_id     VARCHAR(255),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT oracle_executions_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, execution_id),
  CONSTRAINT oracle_executions_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE,
  CONSTRAINT oracle_executions_status_check CHECK (status IN ('running', 'succeeded', 'failed', 'cancelled'))
);

-- Instance oracle executions filtered by lifecycle status.
CREATE INDEX IF NOT EXISTS idx_oracle_executions_instance
  ON oracle_executions (organization_id, workstream_id, namespace_id, workflow_id, status);

-- --------------------------------------------------------------------------
-- agent_step_attempts — mutable attempt aggregate root (Amendment 4)
--
-- Root of the per-attempt aggregate: the attempt state (this table), its event
-- log, its result(s) and the declared capabilities all share the full attempt
-- identity `(organization_id, workstream_id, namespace_id, workflow_id, step_id,
-- attempt_id)` so they can be committed in a single PostgreSQL transaction.
-- `idempotency_key` soft-links to `idempotency_records` (V4) when the attempt
-- was admitted idempotently.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_step_attempts (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  step_id         VARCHAR(255) NOT NULL,
  attempt_id      VARCHAR(255) NOT NULL,
  agent_id        VARCHAR(255) NOT NULL,
  status          VARCHAR(64)  NOT NULL DEFAULT 'running',
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  idempotency_key VARCHAR(255),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT agent_step_attempts_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id),
  CONSTRAINT agent_step_attempts_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE,
  CONSTRAINT agent_step_attempts_status_check
    CHECK (status IN ('running', 'completed', 'failed', 'timed_out', 'cancelled'))
);

-- Step attempt listing filtered by lifecycle status.
CREATE INDEX IF NOT EXISTS idx_agent_step_attempts_step
  ON agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, status);

-- --------------------------------------------------------------------------
-- agent_step_attempt_events — append-only attempt event log
--
-- One immutable row per lifecycle event of an attempt. The composite FK
-- references the `agent_step_attempts` primary key, so an event can never be
-- attached to an attempt of another tenant / workstream / namespace / step, and
-- is removed with its attempt.
--
-- Append-only: NO `updated_at` column and NO update trigger.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_step_attempt_events (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  step_id         VARCHAR(255) NOT NULL,
  attempt_id      VARCHAR(255) NOT NULL,
  event_id        VARCHAR(255) NOT NULL,
  event_type      VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT agent_step_attempt_events_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, event_id),
  CONSTRAINT agent_step_attempt_events_attempt_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)
    REFERENCES agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id) ON DELETE CASCADE
);

-- Chronological event log lookup for an attempt.
CREATE INDEX IF NOT EXISTS idx_agent_step_attempt_events_attempt
  ON agent_step_attempt_events (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, created_at);

-- --------------------------------------------------------------------------
-- agent_step_results — append-only attempt results
--
-- A physically distinct table inside the attempt aggregate (Amendment 4): one
-- immutable row per result produced by an attempt. `semantic_signature` supports
-- RESULT_SEMANTIC_COLLISION detection; `result_status` records success / failure
-- / a detected collision.
--
-- Append-only: NO `updated_at` column and NO update trigger.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_step_results (
  organization_id    VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id      VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id       VARCHAR(255) NOT NULL,
  workflow_id        VARCHAR(255) NOT NULL,
  step_id            VARCHAR(255) NOT NULL,
  attempt_id         VARCHAR(255) NOT NULL,
  result_id          VARCHAR(255) NOT NULL,
  result_status      VARCHAR(64)  NOT NULL,
  semantic_signature VARCHAR(255),
  payload            JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT agent_step_results_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id),
  CONSTRAINT agent_step_results_attempt_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id)
    REFERENCES agent_step_attempts (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id) ON DELETE CASCADE,
  CONSTRAINT agent_step_results_status_check CHECK (result_status IN ('success', 'failure', 'collision_detected'))
);

-- Result lookup for an attempt, oldest first.
CREATE INDEX IF NOT EXISTS idx_agent_step_results_attempt
  ON agent_step_results (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, created_at);

-- --------------------------------------------------------------------------
-- result_capabilities — append-only declared capabilities/effects of a result
--
-- One immutable row per capability (side effect / effect class) declared by a
-- result. The composite FK references the `agent_step_results` primary key, so a
-- capability can never be attached to a result of another tenant / workstream /
-- namespace / attempt, and is removed with its result.
--
-- Append-only: NO `updated_at` column and NO update trigger.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS result_capabilities (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  step_id         VARCHAR(255) NOT NULL,
  attempt_id      VARCHAR(255) NOT NULL,
  result_id       VARCHAR(255) NOT NULL,
  capability_id   VARCHAR(255) NOT NULL,
  capability_type VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT result_capabilities_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id, capability_id),
  CONSTRAINT result_capabilities_result_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id)
    REFERENCES agent_step_results (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id) ON DELETE CASCADE
);

-- Capability listing for a result.
CREATE INDEX IF NOT EXISTS idx_result_capabilities_result
  ON result_capabilities (organization_id, workstream_id, namespace_id, workflow_id, step_id, attempt_id, result_id);

-- ==========================================================================
-- Worker / environment reservation skeletons (Amendment 6)
--
-- Identifier and relationship reservations ONLY: no scheduler, no lease
-- acquisition / renewal / expiry mechanics, no fencing tokens and no heartbeats
-- (those belong to Jalon C). The tables below reserve the composite identities
-- and the tenant-scoped foreign keys so the control plane can be built on top
-- without another identity migration.
-- ==========================================================================

-- --------------------------------------------------------------------------
-- work_units — mutable work unit / compute task skeleton
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_units (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  work_unit_id    VARCHAR(255) NOT NULL,
  unit_type       VARCHAR(255) NOT NULL,
  status          VARCHAR(64)  NOT NULL DEFAULT 'created',
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT work_units_pkey PRIMARY KEY (organization_id, workstream_id, work_unit_id),
  CONSTRAINT work_units_workstream_fk
    FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams (organization_id, workstream_id) ON DELETE CASCADE,
  CONSTRAINT work_units_status_check CHECK (status IN ('created', 'assigned', 'running', 'completed', 'failed', 'cancelled'))
);

-- --------------------------------------------------------------------------
-- work_environments — mutable execution environment / sandbox skeleton
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_environments (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  environment_id  VARCHAR(255) NOT NULL,
  env_type        VARCHAR(255) NOT NULL,
  status          VARCHAR(64)  NOT NULL DEFAULT 'provisioning',
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT work_environments_pkey PRIMARY KEY (organization_id, workstream_id, environment_id),
  CONSTRAINT work_environments_workstream_fk
    FOREIGN KEY (organization_id, workstream_id)
    REFERENCES workstreams (organization_id, workstream_id) ON DELETE CASCADE,
  CONSTRAINT work_environments_status_check CHECK (status IN ('provisioning', 'ready', 'busy', 'decommissioned'))
);

-- --------------------------------------------------------------------------
-- workers — mutable worker node skeleton (organization-scoped)
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workers (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  worker_id       VARCHAR(255) NOT NULL,
  worker_type     VARCHAR(255) NOT NULL,
  status          VARCHAR(64)  NOT NULL DEFAULT 'offline',
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workers_pkey PRIMARY KEY (organization_id, worker_id),
  CONSTRAINT workers_organization_fk
    FOREIGN KEY (organization_id)
    REFERENCES organizations (organization_id) ON DELETE CASCADE,
  CONSTRAINT workers_status_check CHECK (status IN ('offline', 'idle', 'busy', 'maintenance'))
);

-- --------------------------------------------------------------------------
-- work_unit_leases — appointment / reservation log (no active lease mechanics)
--
-- Records that a work unit was appointed a worker and (optionally) an
-- environment. Deliberately carries no `updated_at` and no active lease
-- mechanics: status transitions are appended as new rows in Jalon C.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS work_unit_leases (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  work_unit_id    VARCHAR(255) NOT NULL,
  lease_id        VARCHAR(255) NOT NULL,
  worker_id       VARCHAR(255) NOT NULL,
  environment_id  VARCHAR(255),
  status          VARCHAR(64)  NOT NULL DEFAULT 'active',
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT work_unit_leases_pkey PRIMARY KEY (organization_id, workstream_id, work_unit_id, lease_id),
  CONSTRAINT work_unit_leases_work_unit_fk
    FOREIGN KEY (organization_id, workstream_id, work_unit_id)
    REFERENCES work_units (organization_id, workstream_id, work_unit_id) ON DELETE CASCADE,
  CONSTRAINT work_unit_leases_status_check CHECK (status IN ('active', 'released', 'expired'))
);

-- --------------------------------------------------------------------------
-- updated_at triggers (one per mutable V6 table carrying `updated_at`; the
-- append-only tables agent_step_attempt_events, agent_step_results,
-- result_capabilities and work_unit_leases deliberately have none)
--
-- Amendment 2 readiness: each trigger is a plain per-row `BEFORE UPDATE` touch
-- with no row lock beyond the row being updated and no side effects, so a single
-- PostgreSQL transaction can atomically commit an attempt together with its
-- events, result(s), capabilities and the outbox event.
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_artifacts_updated_at ON artifacts;
CREATE TRIGGER trg_artifacts_updated_at
  BEFORE UPDATE ON artifacts
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_oracle_executions_updated_at ON oracle_executions;
CREATE TRIGGER trg_oracle_executions_updated_at
  BEFORE UPDATE ON oracle_executions
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_agent_step_attempts_updated_at ON agent_step_attempts;
CREATE TRIGGER trg_agent_step_attempts_updated_at
  BEFORE UPDATE ON agent_step_attempts
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_work_units_updated_at ON work_units;
CREATE TRIGGER trg_work_units_updated_at
  BEFORE UPDATE ON work_units
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_work_environments_updated_at ON work_environments;
CREATE TRIGGER trg_work_environments_updated_at
  BEFORE UPDATE ON work_environments
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_workers_updated_at ON workers;
CREATE TRIGGER trg_workers_updated_at
  BEFORE UPDATE ON workers
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
