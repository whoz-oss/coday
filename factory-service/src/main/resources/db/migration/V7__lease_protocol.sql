-- V7__lease_protocol.sql
--
-- Jalon C1-T0 — lease protocol, heartbeat, worker capabilities and the
-- base-guaranteed MONOTONE FENCING TOKEN mechanism.
--
-- V6 reserved the identifier/relationship skeletons for the worker &
-- environment control plane (`work_units`, `work_environments`, `workers`,
-- `work_unit_leases`) but deliberately carried NO active lease mechanics
-- (see V6 Amendment 6). This migration layers the Jalon C lease protocol on top
-- of those skeletons WITHOUT recreating any table: it only ADDs columns to the
-- existing tables, plus the supporting indexes, the sequence and the CHECK
-- constraints below.
--
-- Scope:
--   * `work_unit_leases` — explicit lease lifecycle: monotonically increasing
--     fencing token, acquisition / expiry / heartbeat / release timestamps and a
--     machine-readable expiry reason. This turns the V6 appointment log into the
--     durable record of an *active* lease.
--   * `workers` — liveness and negotiation surface: last heartbeat, spoken
--     protocol version and declared capabilities.
--   * `work_units` — prioritised, deferred and attempt-tracked scheduling: a
--     `priority` used by the eligible-task scan, a nullable `not_before`
--     execution gate and an `attempt_count` retry counter.
--
-- Fencing token design (why a PostgreSQL SEQUENCE):
--   A fencing token must be strictly increasing even across concurrent
--   transactions and worker nodes. A plain `MAX(token) + 1` (or any table read
--   inside a transaction) can hand the *same* token to two concurrent
--   acquisitions, or hand an *older* token to the transaction that commits last,
--   which defeats the whole point of fencing (a stale worker could then hold a
--   valid-looking token and overwrite a fresh lease).
--
--   PostgreSQL SEQUENCE objects solve this by construction: `nextval()` is
--   non-transactional (it never rolls back), is concurrency-safe and always
--   returns a strictly increasing value for a sequence declared with a positive
--   INCREMENT. We therefore introduce the dedicated sequence
--   `work_unit_lease_fencing_seq` (START WITH 1, INCREMENT BY 1) and make it the
--   single, authoritative source of fencing tokens, INCLUDING as the column
--   default, so a lease insert that does not pass an explicit token still gets
--   one that is monotone in the database.
--
-- Conventions (unchanged from V1..V6, see `factory/infra/README.md`):
--   * Every row stays tenant-scoped by `organization_id NOT NULL DEFAULT
--     'default'`.
--   * New columns are added with `IF NOT EXISTS` and are nullable (or NOT NULL
--     with a constant DEFAULT) so the ALTER is safe on an already-populated
--     database.
--   * The migration never drops or retypes a V1..V6 column, and never recreates
--     a V6 table — it only extends the existing skeleton in place.

-- --------------------------------------------------------------------------
-- Monotone fencing token sequence (base-guaranteed)
-- --------------------------------------------------------------------------
-- Dedicated PostgreSQL sequence used to generate strictly increasing, monotone
-- fencing tokens across all lease acquisitions in the database.
--
--   * `nextval('work_unit_lease_fencing_seq')` is non-transactional: it is never
--     rolled back, so two concurrent acquisitions can never observe the same
--     token and a token is never reused.
--   * `INCREMENT BY 1` (a positive constant) guarantees the returned values are
--     STRICTLY increasing — the core fencing property.
--   * `NO MAXVALUE` leaves the 64-bit sequence range available, matching the
--     `BIGINT` column type of `work_unit_leases.fencing_token`.
--   * `CACHE 1` avoids handing out (and burning) cached values on crash, keeping
--     the token space gap-free for observability.
CREATE SEQUENCE IF NOT EXISTS work_unit_lease_fencing_seq
  START WITH 1
  INCREMENT BY 1
  NO MINVALUE
  NO MAXVALUE
  CACHE 1;

-- --------------------------------------------------------------------------
-- work_unit_leases — active lease lifecycle (extends the V6 appointment log)
-- --------------------------------------------------------------------------
-- The V6 table already carries the tenant-scoped identity
-- `(organization_id, workstream_id, work_unit_id, lease_id, worker_id,
-- environment_id, status, created_at)`. V7 adds the lifecycle bookkeeping that
-- makes a lease *active*:
--   * `fencing_token`     — monotone token from `work_unit_lease_fencing_seq`;
--                           a worker holding a stale token is fenced off.
--   * `acquired_at`       — when the worker took the lease.
--   * `lease_expires_at`  — deadline after which the lease is reclamable.
--   * `heartbeat_at`      — timestamp of the most recent successful heartbeat.
--   * `released_at`       — when the worker (or the reclaimer) released it.
--   * `expiry_reason`     — machine-readable reason for an `expired` transition
--                           (e.g. 'heartbeat_timeout', 'worker_lost').
--
-- All six columns are nullable: existing V6 rows predate the protocol and must
-- be preserved untouched.
ALTER TABLE work_unit_leases
  ADD COLUMN IF NOT EXISTS fencing_token BIGINT,
  ADD COLUMN IF NOT EXISTS acquired_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS expiry_reason VARCHAR(255);

-- Make the sequence the authoritative, in-database source of fencing tokens:
-- a lease insert that omits `fencing_token` is assigned the next monotone value
-- automatically. An explicit `nextval(...)` (or a token fetched earlier in the
-- acquisition transaction) still takes precedence, so callers can capture the
-- token before the INSERT.
ALTER TABLE work_unit_leases
  ALTER COLUMN fencing_token SET DEFAULT nextval('work_unit_lease_fencing_seq');

-- Lease acquisition lookup: "is there an active lease for this work unit?".
CREATE INDEX IF NOT EXISTS idx_work_unit_leases_acquisition
  ON work_unit_leases (organization_id, workstream_id, work_unit_id, status);

-- Lease expiry reaper: "which leases have passed their deadline?".
CREATE INDEX IF NOT EXISTS idx_work_unit_leases_expiry
  ON work_unit_leases (organization_id, lease_expires_at);

-- --------------------------------------------------------------------------
-- workers — liveness, protocol version and declared capabilities
-- --------------------------------------------------------------------------
-- Complements the V6 worker skeleton (`worker_type`, `status`, `revision`,
-- `payload`, timestamps):
--   * `last_heartbeat_at` — last heartbeat observed by the control plane; a
--                           worker whose heartbeat is stale is reaped.
--   * `protocol_version`  — the lease-protocol version the worker speaks, so the
--                           control plane can negotiate / reject incompatible
--                           nodes.
--   * `capabilities`      — JSONB array of the capability keys the worker
--                           declares (e.g. ["nodejs","docker"]), used for
--                           eligibility matching. NOT NULL DEFAULT '[]'::jsonb
--                           keeps existing rows valid on an already-populated
--                           table.
ALTER TABLE workers
  ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS protocol_version VARCHAR(64),
  ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '[]'::jsonb;

-- Capabilities are declared as a JSON array; the CHECK keeps the JSONB shape
-- consistent with the DEFAULT and with the eligibility matcher.
ALTER TABLE workers
  ADD CONSTRAINT workers_capabilities_array_check
  CHECK (jsonb_typeof(capabilities) = 'array');

-- --------------------------------------------------------------------------
-- work_units — priority scheduling, deferred execution and attempt tracking
-- --------------------------------------------------------------------------
-- Complements the V6 work-unit skeleton (`unit_type`, `status`, `revision`,
-- `payload`, timestamps):
--   * `priority`      — scheduling weight (higher first) used by the eligible
--                       work-unit scan. NOT NULL DEFAULT 0 so existing rows are
--                       immediately schedulable.
--   * `not_before`    — nullable execution gate: the unit is not eligible before
--                       this instant (back-off / scheduled start). NULL means
--                       "eligible now".
--   * `attempt_count` — number of execution attempts already made, for retry
--                       accounting. NOT NULL DEFAULT 0.
ALTER TABLE work_units
  ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS not_before TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;

-- Retry counter must stay meaningful.
ALTER TABLE work_units
  ADD CONSTRAINT work_units_attempt_count_check
  CHECK (attempt_count >= 0);

-- Eligible-work-unit scan supporting `SELECT ... FOR UPDATE SKIP LOCKED`:
--   WHERE organization_id = ? AND workstream_id = ? AND status = 'created'
--     AND (not_before IS NULL OR not_before <= now())
--   ORDER BY priority DESC, not_before
-- The index leads with the tenant/workstream/status equality predicates, then
-- carries `priority DESC, not_before` so the ordering is served from the index.
CREATE INDEX IF NOT EXISTS idx_work_units_eligibility
  ON work_units (organization_id, workstream_id, status, priority DESC, not_before);
