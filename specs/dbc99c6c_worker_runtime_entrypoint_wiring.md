# Plan Implementation — Local Worker Runtime Wiring, Entrypoint, JS Facade & Documentation (C2-T2)

## 1. Overview & Objectives

Target feature: Local worker runtime entrypoint, JS facade export, operational bundle wiring, and documentation for C2-T2.

The goal is to complete the wiring and entrypoint surface for the local worker runtime without modifying frozen domains or SQL persistence adapters, and to provide comprehensive documentation for running the worker locally against PostgreSQL.

### Architecture Constraints
- `factory/src/domain/worker-runtime/worker-runtime.ts` and `types.ts` are **FROZEN** (C2-T1). Do **NOT** modify.
- `createSqlLeaseRepository`, `createSqlWorkUnitRepository`, `createSqlWorkerRepository` and `createPgPoolClient` in `factory/src/adapters/persistence/sql/` are **FROZEN**. Do **NOT** modify.
- Demo `WorkExecutor`: MUST be a deterministic no-op / echo executor that logs execution details, simulates a slight delay (~50-100ms or configurable/non-blocking), and returns a successful `completed` status without dispatching any real ADW tasks.
- Operational Bundle: `factory/runtime/factory-operational.mjs` MUST be re-generated strictly by executing `node factory/toolchain/build.mjs`. Never edit `factory-operational.mjs` directly.
- JS Facade: `factory/lib/worker-runtime.mjs` MUST be a stateless JS facade re-exporting the worker runtime surface from `../runtime/factory-operational.mjs`.

---

## 2. Proposed Changes

### File 1: `factory/src/entrypoints/worker-runtime.ts` (NEW)
Create the entrypoint and local runtime launcher for the worker runtime.
- **Exports**:
  - Re-export everything from `../domain/worker-runtime/types.js` and `../domain/worker-runtime/worker-runtime.js`.
  - Export `createDemoWorkExecutor(options?: { delayMs?: number, logger?: WorkerRuntimeLogger }): WorkExecutor`.
    - Implementation: Returns a `WorkExecutor` that logs execution of a work unit (`workUnitId`, `unitType`, `payload`), waits `delayMs` (default e.g. 50ms, checking `signal.aborted`), and returns `{ status: 'completed', payloadUpdate: { executedAt: new Date().toISOString(), executor: 'demo-echo' } }`.
  - Export `createLocalWorkerRuntime(options?: LocalWorkerRuntimeOptions): Promise<{ runtime: WorkerRuntime, client: SqlClient, stop: () => Promise<void> }>` or `runLocalWorker(options?: LocalWorkerRuntimeOptions): Promise<...>`.
    - Configuration options (`LocalWorkerRuntimeOptions`):
      - `dbConfig?: Partial<SqlDatabaseConfig>` (defaults resolved via `resolveSqlDatabaseConfig()`)
      - `organizationId?: string` (default `'default'`)
      - `workstreamId?: string` (default `'default'`)
      - `workerId?: string` (default `process.env.WORKER_ID ?? 'local-worker-1'`)
      - `workerType?: string` (default `'local-demo-worker'`)
      - `leaseTtlMs?: number` (default `Number(process.env.LEASE_TTL_MS) || 30000`)
      - `heartbeatIntervalMs?: number` (default `10000`)
      - `pollBackoffMs?: number` (default `2000`)
      - `concurrency?: number` (default `1`)
      - `executor?: WorkExecutor` (defaults to `createDemoWorkExecutor()`)
      - `logger?: WorkerRuntimeLogger` (defaults to console-backed logger)
    - Logic:
      1. Initializes `SqlClient` via `createPgPoolClient(config)`.
      2. Instantiates `leaseRepo` via `createSqlLeaseRepository({ client })`.
      3. Instantiates `workUnitRepo` via `createSqlWorkUnitRepository({ client })`.
      4. Instantiates `workerRepo` via `createSqlWorkerRepository({ client })`.
      5. Constructs `WorkerRuntime` instance with config & deps.
      6. Returns `{ runtime, client, start: () => runtime.start(), stop: () => runtime.stop() }`.

### File 2: `factory/src/entrypoints/factory-operational.ts` (MODIFY)
Add worker runtime re-exports to the operational bundle entrypoint.
- Add exports:
  ```ts
  export * from '../domain/worker-runtime/types.js'
  export * from '../domain/worker-runtime/worker-runtime.js'
  export * from './worker-runtime.js'
  ```

### File 3: `factory/lib/worker-runtime.mjs` (NEW)
Create stateless JS facade for legacy / root library access.
- Contents: Re-exports from `../runtime/factory-operational.mjs`:
  ```js
  import {
    WorkerRuntime,
    createDemoWorkExecutor,
    createLocalWorkerRuntime,
  } from '../runtime/factory-operational.mjs'

  export {
    WorkerRuntime,
    createDemoWorkExecutor,
    createLocalWorkerRuntime,
  }
  export * from '../runtime/factory-operational.mjs'
  ```

### File 4: `factory/infra/README.md` (MODIFY)
Update the README to include local worker runtime execution instructions:
- Environmental variables: `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `WORKER_ID`, `LEASE_TTL_MS`.
- How to launch the worker using Node.js inline script or runner:
  ```bash
  node -e "
    import { createLocalWorkerRuntime } from './factory/lib/worker-runtime.mjs';
    const worker = await createLocalWorkerRuntime();
    await worker.start();
    console.log('Worker running...');
  "
  ```
- SQL example to enqueue a demo work unit into `work_units`:
  ```sql
  INSERT INTO work_units (
    organization_id, workstream_id, work_unit_id, unit_type, status, priority, payload
  ) VALUES (
    'default', 'default', 'wu-demo-1', 'demo-echo', 'created', 10, '{"message": "Hello Worker"}'
  );
  ```
- Detailed breakdown of how to observe state transition:
  1. `created` (on INSERT)
  2. `running` (when acquired by worker & lease created in `work_unit_leases`)
  3. `completed` (after WorkExecutor finishes and lease is released)
- Instructions for stopping the worker cleanly (`worker.stop()`).

### File 5: `factory/tests/test-worker-runtime-entrypoint.mjs` (NEW)
Add automated integration/unit tests for `worker-runtime.ts` and `factory/lib/worker-runtime.mjs`:
- Verify `createDemoWorkExecutor` executes deterministically and respects `AbortSignal`.
- Verify `createLocalWorkerRuntime` builds dependencies correctly.
- Verify `factory/lib/worker-runtime.mjs` correctly exports `WorkerRuntime`, `createDemoWorkExecutor`, and `createLocalWorkerRuntime`.

---

## 3. Step-by-Step Action Plan

1. **Implement Entrypoint**:
   - Create `factory/src/entrypoints/worker-runtime.ts` with `createDemoWorkExecutor` and `createLocalWorkerRuntime`.

2. **Update Operational Entrypoint**:
   - Update `factory/src/entrypoints/factory-operational.ts` to export worker runtime types and utilities.

3. **Build Operational Bundle**:
   - Run `node factory/toolchain/build.mjs` to regenerate `factory/runtime/factory-operational.mjs`.

4. **Create JS Facade**:
   - Create `factory/lib/worker-runtime.mjs` delegating to `../runtime/factory-operational.mjs`.

5. **Add Tests**:
   - Create `factory/tests/test-worker-runtime-entrypoint.mjs`.
   - Update `factory/tests/typescript-factory-operational.mjs` if metafile input count checks require updating.

6. **Update Documentation**:
   - Complete `factory/infra/README.md` with worker runtime launch instructions, env vars, SQL demo queries, and lifecycle observation steps.

7. **Verification**:
   - Run unit tests: `node factory/tests/test-worker-runtime-core.mjs`
   - Run entrypoint tests: `node factory/tests/test-worker-runtime-entrypoint.mjs`
   - Run bundle tests: `node factory/tests/typescript-factory-operational.mjs`
   - Run SQL repository contract tests: `node factory/tests/test-sql-repository-ports-adapters.mjs`

---

## 4. Verification & Criteria Checklist

- [ ] Demo WorkExecutor runs deterministically with no real ADW execution.
- [ ] Runtime domain (`worker-runtime.ts`, `types.ts`) and SQL adapters (`sql/index.ts`) are untouched.
- [ ] `factory/runtime/factory-operational.mjs` regenerated cleanly via `node factory/toolchain/build.mjs`.
- [ ] `factory/lib/worker-runtime.mjs` facade delegates directly to bundle.
- [ ] Tests pass without error.
- [ ] `factory/infra/README.md` contains complete launching guide, env vars, and SQL examples.
