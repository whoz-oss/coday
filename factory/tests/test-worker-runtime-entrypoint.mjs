/**
 * Local worker-runtime entrypoint tests (Jalon C2-T2).
 *
 * Covers the C2-T2 wiring surface without any PostgreSQL server, Docker or
 * `pg` driver:
 *   1. `createDemoWorkExecutor` — deterministic no-op executor: it reports
 *      `completed` with a bounded `demo-echo` payload patch, logs the execution
 *      and honours the `AbortSignal` (no real ADW dispatch).
 *   2. `createLocalWorkerRuntime` — wires the real C1 SQL repositories over an
 *      in-memory `SqlClient` and drives a seeded work unit through
 *      `created → running → completed`.
 *   3. `factory/lib/worker-runtime.mjs` — stateless facade re-exporting the
 *      generated bundle surface.
 *
 * Usage   : node factory/tests/test-worker-runtime-mjs
 * Exit code: 0 = every case passed, 1 = at least one failure.
 */

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'

// The C2-T2 entrypoint is exercised through the generated operational bundle
// (`factory/runtime/factory-operational.mjs`), which is built from
// `factory/src/entrypoints/worker-runtime.ts`. Importing the TypeScript source
// directly would drag the filesystem persistence adapters (whose `private
// readonly` parameter properties Node's strip-only TypeScript cannot parse);
// the bundle is the shipped, dependency-complete surface and is what the
// `factory/lib/worker-runtime.mjs` facade re-exports.
const bundle = await import('../runtime/factory-operational.mjs')
const { WorkerRuntime, createDemoWorkExecutor, createLocalWorkerRuntime, runLocalWorker } = bundle
const { createInMemorySqlClient } = await import('./support/in-memory-sql-client.mjs')

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

const silentLogger = { info() {}, warn() {}, error() {}, debug() {} }

function workUnitFixture(overrides = {}) {
  const at = '2026-01-01T00:00:00.000Z'
  return {
    workUnitId: 'wu-fixture',
    unitType: 'demo-echo',
    status: 'running',
    revision: 2,
    priority: 0,
    notBefore: null,
    attemptCount: 1,
    payload: {},
    createdAt: at,
    updatedAt: at,
    ...overrides,
  }
}

async function waitFor(predicate, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await delay(10)
  }
  return Boolean(await predicate())
}

// ---------------------------------------------------------------------------
// 1. Demo executor
// ---------------------------------------------------------------------------

await test('demo executor: deterministic completed result, no ADW dispatch', async () => {
  const logs = []
  const logger = {
    info: (message, details) => logs.push({ message, details }),
    warn() {},
    error() {},
    debug() {},
  }
  const executor = createDemoWorkExecutor({ delayMs: 1, logger })

  const first = await executor.execute(workUnitFixture(), new AbortController().signal)
  const second = await executor.execute(workUnitFixture(), new AbortController().signal)

  assert.equal(first.status, 'completed')
  assert.equal(second.status, 'completed')
  assert.equal(first.payloadUpdate.executor, 'demo-echo')
  assert.ok(typeof first.payloadUpdate.executedAt === 'string')
  // Bounded, echo-only payload: no ADW result shape leaks through.
  assert.deepEqual(Object.keys(first.payloadUpdate).sort(), ['executedAt', 'executor'])
  assert.ok(
    logs.some(({ message }) => /start/.test(message)),
    'executor logs the start of the execution'
  )
  assert.ok(
    logs.some(({ message }) => /completed/.test(message)),
    'executor logs the completion'
  )
  assert.equal(logs[0].details.workUnitId, 'wu-fixture')
})

await test('demo executor: honours the abort signal', async () => {
  const executor = createDemoWorkExecutor({ delayMs: 50, logger: silentLogger })
  const controller = new AbortController()
  const pending = executor.execute(workUnitFixture(), controller.signal)
  await delay(2)
  controller.abort(new Error('STOP_REQUESTED'))
  await assert.rejects(pending, /STOP_REQUESTED/)
})

await test('demo executor: rejects immediately when the signal is already aborted', async () => {
  const executor = createDemoWorkExecutor({ delayMs: 50, logger: silentLogger })
  const controller = new AbortController()
  controller.abort(new Error('ALREADY_ABORTED'))
  await assert.rejects(executor.execute(workUnitFixture(), controller.signal), /ALREADY_ABORTED/)
})

// ---------------------------------------------------------------------------
// 2. Local wiring + end-to-end lifecycle over the in-memory client
// ---------------------------------------------------------------------------

await test('createLocalWorkerRuntime: wires the C1 SQL repositories and config', async () => {
  const injectedExecutor = {
    async execute() {
      return { status: 'completed' }
    },
  }
  const client = createInMemorySqlClient()
  const handle = await createLocalWorkerRuntime({
    client,
    workerId: 'worker-wired',
    workerType: 'unit-test-worker',
    organizationId: 'org-wired',
    workstreamId: 'ws-wired',
    leaseTtlMs: 42_000,
    heartbeatIntervalMs: 500,
    pollBackoffMs: 7,
    concurrency: 2,
    environmentId: 'env-wired',
    capabilities: ['demo'],
    protocolVersion: '2',
    executor: injectedExecutor,
    logger: silentLogger,
  })

  assert.ok(handle.runtime instanceof WorkerRuntime)
  assert.equal(handle.client, client)
  assert.equal(handle.executor, injectedExecutor)
  assert.equal(handle.config.organizationId, 'org-wired')
  assert.equal(handle.config.workstreamId, 'ws-wired')
  assert.equal(handle.config.workerId, 'worker-wired')
  assert.equal(handle.config.workerType, 'unit-test-worker')
  assert.equal(handle.config.leaseTtlMs, 42_000)
  assert.equal(handle.config.heartbeatIntervalMs, 500)
  assert.equal(handle.config.pollBackoffMs, 7)
  assert.equal(handle.config.concurrency, 2)
  assert.equal(handle.config.environmentId, 'env-wired')
  assert.deepEqual(handle.config.capabilities, ['demo'])
  assert.equal(handle.config.protocolVersion, '2')
  assert.equal(handle.runtime.status, 'stopped')
  assert.equal(typeof handle.start, 'function')
  assert.equal(typeof handle.stop, 'function')
})

await test('createLocalWorkerRuntime: applies WORKER_ID and LEASE_TTL_MS env defaults', async () => {
  const previousWorkerId = process.env.WORKER_ID
  const previousTtl = process.env.LEASE_TTL_MS
  process.env.WORKER_ID = 'env-worker'
  process.env.LEASE_TTL_MS = '12345'
  try {
    const handle = await createLocalWorkerRuntime({
      client: createInMemorySqlClient(),
      executor: {
        async execute() {
          return { status: 'completed' }
        },
      },
      logger: silentLogger,
    })
    assert.equal(handle.config.workerId, 'env-worker')
    assert.equal(handle.config.leaseTtlMs, 12_345)
    assert.equal(handle.config.workerType, 'local-demo-worker')
  } finally {
    if (previousWorkerId === undefined) delete process.env.WORKER_ID
    else process.env.WORKER_ID = previousWorkerId
    if (previousTtl === undefined) delete process.env.LEASE_TTL_MS
    else process.env.LEASE_TTL_MS = previousTtl
  }
})

await test('end-to-end: a seeded work unit goes created → running → completed', async () => {
  const client = createInMemorySqlClient()
  const workUnitRepo = bundle.createSqlWorkUnitRepository(client)
  await workUnitRepo.create({ workUnitId: 'wu-demo-1', unitType: 'demo-echo' })
  assert.equal((await workUnitRepo.get('wu-demo-1')).status, 'created')

  const handle = await createLocalWorkerRuntime({
    client,
    workerId: 'e2e-worker',
    pollBackoffMs: 5,
    heartbeatIntervalMs: 200,
    leaseTtlMs: 60_000,
    executor: createDemoWorkExecutor({ delayMs: 1, logger: silentLogger }),
    logger: silentLogger,
  })

  await handle.start()
  assert.ok(
    await waitFor(async () => (await workUnitRepo.get('wu-demo-1')).status === 'completed'),
    'work unit must reach completed'
  )

  const completed = await workUnitRepo.get('wu-demo-1')
  assert.equal(completed.status, 'completed')
  assert.equal(completed.attemptCount, 1)
  assert.equal(completed.payload.executor, 'demo-echo')
  assert.ok(typeof completed.payload.executedAt === 'string')

  // The worker row is registered by the same run.
  const workerRepo = bundle.createSqlWorkerRepository(client)
  const worker = await workerRepo.get('e2e-worker')
  assert.ok(worker, 'the worker must be registered in the worker repository')

  await handle.stop({ drainTimeoutMs: 1000 })
  assert.equal(handle.runtime.status, 'stopped')
})

await test('runLocalWorker: wires then starts the runtime and returns the running handle', async () => {
  const handle = await runLocalWorker({
    client: createInMemorySqlClient(),
    workerId: 'run-local-worker',
    pollBackoffMs: 5,
    executor: createDemoWorkExecutor({ delayMs: 1, logger: silentLogger }),
    logger: silentLogger,
  })
  assert.equal(handle.runtime.status, 'running')
  await handle.stop({ drainTimeoutMs: 1000 })
  assert.equal(handle.runtime.status, 'stopped')
})

// ---------------------------------------------------------------------------
// 3. Bundle surface + stateless JS facade
// ---------------------------------------------------------------------------

await test('operational bundle exposes the worker-runtime surface', async () => {
  for (const name of [
    'WorkerRuntime',
    'createDemoWorkExecutor',
    'createLocalWorkerRuntime',
    'runLocalWorker',
    'createConsoleWorkerRuntimeLogger',
  ]) {
    assert.equal(typeof bundle[name], 'function', `bundle must export ${name}`)
  }
  // The C2-T1 loop class itself is part of the surface, alongside the C2-T2
  // launchers and helpers.
  assert.equal(typeof WorkerRuntime.prototype.start, 'function')
  assert.equal(typeof WorkerRuntime.prototype.stop, 'function')
})

await test('factory/lib/worker-runtime.mjs is a stateless facade over the bundle', async () => {
  const facade = await import('../lib/worker-runtime.mjs')
  assert.equal(facade.WorkerRuntime, bundle.WorkerRuntime)
  assert.equal(facade.createDemoWorkExecutor, bundle.createDemoWorkExecutor)
  assert.equal(facade.createLocalWorkerRuntime, bundle.createLocalWorkerRuntime)
  assert.equal(facade.runLocalWorker, bundle.runLocalWorker)
  assert.equal(facade.createConsoleWorkerRuntimeLogger, bundle.createConsoleWorkerRuntimeLogger)
})

await test('bundle metafile includes the worker-runtime entrypoint exactly once', async () => {
  const metafilePath = new URL('../dist/factory-operational/factory-operational.meta.json', import.meta.url)
  const metafile = JSON.parse(await readFile(metafilePath, 'utf8'))
  const inputs = Object.keys(metafile.inputs).filter((input) => input.endsWith('src/entrypoints/worker-runtime.ts'))
  assert.equal(inputs.length, 1, 'worker-runtime entrypoint must be bundled exactly once')
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
