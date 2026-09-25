# PostgreSQL evidence and human-interaction persistence

Milestone B3-T1 adds SQL persistence adapters and an executable conformance suite for workflow evidence and human interactions. The adapters use the existing `SqlClient`/transaction abstractions, default tenant scope (`organizationId` and `workstreamId` = `default`), and preserve the filesystem adapters’ idempotency and error-code behavior.

## What changed

- `factory/src/adapters/persistence/sql/sql-workflow-evidence-repository.ts`
  - Adds `SqlWorkflowEvidenceRepository` and its factory.
  - Implements tenant-scoped `list` and transactional `record` operations.
  - Stores evidence payloads as JSONB, checks idempotency scope/fingerprint hashes, returns idempotent replays, and raises `IDEMPOTENCY_KEY_COLLISION` for divergent replays.
  - Evidence writes only issue `INSERT` statements against `workflow_evidence`; listing filters by step and sorts chronologically.
  - Exports a SQL-side `WorkflowEvidenceStoreError` compatible with the filesystem error code contract.

- `factory/src/adapters/persistence/sql/sql-workflow-human-interaction-repository.ts`
  - Adds `SqlWorkflowHumanInteractionRepository` and its factory.
  - Persists the current interaction projection in `human_interactions` and the append-only event log in `human_interaction_events`; `list` rebuilds records by replaying events and validates corrupt logs.
  - Implements `list`, `events`, `reconcileOpen`, `recordOpen`, and `recordTransition`, including the filesystem-compatible lifecycle, recovery, validation, idempotency, and repository error codes.
  - Wraps opening and replying flows in `withTransaction`. Successful flows update the interaction projection, append events, write outbox records, and—when a reply supplies it—append evidence. Failures roll back the complete SQL unit of work.
  - Adds tenant predicates to all adapter queries and maps domain statuses to the SQL status values.

- `factory/tests/test-conformance-evidence-interaction.mjs`
  - Provides a standalone Node test runner that executes the same evidence and interaction scenarios against filesystem repositories and SQL repositories backed by `createInMemorySqlClient()`.
  - Covers idempotent replay/collision behavior, filtering and ordering, interaction opening/replying, recovery, required callbacks, and lifecycle error codes.
  - Adds SQL-specific assertions for tenant columns, evidence append-only behavior, transactional outbox writes, and rollback of interaction events, projections, evidence, and outbox rows.
  - Exits `0` when all cases pass and `1` when any case fails.

- `factory/tests/support/node-ts-resolve-hook.mjs`
  - Supplies the test runner’s Node ESM loader hook so relative `.js` TypeScript import specifiers resolve to `.ts` sources when the JavaScript sibling is absent.

- `specs/72211bd5_postgres_persistence_evidence_interaction.md`
  - Records the implementation plan, schema assumptions, error contracts, atomicity requirements, test scenarios, and verification commands for this task.

## Verification

Run the focused suite directly from the repository root:

```bash
node factory/tests/test-conformance-evidence-interaction.mjs
```

The suite prints each passing/failing scenario and a final count. The SQL adapters are intentionally imported directly in this test; SQL barrel/index wiring was not part of these changes and remains reserved for the later wiring task.
