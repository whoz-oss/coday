/**
 * Worker runtime core tests (Jalon C2-T1, Option A).
 *
 * Fully offline unit tests for `factory/src/domain/worker-runtime/`. The
 * runtime is wired against deterministic in-memory repositories that mirror the
 * C1 lease semantics, a manual clock/timer, and a controllable fake executor.
 *
 * Covered scenarios:
 *   1. nominal  — claim → periodic heartbeat (renew) → complete → release
 *                 (the release atomically commits the work-unit status).
 *   2. failure  — the executor throws → work unit marked `failed` + released.
 *   3. FENCING  — the lease heartbeat renew is rejected with `LEASE_FENCED`:
 *                 the executor's `AbortSignal` is triggered and **no** terminal
 *                 commit (no release with completed/failed) is ever performed.
 *   4. drain    — `stop()` stops claiming new work and lets the in-flight unit
 *                 finish cleanly before the worker goes `offline`.
 *
 * Usage   : node factory/tests/test-worker-runtime-core.mjs
 * Exit code: 0 = every case passed, 1 = at least one failure.
 */

import assert from 'node:assert/strict'
import { register } from 'node:module'

// Register the `.js` → `.ts` resolver so the runtime (whose sources import
// sibling modules with a `.js` specifier) can be imported directly by Node.
register(new URL('./support/node-ts-resolve-hook.mjs', import.meta.url))

const { WorkerRuntime } = await import('../src/domain/worker-runtime/worker-runtime.ts')
const { LeaseError, LEASE_ERROR_CODES } = await import('../src/domain/lease/lease.ts')
const { canTransitionWorker } = await import('../src/domain/worker.ts')

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    console.log(`\u2713 ${name}`)
    passed++
  } catch (error) {
    console.error(`\u2717 ${name}\n   ${error?.stack ?? error}`)
    failed++
  }
}

const ORG = 'org-1'
const WS = 'ws-1'
const WORKER_ID = 'worker-1'
const HEARTBEAT = 1000
const TTL = 5000
const BACKOFF = 60000
const BASE = Date.parse('2026-01-01T00:00:00.000Z')

const tick = () => new Promise((resolve) => queueMicrotask(resolve))

/** Flush `rounds` microtask turns so pending async orchestration settles. */
async function flush(rounds = 60) {
  for (let i = 0; i < rounds; i++) await tick()
}

/** Flush until `predicate()` holds (or give up after `rounds` turns). */
async function until(predicate, rounds = 400) {
  for (let i = 0; i < rounds; i++) {
    if (predicate()) return true
    await tick()
  }
  return Boolean(predicate())
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

/** Manual clock + timer scheduler: tests advance time explicitly. */
class ManualTimers {
  constructor(clockState) {
    this.clock = clockState
    this.tasks = new Map()
    this.nextId = 1
  }

  setTimeout(handler, timeout) {
    const id = this.nextId++
    this.tasks.set(id, { id, handler, time: this.clock.now + Math.max(0, timeout), interval: null })
    return id
  }

  setInterval(handler, timeout) {
    const id = this.nextId++
    const period = Math.max(1, timeout)
    this.tasks.set(id, { id, handler, time: this.clock.now + period, interval: period })
    return id
  }

  clearTimeout(id) {
    this.tasks.delete(id)
  }

  clearInterval(id) {
    this.tasks.delete(id)
  }

  async advance(ms) {
    const target = this.clock.now + Math.max(0, ms)
    let guard = 0
    while (guard++ < 5000) {
      let next = null
      for (const task of this.tasks.values()) {
        if (task.time > target) continue
        if (next === null || task.time < next.time || (task.time === next.time && task.id < next.id)) next = task
      }
      if (next === null) break
      this.clock.now = Math.max(this.clock.now, next.time)
      if (next.interval === null) this.tasks.delete(next.id)
      else next.time = this.clock.now + next.interval
      next.handler()
      await flush(20)
    }
    this.clock.now = Math.max(this.clock.now, target)
  }
}

function runtimeConfig(overrides = {}) {
  return {
    organizationId: ORG,
    workstreamId: WS,
    workerId: WORKER_ID,
    workerType: 'test-worker',
    leaseTtlMs: TTL,
    heartbeatIntervalMs: HEARTBEAT,
    pollBackoffMs: BACKOFF,
    concurrency: 1,
    ...overrides,
  }
}

function workUnit(workUnitId, extras = {}) {
  const at = new Date(BASE).toISOString()
  return {
    workUnitId,
    unitType: 'test-unit',
    status: 'created',
    revision: 1,
    priority: 0,
    notBefore: null,
    attemptCount: 0,
    payload: {},
    createdAt: at,
    updatedAt: at,
    ...extras,
  }
}

/**
 * Builds the runtime dependencies: in-memory lease / work-unit / worker
 * repositories that reproduce the C1 semantics (acquire claims *and* flips the
 * unit to `running`, release commits the terminal status, renew enforces the
 * fencing token), plus a manual clock/timer and a recording logger.
 */
function createHarness({ workUnits = [], executor }) {
  const clockState = { now: BASE }
  const clock = () => new Date(clockState.now)
  const timers = new ManualTimers(clockState)
  const nowIso = () => new Date(clockState.now).toISOString()

  const units = new Map(workUnits.map((entry) => [entry.workUnitId, { ...entry }]))
  const workers = new Map()
  const leases = new Map()
  let fencingSeq = 0

  const calls = { acquire: 0, renew: [], release: [], workerHeartbeat: 0 }
  const logger = {
    infos: [],
    warns: [],
    errors: [],
    info(message) {
      this.infos.push(message)
    },
    warn(message, details) {
      this.warns.push({ message, details })
    },
    error(message, details) {
      this.errors.push({ message, details })
    },
  }

  const workUnitRepo = {
    scope: { organizationId: ORG, workstreamId: WS },
    async get(workUnitId) {
      const unit = units.get(workUnitId)
      return unit ? { ...unit } : null
    },
    async create(input) {
      const unit = {
        priority: 0,
        notBefore: null,
        attemptCount: 0,
        payload: {},
        status: 'created',
        revision: 1,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        ...input,
      }
      units.set(unit.workUnitId, unit)
      return { ...unit }
    },
    async update(workUnitId, patch, expectedRevision) {
      const current = units.get(workUnitId)
      if (!current) throw Object.assign(new Error('NOT_FOUND'), { code: 'NOT_FOUND' })
      if (current.revision !== expectedRevision)
        throw Object.assign(new Error('REVISION_CONFLICT'), { code: 'REVISION_CONFLICT' })
      const next = { ...current, ...patch, revision: current.revision + 1, updatedAt: nowIso() }
      units.set(workUnitId, next)
      return { ...next }
    },
    async transition(workUnitId, nextState, expectedRevision, payloadUpdate) {
      const current = units.get(workUnitId)
      if (!current) throw Object.assign(new Error('NOT_FOUND'), { code: 'NOT_FOUND' })
      if (current.revision !== expectedRevision)
        throw Object.assign(new Error('REVISION_CONFLICT'), { code: 'REVISION_CONFLICT' })
      const next = {
        ...current,
        status: nextState,
        payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
        revision: current.revision + 1,
        updatedAt: nowIso(),
      }
      units.set(workUnitId, next)
      return { ...next }
    },
    async list() {
      return [...units.values()].map((unit) => ({ ...unit }))
    },
  }

  const workerRepo = {
    scope: { organizationId: ORG },
    async get(workerId) {
      const worker = workers.get(workerId)
      return worker ? cloneWorker(worker) : null
    },
    async create(input) {
      const worker = {
        status: 'offline',
        revision: 1,
        lastHeartbeatAt: null,
        protocolVersion: null,
        capabilities: [],
        payload: {},
        createdAt: nowIso(),
        updatedAt: nowIso(),
        ...input,
      }
      workers.set(worker.workerId, worker)
      return cloneWorker(worker)
    },
    async update(workerId, patch, expectedRevision) {
      const current = workers.get(workerId)
      if (!current) throw Object.assign(new Error('NOT_FOUND'), { code: 'NOT_FOUND' })
      if (current.revision !== expectedRevision)
        throw Object.assign(new Error('REVISION_CONFLICT'), { code: 'REVISION_CONFLICT' })
      const next = { ...current, ...patch, revision: current.revision + 1, updatedAt: nowIso() }
      workers.set(workerId, next)
      return cloneWorker(next)
    },
    async transition(workerId, nextState, expectedRevision, payloadUpdate) {
      const current = workers.get(workerId)
      if (!current) throw Object.assign(new Error('NOT_FOUND'), { code: 'NOT_FOUND' })
      if (current.revision !== expectedRevision)
        throw Object.assign(new Error('REVISION_CONFLICT'), { code: 'REVISION_CONFLICT' })
      if (!canTransitionWorker(current.status, nextState))
        throw Object.assign(new Error('INVALID_TRANSITION'), { code: 'INVALID_TRANSITION' })
      const next = {
        ...current,
        status: nextState,
        payload: payloadUpdate ? { ...current.payload, ...payloadUpdate } : current.payload,
        revision: current.revision + 1,
        updatedAt: nowIso(),
      }
      workers.set(workerId, next)
      return cloneWorker(next)
    },
    async heartbeat(workerId, heartbeatAt, expectedRevision) {
      const current = workers.get(workerId)
      if (!current) throw Object.assign(new Error('NOT_FOUND'), { code: 'NOT_FOUND' })
      if (expectedRevision !== undefined && current.revision !== expectedRevision)
        throw Object.assign(new Error('REVISION_CONFLICT'), { code: 'REVISION_CONFLICT' })
      const next = { ...current, lastHeartbeatAt: heartbeatAt, revision: current.revision + 1, updatedAt: nowIso() }
      workers.set(workerId, next)
      calls.workerHeartbeat++
      return cloneWorker(next)
    },
    async list() {
      return [...workers.values()].map(cloneWorker)
    },
  }

  const leaseRepo = {
    async acquire(options) {
      calls.acquire++
      const eligible = [...units.values()]
        .filter(
          (unit) =>
            (unit.status === 'created' || unit.status === 'failed') &&
            (unit.notBefore === null || unit.notBefore <= nowIso())
        )
        .sort((left, right) =>
          right.priority - left.priority !== 0
            ? right.priority - left.priority
            : left.createdAt.localeCompare(right.createdAt)
        )
      const candidate = eligible[0]
      if (!candidate) return null
      const at = nowIso()
      candidate.status = 'running'
      candidate.attemptCount += 1
      candidate.revision += 1
      candidate.updatedAt = at
      const fencingToken = ++fencingSeq
      const lease = {
        organizationId: options.organizationId ?? ORG,
        workstreamId: options.workstreamId ?? WS,
        workUnitId: candidate.workUnitId,
        leaseId: `lease-${fencingToken}`,
        workerId: options.workerId,
        environmentId: options.environmentId ?? null,
        status: 'active',
        fencingToken,
        acquiredAt: at,
        leaseExpiresAt: new Date(clockState.now + options.ttlMs).toISOString(),
        heartbeatAt: at,
        releasedAt: null,
        expiryReason: null,
        createdAt: at,
      }
      leases.set(lease.leaseId, lease)
      return { lease: { ...lease }, workUnitId: candidate.workUnitId }
    },
    async renew(options) {
      calls.renew.push({ ...options })
      const lease = leases.get(options.leaseId)
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      if (lease.status !== 'active')
        throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId })
      if (lease.fencingToken !== options.fencingToken)
        throw new LeaseError(LEASE_ERROR_CODES.LEASE_FENCED, { leaseId: lease.leaseId })
      lease.leaseExpiresAt = new Date(clockState.now + options.ttlMs).toISOString()
      lease.heartbeatAt = nowIso()
      return { ...lease }
    },
    async release(options) {
      calls.release.push({ ...options })
      const lease = leases.get(options.leaseId)
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      if (options.fencingToken !== undefined && lease.fencingToken !== options.fencingToken)
        throw new LeaseError(LEASE_ERROR_CODES.LEASE_FENCED, { leaseId: lease.leaseId })
      if (lease.status !== 'active')
        throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId, status: lease.status })
      const at = nowIso()
      lease.status = 'released'
      lease.releasedAt = at
      const unit = units.get(lease.workUnitId)
      if (unit) {
        unit.status = options.resultStatus ?? 'completed'
        unit.revision += 1
        unit.updatedAt = at
      }
      return { ...lease }
    },
    async expire() {
      return []
    },
    async findByLeaseId(_organizationId, _workstreamId, _workUnitId, leaseId) {
      const lease = leases.get(leaseId)
      return lease ? { ...lease } : null
    },
    async findActiveLeaseByWorkUnit(_organizationId, _workstreamId, workUnitId) {
      const lease = [...leases.values()].find((entry) => entry.workUnitId === workUnitId && entry.status === 'active')
      return lease ? { ...lease } : null
    },
  }

  let idSeq = 0
  const deps = {
    leaseRepo,
    workUnitRepo,
    workerRepo,
    executor,
    clock,
    timers,
    logger,
    idGenerator: () => `inst-${++idSeq}`,
  }

  return { clockState, timers, deps, units, workers, leases, calls, logger, leaseRepo }
}

function cloneWorker(worker) {
  return { ...worker, capabilities: [...worker.capabilities], payload: { ...worker.payload } }
}

// ---------------------------------------------------------------------------
// 1. Nominal path
// ---------------------------------------------------------------------------

await test('nominal: claim → heartbeat → complete → release (terminal commit)', async () => {
  const execution = deferred()
  let captured = null
  const harness = createHarness({
    workUnits: [workUnit('wu-1')],
    executor: {
      async execute(unit, signal) {
        captured = { unit, signal }
        return execution.promise
      },
    },
  })
  const runtime = new WorkerRuntime(runtimeConfig(), harness.deps)

  await runtime.start()
  assert.ok(await until(() => captured !== null), 'executor should be invoked')
  assert.equal(captured.unit.workUnitId, 'wu-1')
  assert.ok(await until(() => harness.workers.get(WORKER_ID)?.status === 'busy'), 'worker should be busy')

  await harness.timers.advance(HEARTBEAT)
  assert.equal(harness.calls.renew.length, 1, 'one lease heartbeat should fire')
  assert.equal(harness.calls.renew[0].fencingToken, 1, 'heartbeat carries the fencing token')
  assert.ok(harness.calls.workerHeartbeat >= 1, 'worker liveness heartbeat should fire')

  execution.resolve({ status: 'completed', payloadUpdate: { done: true } })
  assert.ok(await until(() => harness.calls.release.length === 1), 'lease should be released')

  const release = harness.calls.release[0]
  assert.equal(release.resultStatus, 'completed')
  assert.equal(release.fencingToken, 1)
  assert.equal(release.leaseId, 'lease-1')
  assert.equal(harness.units.get('wu-1').status, 'completed', 'work unit committed as completed')
  assert.deepEqual(harness.units.get('wu-1').payload, { done: true }, 'payload update applied')
  assert.ok(await until(() => harness.workers.get(WORKER_ID)?.status === 'idle'), 'worker back to idle')

  await runtime.stop()
  assert.equal(runtime.status, 'stopped')
  assert.equal(harness.workers.get(WORKER_ID).status, 'offline')
})

// ---------------------------------------------------------------------------
// 2. Executor failure path
// ---------------------------------------------------------------------------

await test('failure: a throwing executor marks the unit failed and releases', async () => {
  const harness = createHarness({
    workUnits: [workUnit('wu-2')],
    executor: {
      async execute() {
        throw Object.assign(new Error('boom'), { code: 'EXEC_BOOM' })
      },
    },
  })
  const runtime = new WorkerRuntime(runtimeConfig(), harness.deps)

  await runtime.start()
  assert.ok(await until(() => harness.calls.release.length === 1), 'lease should be released')

  const release = harness.calls.release[0]
  assert.equal(release.resultStatus, 'failed')
  assert.equal(harness.units.get('wu-2').status, 'failed', 'work unit committed as failed')
  assert.ok(await until(() => harness.workers.get(WORKER_ID)?.status === 'idle'))

  await runtime.stop()
  assert.equal(harness.workers.get(WORKER_ID).status, 'offline')
})

// ---------------------------------------------------------------------------
// 3. CRITICAL fencing path
// ---------------------------------------------------------------------------

await test('fencing: LEASE_FENCED aborts execution and forbids any terminal commit', async () => {
  let captured = null
  const harness = createHarness({
    workUnits: [workUnit('wu-3')],
    executor: {
      execute(unit, signal) {
        captured = { unit, signal }
        return new Promise((_resolve, reject) => {
          signal.addEventListener('abort', () =>
            reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
          )
        })
      },
    },
  })
  // The adapter owns fencing: simulate the lease being stolen/re-assigned, so
  // every renew with our stale token is rejected.
  harness.leaseRepo.renew = async () => {
    throw new LeaseError(LEASE_ERROR_CODES.LEASE_FENCED, { reason: 'test' })
  }

  const runtime = new WorkerRuntime(runtimeConfig(), harness.deps)
  await runtime.start()
  assert.ok(await until(() => captured !== null), 'executor should be invoked')

  await harness.timers.advance(HEARTBEAT)
  assert.ok(await until(() => captured.signal.aborted), 'executor AbortSignal must be triggered')
  await flush()

  assert.equal(harness.calls.release.length, 0, 'no release may be attempted for a fenced lease')
  assert.equal(
    harness.units.get('wu-3').status,
    'running',
    'work unit must not reach a terminal state on a fence'
  )
  assert.ok(
    harness.logger.warns.some((entry) => /fenced/i.test(entry.message)),
    'the fence must be logged'
  )
  assert.ok(await until(() => harness.workers.get(WORKER_ID)?.status === 'idle'), 'worker returns to idle')

  await runtime.stop()
  assert.equal(harness.workers.get(WORKER_ID).status, 'offline')
})

// ---------------------------------------------------------------------------
// 4. Clean drain / shutdown
// ---------------------------------------------------------------------------

await test('drain: stops claiming new work and completes the running unit', async () => {
  const execution = deferred()
  let captured = null
  const harness = createHarness({
    workUnits: [workUnit('wu-4'), workUnit('wu-5')],
    executor: {
      async execute(unit, signal) {
        captured = { unit, signal }
        return execution.promise
      },
    },
  })
  const runtime = new WorkerRuntime(runtimeConfig(), harness.deps)

  await runtime.start()
  assert.ok(await until(() => captured !== null), 'first unit should be claimed')
  assert.equal(captured.unit.workUnitId, 'wu-4')
  const acquiresBefore = harness.calls.acquire

  const stopping = runtime.stop()
  assert.ok(await until(() => runtime.status === 'draining'), 'runtime enters draining')
  await flush(60)
  assert.equal(harness.calls.acquire, acquiresBefore, 'no new claim is attempted while draining')
  assert.equal(captured.signal.aborted, false, 'the running unit is allowed to finish')

  execution.resolve({ status: 'completed' })
  await stopping

  assert.equal(harness.calls.release.at(-1).resultStatus, 'completed')
  assert.equal(harness.units.get('wu-4').status, 'completed')
  assert.equal(harness.units.get('wu-5').status, 'created', 'the second unit is left untouched')
  assert.equal(harness.workers.get(WORKER_ID).status, 'offline')
  assert.equal(runtime.status, 'stopped')
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
