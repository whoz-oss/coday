# WorkUnit and Worker domain/persistence layer

## What changed

Jalon C1-T1a adds the domain vocabulary, persistence ports, SQL adapters, and an executable conformance suite for `WorkUnit` and `Worker` in `factory/`.

The pure domains define validated records, machine-readable validation/error codes, lifecycle states, and allowed transitions without filesystem, Git, AgentOS, or other I/O dependencies:

- `WorkUnit`: `created → assigned → running → completed|failed|cancelled`, with the additional allowed reassignment/cancellation/failure paths encoded in `WORK_UNIT_TRANSITIONS`. It includes scheduling fields (`priority`, `notBefore`, `attemptCount`) and payload metadata.
- `Worker`: `offline`, `idle`, `busy`, and `maintenance`, with transitions for activation, work, shutdown, and maintenance. It includes heartbeat, protocol, capabilities, and payload metadata.

Both repository ports are tenant-scoped at construction time. Work units use `organizationId` + `workstreamId`; workers use `organizationId`. Their APIs cover `get`, `create`, `update`, `transition`, and filtered `list`; the worker port also exposes `heartbeat`.

The SQL adapters persist against the `work_units` and `workers` schemas, apply the shared domain validation/state rules, serialize JSON fields, and use `revision` compare-and-swap updates. They expose structured adapter errors such as `NOT_FOUND`, `INVALID_STATE`, `INVALID_TRANSITION`, and `REVISION_CONFLICT`. Work-unit listing orders by descending priority and eligibility timestamp; worker listing supports status/type filters. The adapters deliberately do not implement lease acquisition, fencing, or expiry.

## Files

- `factory/src/domain/work-unit.ts` — WorkUnit types, defaults vocabulary, validation, states, and transitions.
- `factory/src/domain/worker.ts` — Worker types, validation, states, and transitions.
- `factory/src/ports/persistence/work-unit-repository.ts` — tenant-scoped WorkUnit persistence contract.
- `factory/src/ports/persistence/worker-repository.ts` — tenant-scoped Worker persistence contract, including heartbeat.
- `factory/src/adapters/persistence/sql/sql-work-unit-repository.ts` — SQL WorkUnit implementation and `createSqlWorkUnitRepository` factory.
- `factory/src/adapters/persistence/sql/sql-worker-repository.ts` — SQL Worker implementation and `createSqlWorkerRepository` factory.
- `factory/tests/test-conformance-workunit-worker.mjs` — standalone Node/esbuild conformance checks.
- `specs/b0959943_workunit_worker_domain_ports_sql.md` — implementation design and verification plan for this change.

## How to verify

From the repository root:

```bash
node factory/tests/test-conformance-workunit-worker.mjs
npx tsc --noEmit --project factory/toolchain/tsconfig.json
```

The conformance script bundles the TypeScript adapters in memory and runs them against the existing in-memory SQL client. It checks CRUD/defaults, JSON round-trips, valid and invalid transitions, structured error codes, heartbeat updates, optimistic-locking conflicts, filtering/order, and organization/workstream isolation. The TypeScript command verifies the new source compiles without regenerating the runtime bundle.
