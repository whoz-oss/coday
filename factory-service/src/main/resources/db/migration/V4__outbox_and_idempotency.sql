-- V4__outbox_and_idempotency.sql
--
-- Support tables for Jalon B2 (Tâche B2-T3). This migration adds the two
-- cross-cutting infrastructure tables the durable Factory needs once commands
-- are dispatched over the wire:
--
--   * `outbox_events` — transactional outbox for effects that leave the
--     database (webhooks, agent calls, external systems). A row is written in
--     the same transaction as the state change; a drain worker later claims and
--     dispatches it (Jalon B Amendment 7: outbox pattern for external effects).
--     It is an append-style, mutable-status log: there is no `updated_at`
--     because the domain never "edits" an event, it only advances its status.
--   * `idempotency_records` — tenant-scoped dedupe/response cache keyed by the
--     client-supplied idempotency key. It stores a request fingerprint so a
--     replayed command with the same key and a divergent body is rejected, while
--     an identical replay returns the first response (Jalon B Amendments 2 & 4:
--     idempotency & request hashing, resource locking & operations).
--
-- Conventions (see `factory/infra/README.md`):
--   * Every row is tenant-scoped by a non-null `organization_id` (DEFAULT
--     'default' matching V1/V2/V3).
--   * Identity is composite: `(organization_id, …)` so two tenants can never
--     collide on the same business key.
--   * `updated_at` is maintained by the shared `set_updated_at()` trigger
--     function created in V2.

-- --------------------------------------------------------------------------
-- outbox_events — transactional outbox (Amendment 7)
--
-- PK is `(organization_id, id)` so the event id is only unique per tenant (a
-- client-generated id cannot collide across organizations). `payload` holds the
-- opaque event envelope as JSONB. `attempts` is the drain retry counter and the
-- partial drain index below keeps the pending backlog cheap to scan.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_events (
  organization_id VARCHAR(255) NOT NULL DEFAULT 'default',
  id              VARCHAR(255) NOT NULL,
  workstream_id   VARCHAR(255),
  event_type      VARCHAR(255) NOT NULL,
  payload         JSONB        NOT NULL DEFAULT '{}'::jsonb,
  status          VARCHAR(64)  NOT NULL DEFAULT 'pending',
  attempts        INTEGER      NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  dispatched_at   TIMESTAMPTZ,
  CONSTRAINT outbox_events_pkey PRIMARY KEY (organization_id, id),
  CONSTRAINT outbox_events_status_check CHECK (status IN ('pending', 'dispatched', 'failed')),
  CONSTRAINT outbox_events_attempts_check CHECK (attempts >= 0)
);

-- Drain index: the worker scans the oldest pending events of a tenant
-- (`WHERE organization_id = ? AND status = 'pending' ORDER BY created_at`).
CREATE INDEX IF NOT EXISTS idx_outbox_events_drain
  ON outbox_events (organization_id, status, created_at);

-- --------------------------------------------------------------------------
-- idempotency_records — idempotent command submission (Amendments 2 & 4)
--
-- The tenant-scoped PK `(organization_id, idempotency_key)` is the uniqueness
-- guarantee: a second submission of the same key by the same tenant collides at
-- the database level. `request_hash` fingerprints the command body so a replay
-- with a divergent body can be detected; `response_payload` caches the first
-- response so an identical replay is answered without re-executing.
-- --------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS idempotency_records (
  organization_id  VARCHAR(255) NOT NULL DEFAULT 'default',
  idempotency_key  VARCHAR(255) NOT NULL,
  workstream_id    VARCHAR(255) NOT NULL DEFAULT 'default',
  request_hash     VARCHAR(64)  NOT NULL,
  resource_ref     VARCHAR(255),
  status           VARCHAR(64)  NOT NULL DEFAULT 'processing',
  response_payload JSONB        NOT NULL DEFAULT '{}'::jsonb,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT idempotency_records_pkey PRIMARY KEY (organization_id, idempotency_key),
  -- Explicit unique key documents the collision-detection guarantee (the PK
  -- already implies it, mirroring the `uq_workstreams` convention in V2).
  CONSTRAINT uq_idempotency_records UNIQUE (organization_id, idempotency_key),
  CONSTRAINT idempotency_records_status_check CHECK (status IN ('processing', 'completed', 'failed'))
);

-- Lookup of the resources already produced for a workstream (operations view).
CREATE INDEX IF NOT EXISTS idx_idempotency_records_workstream
  ON idempotency_records (organization_id, workstream_id);

-- --------------------------------------------------------------------------
-- updated_at trigger (idempotency_records is the only V4 table carrying
-- `updated_at`; outbox_events is an append-style status log without one)
-- --------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_idempotency_records_updated_at ON idempotency_records;
CREATE TRIGGER trg_idempotency_records_updated_at
  BEFORE UPDATE ON idempotency_records
  FOR EACH ROW
  EXECUTE FUNCTION set_updated_at();
