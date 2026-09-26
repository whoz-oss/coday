# Plan: Worker Runtime Core Module and Offline Unit Tests (Jalon C2-T1, Option A)

## Objective
Implement a thin worker runtime loop orchestrator under `factory/src/domain/worker-runtime/` that consumes the C1 lease protocol (`LeaseRepository`, `WorkUnitRepository`, `WorkerRepository`) and manages worker lifecycle (register/heartbeat/claim/execute/release/drain), delegating job execution to an injected `WorkExecutor`.

STRICT SAFETY RULE: NO DUPLICATE SAFETY RULES. Fencing, expiration, and exclusivity live in persistence adapters/domain (`leaseRepo`, `workUnitRepo`, etc.). The runtime orchestrates the loop and handles fencing cancellation/abort.

## Perimeter and Constraints (STRICT SCOPE)
- **DO NOT touch SQL adapters or domain modules from C1 (`factory/src/domain/lease/`, etc.)** — they are frozen.
- **DO NOT touch factory barrels or exports (`factory/src/index.ts`, `factory/src/domain/index.ts`, etc.)** — reserved for task W2.
- **DO NOT touch database migrations or generated operational bundle `factory/runtime/factory-operational.mjs`.**
- **DO NOT modify `agentos/**`.**
- **DO NOT wire real ADW dispatch** (Option B is out of scope).

---

## Files to Create

### 1. `factory/src/domain/worker-runtime/types.ts`
Define standard TypeScript interfaces and types for the worker runtime:

- `WorkExecutionResult`:
  - `status`: `'completed' | 'failed'`
  - `payloadUpdate?: Record<string, unknown>`
  - `error?: { code: string; message: string; details?: Record<string, unknown> }`

- `WorkExecutor`:
  - `execute(workUnit: WorkUnit, signal: AbortSignal): Promise<WorkExecutionResult>`

- `WorkerRuntimeConfig`:
  - `organizationId`: `string`
  - `workstreamId`: `string`
  - `workerId`: `string`
  - `workerType`: `string`
  - `leaseTtlMs`: `number`
  - `heartbeatIntervalMs`: `number`
  - `pollBackoffMs`: `number`
  - `concurrency`: `number` (default 1)
  - `environmentId?: string | null`
  - `capabilities?: string[]`

- `WorkerRuntimeLogger`:
  - `info(message: string, details?: Record<string, unknown>): void`
  - `warn(message: string, details?: Record<string, unknown>): void`
  - `error(message: string, details?: Record<string, unknown>): void`
  - `debug?(message: string, details?: Record<string, unknown>): void`

- `WorkerRuntimeClock`: `() => Date`

- `WorkerRuntimeTimers`:
  - `setInterval(handler: () => void, timeout: number): unknown`
  - `clearInterval(handle: unknown): void`
  - `setTimeout(handler: () => void, timeout: number): unknown`
  - `clearTimeout(handle: unknown): void`

- `WorkerRuntimeIdGenerator`: `() => string`

- `WorkerRuntimeDeps`:
  - `leaseRepo`: `LeaseRepository`
  - `workUnitRepo`: `WorkUnitRepository`
  - `workerRepo`: `WorkerRepository`
  - `executor`: `WorkExecutor`
  - `clock?: WorkerRuntimeClock` (defaults to `() => new Date()`)
  - `timers?: WorkerRuntimeTimers` (defaults to global `setInterval`, `clearInterval`, `setTimeout`, `clearTimeout`)
  - `logger?: WorkerRuntimeLogger` (defaults to no-op logger)
  - `idGenerator?: WorkerRuntimeIdGenerator` (defaults to `crypto.randomUUID`)

---

### 2. `factory/src/domain/worker-runtime/worker-runtime.ts`
Implement `WorkerRuntime` class orchestrating the worker loop:

#### State & Lifecycle
- `status`: `'stopped' | 'starting' | 'running' | 'draining' | 'stopped'`
- Active jobs map/set tracking currently running executions and their `AbortController`s.

#### Core Loop & Mechanics:
1. `start()`:
   - Registers/upserts worker via `workerRepo`:
     - Checks if worker exists via `workerRepo.get(config.workerId)`.
     - If non-existent: `workerRepo.create({...})`.
     - Transitions status to `'idle'` (or updates revision if exists).
   - Starts worker liveness heartbeat timer (`workerRepo.heartbeat`) using `heartbeatIntervalMs`.
   - Transitions internal state to `'running'`.
   - Enters poll loop (`_pollLoop()`).

2. `_pollLoop()`:
   - While `status === 'running'` and active jobs < `concurrency`:
     - Attempt to acquire work unit via `leaseRepo.acquire({ workerId, environmentId, ttlMs: leaseTtlMs })`.
     - If `null` returned: wait `pollBackoffMs` before polling again.
     - If `AcquireLeaseResult` returned (`lease`, `workUnitId`):
       - Fetch `WorkUnit` record via `workUnitRepo.get(workUnitId)`.
       - Transition worker state to `'busy'` (if active jobs transition 0 -> 1).
       - Spawn background processing of `_processWorkUnit(lease, workUnit)`.

3. `_processWorkUnit(lease, workUnit)`:
   - Create `AbortController`.
   - Start periodic lease heartbeat timer for this lease via `leaseRepo.renew({ organizationId, workstreamId, workUnitId, leaseId, fencingToken, ttlMs: leaseTtlMs })`.
   - Heartbeat Error Handling:
     - If `leaseRepo.renew` throws or returns `LEASE_FENCED` (or any `LeaseError` with code `LEASE_FENCED` or `LEASE_EXPIRED`):
       - Trigger `abortController.abort(new Error('LEASE_FENCED'))`.
       - Stop the lease heartbeat timer immediately.
       - Mark this job execution as fenced.
   - Call `executor.execute(workUnit, abortController.signal)`.
   - Execution Completion / Rejection Handling:
     - Clear lease heartbeat timer.
     - **CRITICAL FENCING GUARANTEE**: Check if the job was fenced during execution or if `abortController.signal.aborted` due to `LEASE_FENCED`.
     - **IF FENCED**:
       - DO NOT call `leaseRepo.release()`.
       - DO NOT call `workUnitRepo.transition()` to completed or failed.
       - Log fence rejection and return.
     - **IF NOT FENCED**:
       - If `executor.execute` succeeded with result:
         - Release lease via `leaseRepo.release({ organizationId, workstreamId, workUnitId, leaseId, fencingToken, resultStatus: result.status === 'completed' ? 'completed' : 'failed' })`.
       - If `executor.execute` threw an Error:
         - Release lease via `leaseRepo.release({ organizationId, workstreamId, workUnitId, leaseId, fencingToken, resultStatus: 'failed' })`.
   - Clean up active job tracking.
   - If active jobs == 0 and `status === 'running'`, transition worker state back to `'idle'`.

4. `stop()` / `drain()`:
   - Sets internal status to `'draining'`.
   - Prevents claiming any new work units.
   - Waits for active work units to complete or aborts them if a timeout is reached.
   - Clears worker heartbeat timer.
   - Transitions worker status to `'offline'` via `workerRepo.transition(workerId, 'offline', ...)`.
   - Sets status to `'stopped'`.

---

### 3. `factory/tests/test-worker-runtime-core.mjs`
Node test harness matching existing project standards (`test-lease-protocol.mjs`, `test-agentos-runtime-adapter.mjs`). Uses `node:module` resolver hook (`./support/node-ts-resolve-hook.mjs`) to import TS modules directly.

#### Mock Adapters & Dependencies in Test:
- Mock/Fake `LeaseRepository`, `WorkUnitRepository`, `WorkerRepository`.
- Deterministic Fake Executor.
- Simulated Clock and Timers (controllable manual time stepping or deterministic async promise steps).

#### Required Test Cases:
1. **Nominal Path**:
   - `start()` worker -> worker status registered as `idle`.
   - Claim work unit via `acquire()` -> work unit acquired, worker becomes `busy`.
   - Periodic lease heartbeat renews lease.
   - Executor completes successfully -> `leaseRepo.release` called with `resultStatus: 'completed'`, worker becomes `idle`.

2. **Executor Failure Path**:
   - Executor throws an error during execution.
   - Runtime catches error -> `leaseRepo.release` called with `resultStatus: 'failed'`, work unit marked failed, worker returns to `idle`.

3. **CRITICAL FENCING PATH**:
   - Runtime claims work unit and starts execution.
   - During periodic heartbeat, `leaseRepo.renew` rejects with `LeaseError('LEASE_FENCED')`.
   - `AbortSignal` is triggered on executor (`signal.aborted === true`).
   - Executor finishes or aborts, but runtime **REJECTS** terminal commit (`release` / `transition` is **NEVER** called for `completed` or `failed`).

4. **Clean Drain / Shutdown**:
   - Runtime running with active work unit.
   - `stop()` / `drain()` called.
   - New claims are halted (`_pollLoop` exits or skips claim).
   - Currently running work unit completes cleanly.
   - Worker transitions to `offline`.

---

## Verification Plan

### Execution Command:
Run the offline unit test:
```bash
node factory/tests/test-worker-runtime-core.mjs
```

### Affected Project Verification (as required by repository rules):
```bash
pnpm nx test factory
```

---

## Acceptance Criteria Checklist
- [ ] TypeScript compiles cleanly without errors.
- [ ] `factory/src/domain/worker-runtime/types.ts` created with all interfaces.
- [ ] `factory/src/domain/worker-runtime/worker-runtime.ts` created with loop orchestrator and strict fencing rule enforcement.
- [ ] `factory/tests/test-worker-runtime-core.mjs` created and passing all 4 required scenarios.
- [ ] No changes made to `factory/src/index.ts`, `factory/src/domain/lease/`, SQL adapters, or `factory/runtime/factory-operational.mjs`.
