# Implementation Plan: V3 Flyway Migration & Schema Test - Workflow Core Extension

## Objective
Implement Flyway migration `V3__workflow_core.sql` in `factory/infra/migrations/` and the offline schema validation test `factory/tests/test-v3-migration-schema.mjs` (along with updating `factory/infra/README.md`) to extend the workflow core in PostgreSQL while maintaining tenant isolation and schema integrity (Jalon B2, Tâche B2-T2).

## Scope STRICT
Modify/Create ONLY:
- `factory/infra/migrations/V3__workflow_core.sql`
- `factory/tests/test-v3-migration-schema.mjs`
- `factory/infra/README.md`

Do NOT touch V1, V2, V4+, any repositories, or runtime/agentos code.

---

## Detailed Requirements & Implementation Specifications

### 1. `factory/infra/migrations/V3__workflow_core.sql`

This migration extends `workflow_definitions` created in V1 and creates 4 new workflow core tables: `workflow_definition_versions`, `workstream_workflow_grants`, `workflow_step_states`, and `workflow_transitions`.

Conventions:
- `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
- `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'` where applicable
- `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)` on mutable roots/step states
- Timestamps: `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- For tables with `updated_at`, include `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP` and wire the `BEFORE UPDATE` trigger reusing `set_updated_at()` defined in V2.

#### Table Modifications / Declarations:

1. **`workflow_definitions` ALTERations**:
   - `ALTER TABLE workflow_definitions ADD COLUMN IF NOT EXISTS visibility VARCHAR(32) NOT NULL DEFAULT 'organization';`
   - `ALTER TABLE workflow_definitions ADD CONSTRAINT workflow_definitions_visibility_check CHECK (visibility IN ('platform', 'organization', 'workstream'));`
   - `ALTER TABLE workflow_definitions ADD COLUMN IF NOT EXISTS owner_workstream_id VARCHAR(255);` (nullable, target workstream when visibility='workstream')
   - (Note: V1 created `workflow_definitions` with `organization_id`, `workstream_id`, `workflow_type`, `version`, `definition_hash`, `definition_json`, `created_at` with PK `(organization_id, workflow_type, version)`). Ensure compat with `ADD COLUMN IF NOT EXISTS`.

2. **`workflow_definition_versions`**:
   - Stores version history / revisions for workflow definitions.
   - Tenant scoped: `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`.
   - Structural columns: `workflow_type VARCHAR(255) NOT NULL`, `version VARCHAR(64) NOT NULL`, `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`.
   - Payload: `definition_json JSONB NOT NULL DEFAULT '{}'::jsonb` (or `payload JSONB NOT NULL DEFAULT '{}'::jsonb`), `definition_hash VARCHAR(64)`.
   - Timestamps: `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`.
   - Primary Key: `CONSTRAINT workflow_definition_versions_pkey PRIMARY KEY (organization_id, workflow_type, version, revision)`.
   - Foreign Key to `workflow_definitions`: `CONSTRAINT workflow_definition_versions_workflow_definition_fk FOREIGN KEY (organization_id, workflow_type, version) REFERENCES workflow_definitions (organization_id, workflow_type, version) ON DELETE CASCADE`.
   - Trigger: Wire `trg_workflow_definition_versions_updated_at` before update execute function `set_updated_at()`.

3. **`workstream_workflow_grants`** (Amendment 1):
   - Explicit workflow access grants for workstreams.
   - Scope / Tenant isolation via composite FK to `workstreams(organization_id, workstream_id)` ON DELETE CASCADE.
   - Columns:
     - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
     - `workstream_id VARCHAR(255) NOT NULL`
     - `grant_id VARCHAR(255) NOT NULL` (or PK composite of `(organization_id, workstream_id, workflow_type)`)
     - `workflow_type VARCHAR(255) NOT NULL`
     - `version VARCHAR(64)` (optional / nullable version filter)
     - `enabled BOOLEAN NOT NULL DEFAULT true`
     - `configuration JSONB NOT NULL DEFAULT '{}'::jsonb`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
     - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Primary Key: `CONSTRAINT workstream_workflow_grants_pkey PRIMARY KEY (organization_id, workstream_id, workflow_type)`.
   - Composite FK: `CONSTRAINT workstream_workflow_grants_workstream_fk FOREIGN KEY (organization_id, workstream_id) REFERENCES workstreams (organization_id, workstream_id) ON DELETE CASCADE`.
   - Trigger: Wire `trg_workstream_workflow_grants_updated_at` before update execute function `set_updated_at()`.

4. **`workflow_step_states`**:
   - Individual step execution states for workflow instances.
   - Composite FK to `workflow_instances` (Amendment 8): `(organization_id, workstream_id, namespace_id, workflow_id)` referencing `workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.
   - Columns:
     - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
     - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
     - `namespace_id VARCHAR(255) NOT NULL`
     - `workflow_id VARCHAR(255) NOT NULL`
     - `step_id VARCHAR(255) NOT NULL`
     - `revision INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1)`
     - `status VARCHAR(64) NOT NULL DEFAULT 'pending'`
     - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
     - `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Primary Key: `CONSTRAINT workflow_step_states_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, step_id)`.
   - Composite FK: `CONSTRAINT workflow_step_states_instance_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id) REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.
   - Trigger: Wire `trg_workflow_step_states_updated_at` before update execute function `set_updated_at()`.

5. **`workflow_transitions`**:
   - Log/record of state transitions for workflow instances.
   - Composite FK to `workflow_instances` (Amendment 8): `(organization_id, workstream_id, namespace_id, workflow_id)` referencing `workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.
   - Columns:
     - `transition_id VARCHAR(255) NOT NULL` (or auto/UUID or sequence/PK composite with sequence)
     - `organization_id VARCHAR(255) NOT NULL DEFAULT 'default'`
     - `workstream_id VARCHAR(255) NOT NULL DEFAULT 'default'`
     - `namespace_id VARCHAR(255) NOT NULL`
     - `workflow_id VARCHAR(255) NOT NULL`
     - `from_step_id VARCHAR(255)`
     - `to_step_id VARCHAR(255) NOT NULL`
     - `event_name VARCHAR(255) NOT NULL`
     - `payload JSONB NOT NULL DEFAULT '{}'::jsonb`
     - `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - Primary Key: `CONSTRAINT workflow_transitions_pkey PRIMARY KEY (organization_id, workstream_id, namespace_id, workflow_id, transition_id)`.
   - Composite FK: `CONSTRAINT workflow_transitions_instance_fk FOREIGN KEY (organization_id, workstream_id, namespace_id, workflow_id) REFERENCES workflow_instances (organization_id, workstream_id, namespace_id, workflow_id) ON DELETE CASCADE`.

---

### 2. `factory/tests/test-v3-migration-schema.mjs`

Following the pattern established by `test-v2-migration-schema.mjs`:
- Offline parser: reads and parses V1, V2, and V3 files combined to evaluate full schema state across V1+V2+V3.
- Test suites (Blocks A to G):
  - **Bloc A — Syntax & Integrity**: Valid SQL, balance of parentheses and dollar-quoting (`set_updated_at`), proper terminal `;`.
  - **Bloc B — Tables & Columns**: Verify presence of new tables (`workflow_definition_versions`, `workstream_workflow_grants`, `workflow_step_states`, `workflow_transitions`) and modified columns in `workflow_definitions` (`visibility`, `owner_workstream_id`).
  - **Bloc C — Primary & Unique Keys**: Assert PK definitions for newly created tables and check targets.
  - **Bloc D — Composite Foreign Keys**: Assert composite FKs exist and point to valid PKs/UNIQUE keys (e.g. `workflow_step_states` -> `workflow_instances`, `workflow_transitions` -> `workflow_instances`, `workstream_workflow_grants` -> `workstreams`, `workflow_definition_versions` -> `workflow_definitions`). Verify `ON DELETE CASCADE`.
  - **Bloc E — CHECK Constraints**: Test `visibility IN ('platform', 'organization', 'workstream')` on `workflow_definitions`, `revision >= 1` on mutable tables/step states.
  - **Bloc F — Defaults & Triggers**: Test `organization_id DEFAULT 'default'`, `workstream_id DEFAULT 'default'`, and `updated_at` trigger wiring (`set_updated_at()`) for tables carrying `updated_at`.
  - **Bloc G — Tenant Isolation & Integrity**: Test cross-tenant prevention checks for composite FKs referencing `workflow_instances` and `workstreams`.

Execution runner:
- `node factory/tests/test-v3-migration-schema.mjs`
- Standard zero exit code on success, non-zero on failure.

---

### 3. `factory/infra/README.md`

- Update documentation to include V3 migration details.
- Document the schema changes to `workflow_definitions` and details of `workflow_definition_versions`, `workstream_workflow_grants`, `workflow_step_states`, and `workflow_transitions`.

---

## Verification Strategy

1. Execute test script:
   ```bash
   node factory/tests/test-v3-migration-schema.mjs
   ```
   Confirm all test cases pass with 0 errors.

2. Run repository-wide lint and test affected commands (if applicable):
   ```bash
   node factory/tests/test-v2-migration-schema.mjs
   ```
   Ensure existing V2 tests continue to pass without regression.

3. Verify strictly no extraneous files outside of the allowed scope were created or modified.
