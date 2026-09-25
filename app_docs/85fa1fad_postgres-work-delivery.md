# PostgreSQL work-environment and delivery adapters

## What changed

The factory now has SQL persistence implementations for the work-environment and delivery repository ports, plus a cross-adapter conformance runner. The adapters are driver-agnostic: they consume the existing `SqlClient`, scope reads and writes by organization/workstream, and use transactions for mutations. They are intended to preserve the filesystem adapters’ domain validation, lifecycle rules, idempotency behavior, and machine error codes.

## Implementation files

- `factory/src/adapters/persistence/sql/sql-work-environment-repository.ts`
  - Persists the validated `WorkUnitEnvironment` descriptor as JSONB in `work_environments`.
  - Maps domain lifecycle states to the SQL status vocabulary, maintains revisions, and uses revision-guarded updates.
  - Supports virtual SQL artifact paths, reserve/read/list, namespace filtering, idempotent reservation, and validated lifecycle transitions.
  - Rejects invalid namespaces/environments, missing rows, stale revisions, invalid transitions, and corrupt payloads with filesystem-compatible error codes.

- `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`
  - Stores delivery snapshots in `deliveries` and immutable operation/request records in `delivery_journal`.
  - Implements create/read, policy-checked promotion, snapshot patching, delivery-operation creation and transitions, indeterminate-operation detection/reconciliation, and rollback request/approval handling.
  - Reuses the delivery domain’s normalization, identity, hashing, policy, and contract validators. Per-delivery serialization plus `withTransaction` keeps snapshot and journal mutations consistent.

- `factory/tests/test-conformance-work-environment-delivery.mjs`
  - Bundles the TypeScript SQL adapters in memory and runs the same scenarios against filesystem and in-memory SQL implementations.
  - Covers reserve/idempotency/list/transition behavior, delivery promotion and collisions, operation lifecycle and reconciliation, rollback lifecycle, snapshot updates, and expected error codes.
  - Compares deterministic observations from both backends to enforce behavioral parity without writing a generated bundle.

- `specs/85fa1fad_postgres_work_env_delivery_adapters.md`
  - Records the requested perimeter, review points, verification commands, and the intended conventional commit subject.

## Verification

Run the focused conformance suite:

```bash
node factory/tests/test-conformance-work-environment-delivery.mjs
```

The runner exits `0` only when all six scenario/parity checks pass. Run the existing SQL adapter regression suite as well:

```bash
node factory/tests/test-sql-repository-ports-adapters.mjs
```

The implementation plan records an expected successful result of 21 checks for that runner. The conformance suite uses `factory/tests/support/in-memory-sql-client.mjs`, so these focused checks do not require a live PostgreSQL service. The intended change perimeter is the two SQL repositories and the new conformance test; the captured diff also contains the associated plan document listed above.
