# Implementation Plan - PostgreSQL Persistence Adapters for Evidence and Human Interaction (Milestone B3 Task B3-T1)

## Overview
Implement two new SQL persistence adapters in `factory/src/adapters/persistence/sql/`:
1. `sql-workflow-evidence-repository.ts`: SQL repository for workflow evidence against `workflow_evidence` table (APPEND-ONLY).
2. `sql-workflow-human-interaction-repository.ts`: SQL repository for human interactions against `human_interactions` and `human_interaction_events` tables.

Also create a comprehensive conformance test suite:
`factory/tests/test-conformance-evidence-interaction.mjs` running parity tests between Filesystem adapters and SQL adapters (using `createInMemorySqlClient()`).

## Context & Key Architectural Requirements

### SQL Schema V5 Tables & Columns
- `workflow_evidence`:
  - Columns: `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `evidence_id`, `evidence_type`, `source`, `producer`, `payload`, `created_at`
  - PK: `(organization_id, workstream_id, namespace_id, workflow_id, evidence_id)`
  - Append-only invariant: strictly NO updates or deletes.
- `human_interactions`:
  - Columns: `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `interaction_id`, `interaction_type`, `status`, `revision`, `payload`, `created_at`, `updated_at`
  - PK: `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id)`
  - Status values: `'waiting'`, `'answered'`, `'closed'` (or mapped domain statuses like `'opening'`, `'open'`, `'replied'`, `'aborted'`)
- `human_interaction_events`:
  - Columns: `organization_id`, `workstream_id`, `namespace_id`, `workflow_id`, `interaction_id`, `event_id`, `event_type`, `actor_id`, `payload`, `created_at`
  - PK: `(organization_id, workstream_id, namespace_id, workflow_id, interaction_id, event_id)`
  - Append-only event log.
- `outbox_events` (V4 Schema):
  - Columns: `organization_id`, `id`, `workstream_id`, `event_type`, `payload`, `status`, `attempts`, `created_at`, `dispatched_at`
  - PK: `(organization_id, id)`

### Error Parity & Error Classes
- Filesystem adapters and stores use `WorkflowEvidenceStoreError` / `WorkflowHumanInteractionError` or `WorkflowHumanInteractionRepositoryError`.
- For `WorkflowEvidenceRepository`: throw `WorkflowEvidenceStoreError` with exact error codes:
  - `'IDEMPOTENCY_KEY_COLLISION'` when idempotencyKey is used with different content (fingerprint/semanticHash mismatch).
  - `'EVIDENCE_STORAGE_FAILURE'` or `'CORRUPT_EVIDENCE_STORAGE'` where appropriate.
- For `WorkflowHumanInteractionRepository`: throw `WorkflowHumanInteractionError` or `WorkflowHumanInteractionRepositoryError` with exact error codes:
  - `'HUMAN_INTERACTION_TRANSITION_REQUIRED'` when `recordOpen` options lack `transition`.
  - `'HUMAN_INTERACTION_ACTION_REQUIRED'` when `recordTransition` options lack `action`.
  - `'INVALID_INTERACTION'` when open input fails validation.
  - `'IDEMPOTENCY_KEY_COLLISION'` when idempotency Key collision occurs with divergent semantic hash.
  - `'INTERACTION_ALREADY_OPEN'` when opening an interaction for a workflow step that already has an open or opening interaction.
  - `'INTERACTION_NOT_FOUND'` when performing transition on non-existent interaction.
  - `'INTERACTION_CLOSED'` when attempting to transition an interaction that is not in open status.
  - `'INTERACTION_OPEN_INDETERMINATE'` when replaying an idempotency key whose prior status was aborted or not open.
  - `'INTERACTION_RECOVERY_NOT_FOUND'`, `'INTERACTION_RECOVERY_AMBIGUOUS'`, `'INTERACTION_RECOVERY_SNAPSHOT_INVALID'`, `'INTERACTION_RECOVERY_REVISION_DIVERGED'`, `'INTERACTION_RECOVERY_STATE_DIVERGED'`, `'INTERACTION_RECOVERY_TRANSITION_UNPROVEN'` for `reconcileOpen`.

### Amendment 2: Atomicity Requirement
- `recordOpen` and `recordTransition` in `SqlWorkflowHumanInteractionRepository` MUST execute inside ONE SINGLE database transaction using `withTransaction` from `./unit-of-work.js`:
  - When opening:
    1. Insert `human_interactions` record (or update status if reopening)
    2. Insert `human_interaction_events` event row (`interaction_opening`)
    3. Execute the authoritative workflow transition callback (`options.transition(interaction)`) which updates `workflow_instances` state/revision
    4. If transition fails, record `interaction_open_aborted` event (or rollback depending on semantics), and rethrow/abort.
    5. On transition success, insert `interaction_opened` event into `human_interaction_events`.
    6. Insert outbox event into `outbox_events` table (if outbox event is generated).
  - When transitioning/answering (`recordTransition`):
    1. Lock / SELECT the `human_interactions` row.
    2. Validate interaction is open and expectations match.
    3. Execute authoritative callback `options.action(interaction)`.
    4. Update `human_interactions` state (`status = 'answered'` or `'closed'`, `revision` incremented).
    5. Insert evidence row into `workflow_evidence` table (if evidence generated).
    6. Insert `interaction_transitioned` event into `human_interaction_events`.
    7. Insert outbox event into `outbox_events` table.
    8. ALL executed within `withTransaction(client, async (tx) => { ... })`.

### Invariants & Tenant Scoping
- Tenant scoping: `organizationId` (default `'default'`), `workstreamId` (default `'default'`).
- `workflow_evidence` is strictly APPEND-ONLY: no `UPDATE` or `DELETE` statements on `workflow_evidence`.

### Strictly Forbidden to Touch
- `db.ts`, `unit-of-work.ts`, `in-memory-sql-client.mjs`
- SQL barrel `sql/index.ts` and `adapters/persistence/index.ts` (reserved for W3 wiring task)
- Other adapters (agent-step, oracle, work-environment, delivery)
- Migrations in `infra/migrations/`
- Bundled runtime `factory/runtime/factory-operational.mjs`
- `agentos/**`

---

## Detailed Step-by-Step Implementation Plan

### Step 1: Create `factory/src/adapters/persistence/sql/sql-workflow-evidence-repository.ts`

**Interface / Port Implemented**: `WorkflowEvidenceRepository` from `../../../ports/persistence/workflow-evidence-repository.js`.

**Key Exports & Types**:
- Class `SqlWorkflowEvidenceRepository`
- Function `createSqlWorkflowEvidenceRepository(client: SqlClient, options?: SqlWorkflowEvidenceRepositoryOptions)`
- Class `WorkflowEvidenceStoreError` (matching error structure in `factory/lib/workflow-evidence-store.mjs` or imported if shared).

**Implementation details for `SqlWorkflowEvidenceRepository`**:
- Constructor accepts `client: SqlClient` and `options?: SqlWorkflowEvidenceRepositoryOptions` (`organizationId`, `workstreamId`).
- `list(namespaceId: string, storageId: string, filter?: WorkflowEvidenceListFilter)`:
  - Executes `SELECT organization_id, workstream_id, namespace_id, workflow_id, evidence_id, evidence_type, source, producer, payload, created_at FROM workflow_evidence WHERE organization_id = $1 AND workstream_id = $2 AND namespace_id = $3 AND workflow_id = $4 ORDER BY created_at ASC, evidence_id ASC`.
  - Filters by `filter.stepId` if provided (by reading `stepId` from the parsed `payload` or filter criteria).
  - Maps rows to `WorkflowEvidence` objects: parses `payload` using `parseJsonColumn` and reconstructs `WorkflowEvidence` structure matching domain specs.
- `record(namespaceId: string, storageId: string, input: WorkflowEvidenceInput, source: WorkflowEvidenceSource)`:
  - Computes idempotency scope hash and fingerprint if `input.idempotencyKey` is present:
    - `scope` object: `{ namespaceId, workflowId: input.workflowId, stepId: input.stepId, source, idempotencyKey: input.idempotencyKey }`
    - `scopeHash` = sha256 hash of JSON stringified canonical scope.
    - `fingerprint` = sha256 hash of JSON stringified `{ ...input, idempotencyKey: undefined }`.
  - Checks if an evidence row already exists with matching idempotency metadata in payload:
    - Queries `workflow_evidence` for the workflow.
    - If found prior evidence with matching `idempotency.scopeHash`:
      - If prior `idempotency.semanticHash !== fingerprint`, throw `new WorkflowEvidenceStoreError('IDEMPOTENCY_KEY_COLLISION')`.
      - Return `{ created: false, idempotent: true, evidence: priorEvidence }`.
  - Creates new `WorkflowEvidence` record using `createWorkflowEvidence(input, namespaceId, source)`.
  - Stores `idempotency` metadata inside `payload`: `{ ...evidence, idempotency: { scopeHash, semanticHash: fingerprint } }`.
  - Inserts into `workflow_evidence`:
    - `organization_id`: `this.#organizationId`
    - `workstream_id`: `this.#workstreamId`
    - `namespace_id`: `namespaceId`
    - `workflow_id`: `input.workflowId`
    - `evidence_id`: `evidence.evidenceId`
    - `evidence_type`: `input.kind`
    - `source`: `source.kind ?? source.runtimeId ?? 'unknown'`
    - `producer`: `source.agentId ?? source.actorId ?? 'system'`
    - `payload`: `JSON.stringify(storedPayload)`
    - `created_at`: `evidence.observedAt`
  - Returns `{ created: true, idempotent: false, evidence: stored }`.

---

### Step 2: Create `factory/src/adapters/persistence/sql/sql-workflow-human-interaction-repository.ts`

**Interface / Port Implemented**: `WorkflowHumanInteractionRepository` from `../../../ports/persistence/workflow-human-interaction-repository.js`.

**Key Exports & Types**:
- Class `SqlWorkflowHumanInteractionRepository`
- Function `createSqlWorkflowHumanInteractionRepository(client: SqlClient, options?: SqlWorkflowHumanInteractionRepositoryOptions)`
- Error classes: `WorkflowHumanInteractionError`, `WorkflowHumanInteractionRepositoryError` matching exact codes.

**Implementation details for `SqlWorkflowHumanInteractionRepository`**:
- Constructor accepts `client: SqlClient` and `options?: SqlWorkflowHumanInteractionRepositoryOptions` (`organizationId`, `workstreamId`).

- Method `events(namespaceId: string, storageId: string)`:
  - Queries `human_interaction_events` table for `organization_id`, `workstream_id`, `namespace_id`, `workflow_id = storageId` ordered by `created_at ASC`.
  - Parses `payload` using `parseJsonColumn` and returns array of `WorkflowHumanInteractionEvent`.

- Method `list(namespaceId: string, storageId: string, options?: WorkflowHumanInteractionListOptions)`:
  - Reconstructs projected interaction records from `events` or reads from `human_interactions` + `human_interaction_events`.
  - Queries `human_interactions` table for `organization_id`, `workstream_id`, `namespace_id`, `workflow_id = storageId`.
  - Parses `payload` column which stores projected `HumanInteractionRecord`.
  - If `options?.openOnly` is true, filters where `status === 'open'` (or SQL condition `status = 'waiting'`).
  - Sorts chronologically by `openedAt ASC, interactionId ASC`.

- Method `reconcileOpen(namespaceId, storageId, input, snapshot, options)`:
  - Uses `events` / `list` to find candidates for recovery (`workflowId === input.workflowId && stepId === input.stepId && ['opening', 'aborted'].includes(status)`).
  - Enforces exact error conditions:
    - No candidates => throw `WorkflowHumanInteractionError('INTERACTION_RECOVERY_NOT_FOUND')`
    - >1 candidates => throw `WorkflowHumanInteractionError('INTERACTION_RECOVERY_AMBIGUOUS')`
    - Divergent semanticHash => throw `WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')`
    - Invalid snapshot revision or step missing => throw `WorkflowHumanInteractionError('INTERACTION_RECOVERY_SNAPSHOT_INVALID')`
    - If step status is `'ready'`: aborts opening and returns `{ status: 'reopen', abandonedInteraction }` or handles revision checks.
    - Reconciles and inserts `interaction_opened` into `human_interaction_events` and updates `human_interactions`.

- Method `recordOpen(namespaceId, storageId, input, options)`:
  - Validates `options?.transition` exists, else throws `WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_TRANSITION_REQUIRED')`.
  - Validates `input` using `validateHumanInteractionOpenInput(input)`, else throws `WorkflowHumanInteractionError('INVALID_INTERACTION')`.
  - Calculates semantic hash with `humanInteractionSemanticHash(normalized)`.
  - Executes inside `withTransaction(this.#client, async (tx) => { ... })`:
    - Checks prior interactions with `idempotencyKey`:
      - If prior found with matching key:
        - If `prior.semanticHash !== hash`, throw `WorkflowHumanInteractionError('IDEMPOTENCY_KEY_COLLISION')`.
        - If `prior.status === 'open'`, return `{ created: false, idempotent: true, interaction: prior }`.
        - Else throw `WorkflowHumanInteractionError('INTERACTION_OPEN_INDETERMINATE')`.
    - Checks if an interaction is already opening/open for same `workflowId` and `stepId`:
      - If yes, throw `WorkflowHumanInteractionError('INTERACTION_ALREADY_OPEN')`.
    - Constructs `HumanInteractionRecord` with status `'opening'`.
    - Inserts event `'interaction_opening'` into `human_interaction_events`.
    - Inserts or updates row in `human_interactions` with `status = 'waiting'`.
    - Executes `transition(interaction)` callback inside the transaction (`tx` passed if applicable, or transactional workflow instance update).
    - If callback fails/throws:
      - Inserts `'interaction_open_aborted'` into `human_interaction_events` (or allows transaction rollback depending on requirement).
      - Throws error with appropriate code.
    - If callback succeeds (`result.ok`):
      - Updates `human_interactions` record (`status = 'waiting'`, `revision = result.snapshot.revision`).
      - Inserts `'interaction_opened'` into `human_interaction_events`.
      - Optionally writes outbox event to `outbox_events` if transition produces one.
      - Returns `{ created: true, idempotent: false, interaction: openInteraction }`.

- Method `recordTransition(namespaceId, storageId, interactionId, reply, actorId, evidenceId, transitionRequestId, options)`:
  - Validates `options?.action` exists, else throws `WorkflowHumanInteractionRepositoryError('HUMAN_INTERACTION_ACTION_REQUIRED')`.
  - Executes inside `withTransaction(this.#client, async (tx) => { ... })`:
    - Selects `human_interactions` row for `interactionId` (with tenant scoping).
    - If row not found, throws `WorkflowHumanInteractionError('INTERACTION_NOT_FOUND')`.
    - If status is not open/waiting, throws `WorkflowHumanInteractionError('INTERACTION_CLOSED')`.
    - Executes `options.action(interaction)` callback.
    - If action returns result without `result.transition?.ok`, throws `WorkflowHumanInteractionError('INVALID_INTERACTION_TRANSACTION')`.
    - Updates `human_interactions` row (`status = 'answered'`, `revision = result.transition.snapshot.revision`, updated `payload`).
    - Inserts event `'interaction_transitioned'` into `human_interaction_events`.
    - Inserts outbox event record into `outbox_events` if needed.
    - Returns updated `HumanInteractionRecord`.

---

### Step 3: Create Conformance Test Suite `factory/tests/test-conformance-evidence-interaction.mjs`

Create the executable ES module test suite `test-conformance-evidence-interaction.mjs`.

**Test Harness & Setup**:
- Imports `createInMemorySqlClient` from `./support/in-memory-sql-client.mjs`.
- Imports filesystem evidence and human interaction stores/repositories from `factory/lib/` and `factory/src/adapters/persistence/`.
- Imports `SqlWorkflowEvidenceRepository` and `SqlWorkflowHumanInteractionRepository` (either from `./runtime/factory-operational.mjs` or directly from `../src/adapters/persistence/sql/sql-workflow-evidence-repository.js` and `sql-workflow-human-interaction-repository.js`).
- Creates temporary directory structure for Filesystem repositories.
- Initializes `createInMemorySqlClient()` for SQL repositories.
- Seed in-memory database table structures (`workflow_evidence`, `human_interactions`, `human_interaction_events`, `outbox_events`, `workflow_instances`).

**Test Scenarios Executed Against BOTH Adapters (Parity Tests)**:
1. **Evidence Repository Parity**:
   - `record()` creates new evidence record with generated ID and timestamp.
   - `list()` returns chronologically ordered evidence records, filtered by `stepId`.
   - `record()` with same idempotency key and same payload returns `idempotent: true` and existing record.
   - `record()` with same idempotency key and divergent payload throws `IDEMPOTENCY_KEY_COLLISION`.
   - Append-only invariant verification: verify no SQL update/delete is ever issued on `workflow_evidence`.

2. **Human Interaction Repository Parity**:
   - `recordOpen()` successfully creates interaction and records `interaction_opening` and `interaction_opened` events.
   - `recordOpen()` with missing `transition` callback throws `HUMAN_INTERACTION_TRANSITION_REQUIRED`.
   - `recordOpen()` with invalid input throws `INVALID_INTERACTION`.
   - `recordOpen()` when interaction already open for same step throws `INTERACTION_ALREADY_OPEN`.
   - `recordOpen()` idempotency replay with same key returns existing interaction.
   - `recordOpen()` idempotency replay with divergent key throws `IDEMPOTENCY_KEY_COLLISION`.
   - `recordTransition()` / reply transitions interaction to answered/replied and updates revision.
   - `recordTransition()` with missing `action` callback throws `HUMAN_INTERACTION_ACTION_REQUIRED`.
   - `recordTransition()` on non-existent interaction throws `INTERACTION_NOT_FOUND`.
   - `recordTransition()` on already closed/replied interaction throws `INTERACTION_CLOSED`.
   - `reconcileOpen()` recovers interrupted openings or aborts appropriately according to workflow facts.

3. **SQL Specific Atomicity & Outbox Verification**:
   - **Transaction Rollback Atomicity**: Simulate a failure during transition callback inside `recordOpen` or `recordTransition`. Verify that ALL DB operations (`human_interactions`, `human_interaction_events`, `workflow_evidence`, `outbox_events`) are completely rolled back by `withTransaction`.
   - **Outbox Event Insertion**: Verify that transactional operations write corresponding events into `outbox_events` table inside the transaction.

**Runner Output**:
- Logs `✓ scenario_name` for passing assertions.
- Exits with `code 0` on success, `code 1` on failure.

---

## Verification Plan

### Manual / Test Execution
Run the newly created conformance test suite and existing related tests:
```bash
node factory/tests/test-conformance-evidence-interaction.mjs
node factory/tests/test-v5-migration-schema.mjs
node factory/tests/test-sql-repository-ports-adapters.mjs
node factory/tests/test-workflow-evidence.mjs
node factory/tests/test-workflow-human-interaction-source.mjs
```

Ensure all exit with code 0 and 0 failures.

---

## Plan Mirror Copy in Repo
Save copy to `specs/72211bd5_postgres_persistence_evidence_interaction.md`.
