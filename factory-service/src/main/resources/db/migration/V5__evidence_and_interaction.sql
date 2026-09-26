-- V5__evidence_and_interaction.sql
--
-- Evidence & human interaction control plane for Jalon B2 (Tâche B2-T4). This
-- migration adds the tenant-scoped persistence surface for two things the
-- durable Factory needs once workflows can pause on a human decision:
--
--   * `workflow_evidence` — cross-cutting, append-only evidence log attached to
--     a workflow instance (Jalon B Amendment 3: evidence is captured as an
--     immutable, attributable record, never edited in place). It is written in
--     the same transaction as the state change it justifies.
--   * `human_interactions` — mutable aggregate root of a human decision node
--     (a question awaiting an answer). It carries an optimistic-locking
--     `revision` and a lifecycle `status`, and is updated in place as the human
--     answers / the interaction is closed.
--   * `human_interaction_events` — append-only event log recording the lifecycle
--     of a human interaction (creation, response, closure), one immutable row
--     per event.
--
-- Conventions (see `factory/infra/README.md`):
--   * Every row is tenant-scoped by a non-null `organization_id` (DEFAULT
--     'default' matching V1/V2/V3/V4).
--   * Identity is composite `(organization_id, workstream_id, namespace_id,
--     workflow_id, …)` so two tenants, workstreams or namespaces can never
--     collide on the same business key.
--   * Structural children carry the full instance identity so a composite FK can
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
-- workflow_evidence — append-only cross-cutting evidence log (Amendment 3)
--
-- One row per evidence item attached to a workflow instance. The composite FK
-- `(organization_id, workstream_id, namespace_id, workflow_id)` references the
-- `workflow_instances` primary key, so an evidence item can never be attached to
-- an instance of another tenant, workstream or namespace, and is removed with
-- its instance.
--
-- Append-only: there is deliberately NO `updated_at` column and NO update
-- trigger — evidence is immutable once written. A correction is a new evidence
-- row with its own `evidence_id`.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS workflow_evidence (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  evidence_id     VARCHAR(255) NOT NULL,
  evidence_type   VARCHAR(255) NOT NULL,
  source          VARCHAR(255) NOT NULL,
  producer        VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT workflow_evidence_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, evidence_id),
  CONSTRAINT workflow_evidence_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE
);

-- Evidence listing for an instance, newest last (chronological replay).
CREATE INDEX IF NOT EXISTS idx_workflow_evidence_instance
  ON workflow_evidence (organization_id, workstream_id, namespace_id, workflow_id, created_at);

-- --------------------------------------------------------------------------
-- human_interactions — mutable human decision aggregate root
--
-- Root aggregate of a human decision workflow node. Unlike the evidence log it
-- is updated in place as the human answers: `status` advances
-- `waiting → answered → closed` and `revision` is the optimistic-locking column
-- writers compare-and-swap on. The composite FK pins the interaction to its
-- workflow instance (Amendment 8).
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS human_interactions (
  organization_id  VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id    VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id     VARCHAR(255) NOT NULL,
  workflow_id      VARCHAR(255) NOT NULL,
  interaction_id   VARCHAR(255) NOT NULL,
  interaction_type VARCHAR(255) NOT NULL,
  status           VARCHAR(64)  NOT NULL DEFAULT 'waiting',
  revision         INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT human_interactions_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id),
  CONSTRAINT human_interactions_instance_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id)
    REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE,
  CONSTRAINT human_interactions_status_check CHECK (status IN ('waiting', 'answered', 'closed'))
);

-- Interaction lookup for an instance filtered by lifecycle status (pending
-- queue: `WHERE … AND status = 'waiting'`).
CREATE INDEX IF NOT EXISTS idx_human_interactions_instance
  ON human_interactions (organization_id, workstream_id, namespace_id, workflow_id, status);

-- --------------------------------------------------------------------------
-- human_interaction_events — append-only interaction event log
--
-- One immutable row per lifecycle event of a human interaction (creation,
-- response, closure). The composite FK references the `human_interactions`
-- primary key, so an event can never be attached to an interaction of another
-- tenant / workstream / namespace, and is removed with its interaction.
--
-- Append-only: NO `updated_at` column and NO update trigger.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS human_interaction_events (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  workflow_id     VARCHAR(255) NOT NULL,
  interaction_id  VARCHAR(255) NOT NULL,
  event_id        VARCHAR(255) NOT NULL,
  event_type      VARCHAR(255) NOT NULL,
  actor_id        VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT human_interaction_events_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id),
  CONSTRAINT human_interaction_events_interaction_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id)
    REFERENCES human_interactions (organization_id, workstream_id, namespace_id, workflow_id, interaction_id) ON DELETE CASCADE
);

-- Event log lookup for an interaction, oldest first (audit trail).
CREATE INDEX IF NOT EXISTS idx_human_interaction_events_interaction
  ON human_interaction_events (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, created_at);

-- --------------------------------------------------------------------------
-- updated_at trigger (human_interactions is the only V5 table carrying
-- `updated_at`; workflow_evidence and human_interaction_events are append-only
-- logs without one)
--
-- Amendment 2 readiness: this is a plain per-row `BEFORE UPDATE` touch with no
-- row lock beyond the row being updated and no side effects, so a single
-- PostgreSQL transaction can atomically combine the human decision, its
-- evidence row, the workflow step/instance state transition (revision
-- increment), the interaction closure and the outbox event.
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_human_interactions_updated_at ON human_interactions;
CREATE TRIGGER trg_human_interactions_updated_at
  BEFORE UPDATE ON human_interactions
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
