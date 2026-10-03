# Plan B2-T3: Support Tables Migration (V4 outbox & idempotency)

## Task Description
Create Flyway migration V4 for support tables (`outbox_events` & `idempotency_records`), associated schema validation test `factory/tests/test-v4-migration-schema.mjs`, and append section V4 documentation to `factory/infra/README.md`.

## Strict Scope & Constraints
- Touch ONLY:
  1. `factory/infra/migrations/V4__outbox_and_idempotency.sql`
  2. `factory/tests/test-v4-migration-schema.mjs`
  3. `factory/infra/README.md`
- Do NOT touch V1, V2, V3, evidence/interaction (V5), artifacts (V6), TS repositories, runtime bundle `factory/runtime/factory-operational.mjs`, or `agentos/**`.

---

## Technical Specifications & Concrete Changes

### 1. `factory/infra/migrations/V4__outbox_and_idempotency.sql`

Create SQL migration file with exact structures:

#### Table `outbox_events`
- Purpose: Asynchronous/external event delivery log (Amendment 7).
- Columns:
  * `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  * `id VARCHAR(255) NOT NULL`
  * `workstream_id VARCHAR(255)` (nullable)
  * `event_type VARCHAR(255) NOT NULL`
  * `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  * `status VARCHAR(64) NOT NULL DEFAULT 'pending'`
  * `attempts INTEGER NOT NULL DEFAULT 0`
  * `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  * `dispatched_at TIMESTAMPTZ` (nullable)
- Constraints:
  * Primary Key: `CONSTRAINT outbox_events_pkey PRIMARY KEY (organization_id, id)`
  * Check Constraint: `CONSTRAINT outbox_events_status_check CHECK (status IN ('pending', 'dispatched', 'failed'))`
  * Check Constraint: `CONSTRAINT outbox_events_attempts_check CHECK (attempts >= 0)`
- Index:
  * Drain index: `CREATE INDEX IF NOT EXISTS idx_outbox_events_drain ON outbox_events (organization_id, status, created_at);` (also ensuring standard drain lookup `(status, created_at)` is efficiently satisfied).

#### Table `idempotency_records`
- Purpose: Enforce idempotency submission uniqueness and collision detection across operations (Amendments 2, 4).
- Columns:
  * `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
  * `idempotency_key VARCHAR(255) NOT NULL`
  * `workstream_id VARCHAR(255)` (nullable or default 'default' — make `NOT NULL DEFAULT 'default'`)
  * `request_hash VARCHAR(64) NOT NULL`
  * `resource_ref VARCHAR(255)` (nullable)
  * `status VARCHAR(64) NOT NULL DEFAULT 'processing'`
  * `response_payload JSONB NOT NULL DEFAULT '{}'::jsonb`
  * `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
  * `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- Constraints:
  * Primary Key: `CONSTRAINT idempotency_records_pkey PRIMARY KEY (organization_id, idempotency_key)`
  * Unique Constraint: `CONSTRAINT uq_idempotency_records UNIQUE (organization_id, idempotency_key)` (or PK as unique guarantee)
  * Check Constraint: `CONSTRAINT idempotency_records_status_check CHECK (status IN ('processing', 'completed', 'failed'))`
- Triggers:
  * `updated_at` trigger calling `set_updated_at()` function created in V2:
    ```sql
    DROP TRIGGER IF EXISTS trg_idempotency_records_updated_at ON idempotency_records;
    CREATE TRIGGER trg_idempotency_records_updated_at
      BEFORE UPDATE ON idempotency_records
      FOR EACH ROW
      EXECUTE FUNCTION set_updated_at();
    ```

---

### 2. `factory/tests/test-v4-migration-schema.mjs`

Create the offline Node.js schema test script following the pattern of `test-v3-migration-schema.mjs`:
- Reads cumulative migrations `V1__init_workflow_pilot_schema.sql`, `V2__tenant_and_membership.sql`, `V3__workflow_core.sql`, and `V4__outbox_and_idempotency.sql`.
- Parses SQL without external dependencies (no Docker, no Postgres connection, no `pg` module).
- Validates the following blocks:
  * **Block A (Syntax)**: Valid statement splitting, balanced parentheses, non-empty statements.
  * **Block B (Tables & Columns)**: Presence of `outbox_events` and `idempotency_records`, accurate column types (`VARCHAR`, `JSONB`, `TIMESTAMPTZ`, `INTEGER`), `NOT NULL` rules, default values (`'default'`, `'pending'`, `'processing'`, `'{}'::jsonb`, `0`).
  * **Block C (Keys & Uniqueness)**: Primary keys for `outbox_events (organization_id, id)` and `idempotency_records (organization_id, idempotency_key)`. Uniqueness enforcement on idempotency keys per tenant.
  * **Block D (Indices)**: Presence of drain index `idx_outbox_events_drain` on `outbox_events` covering `status` and `created_at` (and `organization_id`).
  * **Block E (Triggers & Defaults)**: Verification that `idempotency_records` uses `set_updated_at()` trigger for `updated_at`, while append-only/event table `outbox_events` does not have `updated_at`. Verification of `attempts >= 0` check constraint on `outbox_events`.
  * **Block F (Tenant Isolation)**: `organization_id NOT NULL DEFAULT 'default'` on all support tables.
- Returns exit code 0 when all assertions pass, non-zero on failure.

---

### 3. `factory/infra/README.md`

Append section **"Schema overview — V4 outbox & idempotency"** at the bottom of the file (leaving existing V1, V2, V3 sections untouched).
Details to include in documentation:
- Description of `V4__outbox_and_idempotency.sql`.
- Tables (`outbox_events`, `idempotency_records`), column descriptions, default values, primary keys, indices, check constraints, and triggers.
- Design rationale reference: Amendment 2 (Idempotency & Request Hashing), Amendment 4 (Resource Locking & Operations), Amendment 7 (Outbox Pattern for External Effects).
- Add instructions for running the test script `node factory/tests/test-v4-migration-schema.mjs`.

---

## Verification Plan

1. **Schema test execution**:
   Run: `node factory/tests/test-v4-migration-schema.mjs`
   Verify exit status 0 and all test assertions pass.

2. **Affected / Monorepo verification**:
   Verify no unexpected files modified: `git status`.
