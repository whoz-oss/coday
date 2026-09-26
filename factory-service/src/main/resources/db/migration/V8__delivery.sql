-- V8__delivery.sql
--
-- Delivery aggregate (Jalon A5) persistence surface. Adds the durable
-- delivery-control-plane tables that back the Kotlin port of the Node
-- `SqlDeliveryRepository` (`factory/src/adapters/persistence/sql/sql-delivery-repository.ts`):
--
--   * `deliveries`        — the mutable delivery snapshot (optimistic-locked by
--                           `revision`, whole snapshot kept verbatim in the JSONB
--                           `payload`, `stage` denormalised for lookup).
--   * `delivery_journal`  — the append-only journal: every promotion,
--                           delivery-operation transition and rollback-request
--                           decision is one immutable row, projected back into
--                           live operations and rollback requests.
--
-- Conventions (see `factory/infra/README.md`):
--   * Every row is tenant-scoped by a non-null `organization_id` (DEFAULT
--     'default' matching V1..V7) and `workstream_id` (DEFAULT 'default').
--   * Identity is composite `(organization_id, workstream_id, namespace_id,
--     delivery_id)` so two tenants, workstreams or namespaces can never collide
--     on the same business key.
--   * The journal is append-only: it carries NO `updated_at` column and NO
--     update trigger; a correction is a new row, never an in-place edit.
--   * Mutable aggregates carry a `revision` column used for optimistic locking;
--     `CHECK (revision >= 1)` keeps the counter meaningful.
--   * The domain payload is stored verbatim as JSONB; the relational columns are
--     the query/identity surface only.
--   * `updated_at` is maintained by the shared `set_updated_at()` trigger
--     function created in V2.

-- --------------------------------------------------------------------------
-- deliveries — mutable, optimistic-locked delivery snapshot
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS deliveries (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  delivery_id     VARCHAR(255) NOT NULL,
  revision        INTEGER      NOT NULL DEFAULT 1 CHECK (revision >= 1),
  stage           VARCHAR(64)  NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT deliveries_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, delivery_id)
);

-- Stage lookup for a tenant scope (delivery board / promotion scans).
CREATE INDEX IF NOT EXISTS idx_deliveries_stage
  ON deliveries (organization_id, workstream_id, namespace_id, stage);

-- --------------------------------------------------------------------------
-- delivery_journal — append-only operations journal
--
-- The composite foreign key pins every journal row to an existing delivery of
-- the exact same tenant/workstream/namespace, so an orphan or cross-tenant
-- record is impossible at the database level; deleting a delivery cascades to
-- its whole journal.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_journal (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  workstream_id   VARCHAR(255) NOT NULL DEFAULT 'default',
  namespace_id    VARCHAR(255) NOT NULL,
  delivery_id     VARCHAR(255) NOT NULL,
  record_sequence INTEGER      NOT NULL,
  record_id       VARCHAR(255) NOT NULL,
  record_type     VARCHAR(64),
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT delivery_journal_pkey
    PRIMARY KEY (organization_id, workstream_id, namespace_id, delivery_id, record_sequence),
  CONSTRAINT delivery_journal_delivery_fk
    FOREIGN KEY (organization_id, workstream_id, namespace_id, delivery_id)
    REFERENCES deliveries (organization_id, workstream_id, namespace_id, delivery_id) ON DELETE CASCADE
);

-- Chronological journal replay for one delivery.
CREATE INDEX IF NOT EXISTS idx_delivery_journal_lookup
  ON delivery_journal (organization_id, workstream_id, namespace_id, delivery_id, created_at);

-- --------------------------------------------------------------------------
-- updated_at trigger (only `deliveries` carries `updated_at`)
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_deliveries_updated_at ON deliveries;
CREATE TRIGGER trg_deliveries_updated_at
  BEFORE UPDATE ON deliveries
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
