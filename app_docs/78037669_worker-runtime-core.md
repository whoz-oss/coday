# Worker runtime core

## What changed

Added the C2-T1 worker runtime core as a thin orchestrator over the existing C1 lease protocol. `WorkerRuntime` now manages registration, worker liveness, polling and acquisition, concurrent execution, lease renewal, terminal release, and graceful drain. Safety rules remain delegated to the injected repositories/domain: the runtime does not calculate expiry or enforce exclusivity, and treats fencing-related protocol errors as ownership loss.

The runtime is constructed with `WorkerRuntimeConfig` and `WorkerRuntimeDeps`. Dependencies include lease, work-unit, and worker repositories; a `WorkExecutor`; and injectable clock, timers, logger, and ID generator seams. Configuration includes organization/workstream/worker identity, lease and polling timings, optional environment/capability metadata, and bounded concurrency (defaulting to one).

During execution, each acquired lease gets an `AbortController` and a periodic renewal carrying its fencing token. `LEASE_FENCED`, `LEASE_EXPIRED`, and `LEASE_NOT_FOUND` renewal errors mark the job fenced, stop its renewal timer, abort the executor, and discard its result without attempting a terminal release. Non-fenced success and failure outcomes release the lease with `completed` or `failed`; successful payload updates are applied before completion. A timed drain can abort in-flight work and release it as `created` for re-queueing, while a normal drain waits for current work and prevents further claims before taking the worker offline.

## Files

- `factory/src/domain/worker-runtime/types.ts` defines the executor/result contracts, runtime configuration and injected ports, logger/timer seams, lifecycle statuses, and stop options.
- `factory/src/domain/worker-runtime/worker-runtime.ts` implements `WorkerRuntime`, including worker registration/heartbeat, the claim loop, per-lease heartbeats, fencing cancellation, release handling, concurrency tracking, and drain/shutdown behavior.
- `factory/tests/test-worker-runtime-core.mjs` provides an offline Node harness with a manual clock/timer scheduler, deterministic executor controls, and in-memory repository implementations mirroring the lease protocol. It covers nominal heartbeat/complete/release, executor failure, fencing abort with no terminal commit, and clean drain.
- `specs/78037669_worker_runtime_core.md` records the implementation scope, protocol behavior, test scenarios, and verification plan.

No factory barrels, SQL/C1 modules, migrations, generated operational bundle, AgentOS code, or real ADW dispatch wiring were changed.

## Verification and use

Run the focused offline harness from the repository root:

```bash
node factory/tests/test-worker-runtime-core.mjs
```

For project-level verification, use the documented Nx command:

```bash
pnpm nx test factory
```

To use the runtime, instantiate `WorkerRuntime` with the required repository implementations, an executor that accepts `(workUnit, signal)`, and the runtime configuration, then call `start()`. Call `stop()` to enter a graceful drain; optionally pass `{ drainTimeoutMs }` to abort work that exceeds the drain window. The runtime's `status`, `activeCount`, `instanceId`, and `worker` accessors expose lifecycle/observation state.
