# Plan V5 Flyway Migration (Evidence & Human Interaction)

## Overview
Implement Flyway migration `V5__evidence_and_interaction.sql`, schema validation test suite `factory/tests/test-v5-migration-schema.mjs`, and update `factory/infra/README.md`.

This migration establishes control plane PostgreSQL persistence for cross-cutting evidence logging (`workflow_evidence`), mutable human interactions root aggregate (`human_interactions`), and audit event logging (`human_interaction_events`).

---

## File Operations

1. **`factory/infra/migrations/V5__evidence_and_interaction.sql`** (CREATE)
   - Flyway V5 migration file.
   - Table `workflow_evidence`:
     - Append-only cross-cutting evidence log (Amendment 3).
     - Tenant-scoped columns: `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `namespace_id VARCHAR(255) NOT NULL`, `workflow_id VARCHAR(255) NOT NULL`, `evidence_id VARCHAR(255) NOT NULL`.
     - Primary Key: `(organization_id, workstream_id, namespace_id, workflow_id, evidence_id)`.
     - Foreign Key: `CONSTRAINT workflow_evidence_instance_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id) REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.
     - Additional columns: `evidence_type VARCHAR(255) NOT NULL`, `source VARCHAR(255) NOT NULL`, `producer VARCHAR(255) NOT NULL`, `payload JSONB NOT NULL DEFAULT '{}'::jsonb`, `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
     - Append-only guarantee: NO `updated_at` column, NO update trigger.
     - Supporting index: `CREATE INDEX IF NOT EXISTS idx_workflow_evidence_instance ON workflow_evidence (organization_id, workstream_id, namespace_id, workflow_id, created_at)`.

   - Table `human_interactions`:
     - Mutable aggregate root for human decision workflow nodes.
     - Tenant-scoped columns: `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `namespace_id VARCHAR(255) NOT NULL`, `workflow_id VARCHAR(255) NOT NULL`, `interaction_id VARCHAR(255) NOT NULL`.
     - Primary Key: `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id)`.
     - Foreign Key: `CONSTRAINT human_interactions_instance_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id) REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.
     - Additional columns: `interaction_type VARCHAR(255) NOT NULL`, `status VARCHAR(64) NOT NULL DEFAULT 'waiting'`, `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`, `payload JSONB NOT NULL DEFAULT '{}'::jsonb`, `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
     - Check constraint: `CONSTRAINT human_interactions_status_check CHECK (status IN ('waiting', 'answered', 'closed'))`.
     - Trigger: `DROP TRIGGER IF EXISTS trg_human_interactions_updated_at ON human_interactions; CREATE TRIGGER trg_human_interactions_updated_at BEFORE UPDATE ON human_interactions FOR EACH ROW EXECUTE FUNCTION set_updated_at();`.
     - Supporting index: `CREATE INDEX IF NOT EXISTS idx_human_interactions_instance ON human_interactions (organization_id, workstream_id, namespace_id, workflow_id, status)`.

   - Table `human_interaction_events`:
     - Append-only log for human interaction lifecycle events (creation, response, closure).
     - Tenant-scoped columns: `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`, `namespace_id VARCHAR(255) NOT NULL`, `workflow_id VARCHAR(255) NOT NULL`, `interaction_id VARCHAR(255) NOT NULL`, `event_id VARCHAR(255) NOT NULL`.
     - Primary Key: `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id)`.
     - Foreign Key: `CONSTRAINT human_interaction_events_interaction_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id, interaction_id) REFERENCES human_interactions (organization_id, workstream_id, namespace_id, workflow_id, interaction_id) ON DELETE CASCADE`.
     - Additional columns: `event_type VARCHAR(255) NOT NULL`, `actor_id VARCHAR(255) NOT NULL`, `payload JSONB NOT NULL DEFAULT '{}'::jsonb`, `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
     - Append-only guarantee: NO `updated_at` column, NO update trigger.
     - Supporting index: `CREATE INDEX IF NOT EXISTS idx_human_interaction_events_interaction ON human_interaction_events (organization_id, workstream_id, namespace_id, workflow_id, interaction_id, created_at)`.

   - Amendment 2 Readiness:
     - No blocking triggers, strict FK cascade ordering, or locking locks that prevent single atomic PostgreSQL transaction combining human decision + evidence + workflow step state transition + revision increment + interaction closure + outbox event.

2. **`factory/tests/test-v5-migration-schema.mjs`** (CREATE)
   - ES Module test script following `test-v4-migration-schema.mjs` pattern.
   - Cumulative parsing of V1 + V2 + V3 + V4 + V5.
   - Test blocks:
     - **Block A**: Syntax cleanliness (balanced parentheses, statements ending with semicolon, `$$` quoting).
     - **Block B**: Table & column definitions for all 3 V5 tables (`workflow_evidence`, `human_interactions`, `human_interaction_events`), verifying exact types, `NOT NULL`, and defaults.
     - **Block C**: Primary keys & composite tenant foreign keys. Verify FK target matching for `workflow_instances` and `human_interactions` to enforce tenant isolation and reject orphan / cross-tenant records.
     - **Block D**: Supporting indexes (`idx_workflow_evidence_instance`, `idx_human_interactions_instance`, `idx_human_interaction_events_interaction`).
     - **Block E**: CHECK constraints (`revision >= 1` on `human_interactions`, `status IN ('waiting', 'answered', 'closed')` on `human_interactions`).
     - **Block F**: Append-only verification (`workflow_evidence` & `human_interaction_events` MUST NOT have `updated_at` column or triggers). `human_interactions` MUST have `trg_human_interactions_updated_at` trigger calling `set_updated_at()`.
     - **Block G**: Tenant isolation (`organization_id NOT NULL DEFAULT 'default'`).

3. **`factory/infra/README.md`** (UPDATE)
   - Append V5 schema documentation at the bottom without altering V1-V4 sections.
   - Add V5 file `V5__evidence_and_interaction.sql` to layout directory tree.
   - Add V5 migration entry under Migrations list.
   - Add "Schema overview — V5 evidence & human interaction" section describing:
     - Summary table of `workflow_evidence`, `human_interactions`, `human_interaction_events`.
     - Detailed field descriptions for each table.
     - Design rationale for Amendment 2 (atomic multi-table transactions), Amendment 3 (append-only evidence log), and Amendment 8 (composite FK tenant isolation).
     - Execution instructions for running `node factory/tests/test-v5-migration-schema.mjs`.

---

## Verification Plan

### Offline Test Execution
Run offline SQL migration test suite (no DB required):
```bash
node factory/tests/test-v5-migration-schema.mjs
```
Expected output: 0 failures, all assertions passed.

Run all existing schema tests to ensure no regressions:
```bash
node factory/tests/test-v2-migration-schema.mjs
node factory/tests/test-v3-migration-schema.mjs
node factory/tests/test-v4-migration-schema.mjs
```

### Affected / Scope Boundary Validation
Check git diff to strictly confirm boundary compliance:
```bash
git status
```
Output MUST ONLY contain:
- `factory/infra/migrations/V5__evidence_and_interaction.sql`
- `factory/tests/test-v5-migration-schema.mjs`
- `factory/infra/README.md`
