/**
 * Conformance suite for the WorkUnit and Worker SQL adapters (Jalon C1-T1a).
 *
 * The SQL adapter sources are TypeScript with `.js` import specifiers (the
 * factory convention) and cannot be loaded by `node` directly, so they are
 * bundled in-memory with the already-required `esbuild` toolchain dependency
 * and imported from a data URL — no file is written, no generated bundle is
 * regenerated (matching the B3 work-environment / delivery conformance style).
 *
 * It drives the adapters against the offline in-memory `SqlClient` and asserts:
 *
 *   1. WorkUnit CRUD (create defaults, get, update patches, list) and the
 *      optimistic-locking `revision` compare-and-swap (`REVISION_CONFLICT`);
 *   2. WorkUnit valid / invalid lifecycle transitions and the shared error
 *      codes (`INVALID_STATE`, `INVALID_TRANSITION`, `NOT_FOUND`, ...);
 *   3. Worker CRUD, heartbeat recording and liveness / revision conflicts;
 *   4. Worker valid / invalid lifecycle transitions;
 *   5. tenant isolation (organization / workstream scoping).
 *
 * Usage: node factory/tests/test-conformance-workunit-worker.mjs
 * Exit code: 0 when every case passes, 1 otherwise.
 */
import assert from 'node:assert/strict'

import { build } from 'esbuild'

import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function test(name, fn) {
  try {
    await fn()
    passed++
    console.log(`\u2713 ${name}`)
  } catch (error) {
    failed++
    console.error(`\u2717 ${name}\n   ${error?.stack ?? error}`)
  }
}

/** Runs `fn` and returns the thrown machine code (or its message). */
async function thrownCode(fn) {
  try {
    await fn()
    return 'NO_ERROR'
  } catch (error) {
    return error?.code ?? error?.message ?? String(error)
  }
}

// ---------------------------------------------------------------------------
// SQL adapter sources, bundled in-memory (no generated bundle is touched)
// ---------------------------------------------------------------------------

const ADAPTER_ENTRY_POINTS = [
  '../src/adapters/persistence/sql/sql-work-unit-repository.ts',
  '../src/adapters/persistence/sql/sql-worker-repository.ts',
]

const adapterEntry = ADAPTER_ENTRY_POINTS.map((entryPoint) => `export * from ${JSON.stringify(entryPoint)}`).join('\n')
const compiled = await build({
  stdin: {
    contents: adapterEntry,
    resolveDir: import.meta.dirname,
    loader: 'ts',
    sourcefile: 'conformance-workunit-worker-adapters.ts',
  },
  bundle: true,
  format: 'esm',
  platform: 'node',
  target: ['node22'],
  packages: 'bundle',
  external: ['node:*'],
  write: false,
  logLevel: 'silent',
})
const sqlModule = await import(
  `data:text/javascript;base64,${Buffer.from(compiled.outputFiles[0].text, 'utf8').toString('base64')}`
)

// ---------------------------------------------------------------------------
// WorkUnit scenario suite
// ---------------------------------------------------------------------------

async function runWorkUnitSuite() {
  const client = createInMemorySqlClient()
  const units = new sqlModule.SqlWorkUnitRepository(client, { organizationId: 'org-a', workstreamId: 'ws-1' })
  const otherWorkstream = new sqlModule.SqlWorkUnitRepository(client, { organizationId: 'org-a', workstreamId: 'ws-2' })
  const otherOrganization = new sqlModule.SqlWorkUnitRepository(client, {
    organizationId: 'org-b',
    workstreamId: 'ws-1',
  })

  // Tenant scope is fixed at wiring time.
  assert.deepEqual(units.scope, { organizationId: 'org-a', workstreamId: 'ws-1' })

  // a. create applies the V7 defaults
  const created = await units.create({ workUnitId: 'unit-1', unitType: 'build' })
  assert.equal(created.status, 'created')
  assert.equal(created.revision, 1)
  assert.equal(created.priority, 0)
  assert.equal(created.notBefore, null)
  assert.equal(created.attemptCount, 0)
  assert.deepEqual(created.payload, {})

  // b. get returns the stored unit or null
  const fetched = await units.get('unit-1')
  assert.equal(fetched.workUnitId, 'unit-1')
  assert.equal(fetched.unitType, 'build')
  assert.equal(await units.get('unit-missing'), null)

  // c. duplicate and invalid creates throw the expected codes
  assert.equal(
    await thrownCode(() => units.create({ workUnitId: 'unit-1', unitType: 'build' })),
    'WORK_UNIT_ALREADY_EXISTS'
  )
  assert.equal(await thrownCode(() => units.create({ workUnitId: 'bad id!', unitType: 'build' })), 'INVALID_WORK_UNIT')
  assert.equal(
    await thrownCode(() => units.create({ workUnitId: 'unit-x', unitType: 'build', priority: 1.5 })),
    'INVALID_WORK_UNIT'
  )

  // d. scheduling fields are honoured at create time
  const scheduled = await units.create({
    workUnitId: 'unit-2',
    unitType: 'test',
    priority: 5,
    notBefore: '2030-01-01T00:00:00.000Z',
    payload: { lane: 'fast' },
  })
  assert.equal(scheduled.priority, 5)
  assert.equal(scheduled.notBefore, '2030-01-01T00:00:00.000Z')
  assert.deepEqual(scheduled.payload, { lane: 'fast' })

  // e. update patches mutable fields and bumps the revision
  const updated = await units.update('unit-1', { priority: 9, payload: { foo: 'bar' } }, 1)
  assert.equal(updated.revision, 2)
  assert.equal(updated.priority, 9)
  assert.deepEqual(updated.payload, { foo: 'bar' })
  assert.equal(fetched.revision, 1)

  // f. optimistic locking / not found
  assert.equal(await thrownCode(() => units.update('unit-1', { priority: 1 }, 1)), 'REVISION_CONFLICT')
  assert.equal(await thrownCode(() => units.update('unit-missing', { priority: 1 }, 1)), 'NOT_FOUND')
  assert.equal(await thrownCode(() => units.update('unit-1', { workUnitId: 'other' }, 2)), 'INVALID_WORK_UNIT')

  // g. valid lifecycle transitions
  const assigned = await units.transition('unit-1', 'assigned', 2)
  assert.equal(assigned.status, 'assigned')
  assert.equal(assigned.revision, 3)
  const running = await units.transition('unit-1', 'running', 3, { workerId: 'worker-1' })
  assert.equal(running.status, 'running')
  assert.deepEqual(running.payload, { foo: 'bar', workerId: 'worker-1' })
  const completed = await units.transition('unit-1', 'completed', 4)
  assert.equal(completed.status, 'completed')
  assert.equal(completed.revision, 5)

  // h. invalid transitions / states / revisions / missing
  assert.equal(await thrownCode(() => units.transition('unit-1', 'running', 5)), 'INVALID_TRANSITION')
  assert.equal(await thrownCode(() => units.transition('unit-2', 'completed', 1)), 'INVALID_TRANSITION')
  assert.equal(await thrownCode(() => units.transition('unit-2', 'bogus', 1)), 'INVALID_STATE')
  assert.equal(await thrownCode(() => units.transition('unit-2', 'assigned', 99)), 'REVISION_CONFLICT')
  assert.equal(await thrownCode(() => units.transition('unit-missing', 'assigned', 1)), 'NOT_FOUND')

  // i. list filters and ordering (descending priority)
  const all = await units.list()
  assert.deepEqual(
    all.map((unit) => unit.workUnitId),
    ['unit-1', 'unit-2']
  )
  assert.deepEqual(
    (await units.list({ status: 'created' })).map((unit) => unit.workUnitId),
    ['unit-2']
  )
  assert.deepEqual((await units.list({ status: ['completed', 'created'] })).map((unit) => unit.workUnitId).sort(), [
    'unit-1',
    'unit-2',
  ])
  assert.deepEqual(
    (await units.list({ priorityMin: 9 })).map((unit) => unit.workUnitId),
    ['unit-1']
  )
  assert.deepEqual(
    (await units.list({ limit: 1 })).map((unit) => unit.workUnitId),
    ['unit-1']
  )

  // j. tenant isolation: another workstream / organization never sees the unit
  assert.equal(await otherWorkstream.get('unit-1'), null)
  assert.equal(await otherOrganization.get('unit-1'), null)
  assert.deepEqual(await otherWorkstream.list(), [])
  assert.deepEqual(await otherOrganization.list(), [])
}

// ---------------------------------------------------------------------------
// Worker scenario suite
// ---------------------------------------------------------------------------

async function runWorkerSuite() {
  const client = createInMemorySqlClient()
  const workers = new sqlModule.SqlWorkerRepository(client, { organizationId: 'org-a' })
  const otherOrganization = new sqlModule.SqlWorkerRepository(client, { organizationId: 'org-b' })

  assert.deepEqual(workers.scope, { organizationId: 'org-a' })

  // a. create applies the V7 defaults
  const created = await workers.create({ workerId: 'worker-1', workerType: 'npm' })
  assert.equal(created.status, 'offline')
  assert.equal(created.revision, 1)
  assert.equal(created.lastHeartbeatAt, null)
  assert.equal(created.protocolVersion, null)
  assert.deepEqual(created.capabilities, [])
  assert.deepEqual(created.payload, {})

  // b. get returns the stored worker or null
  const fetched = await workers.get('worker-1')
  assert.equal(fetched.workerId, 'worker-1')
  assert.equal(fetched.workerType, 'npm')
  assert.equal(await workers.get('worker-missing'), null)

  // c. duplicate and invalid creates throw the expected codes
  assert.equal(
    await thrownCode(() => workers.create({ workerId: 'worker-1', workerType: 'npm' })),
    'WORKER_ALREADY_EXISTS'
  )
  assert.equal(await thrownCode(() => workers.create({ workerId: 'bad id!', workerType: 'npm' })), 'INVALID_WORKER')
  assert.equal(
    await thrownCode(() => workers.create({ workerId: 'worker-x', workerType: 'npm', capabilities: 'nodejs' })),
    'INVALID_WORKER'
  )

  // d. declared capabilities / protocol version round-trip
  const second = await workers.create({
    workerId: 'worker-2',
    workerType: 'docker',
    capabilities: ['docker', 'nodejs'],
    protocolVersion: '1',
    payload: { zone: 'eu' },
  })
  assert.deepEqual(second.capabilities, ['docker', 'nodejs'])
  assert.equal(second.protocolVersion, '1')
  assert.deepEqual(second.payload, { zone: 'eu' })

  // e. heartbeat records liveness and bumps the revision
  const beat = await workers.heartbeat('worker-1', '2025-01-01T00:00:00.000Z')
  assert.equal(beat.lastHeartbeatAt, '2025-01-01T00:00:00.000Z')
  assert.equal(beat.revision, 2)
  const beatWithRevision = await workers.heartbeat('worker-1', '2025-01-01T00:00:01.000Z', 2)
  assert.equal(beatWithRevision.lastHeartbeatAt, '2025-01-01T00:00:01.000Z')
  assert.equal(beatWithRevision.revision, 3)

  // f. heartbeat errors: stale revision, malformed instant, missing worker
  assert.equal(
    await thrownCode(() => workers.heartbeat('worker-1', '2025-01-01T00:00:02.000Z', 2)),
    'REVISION_CONFLICT'
  )
  assert.equal(await thrownCode(() => workers.heartbeat('worker-1', 'not-a-time')), 'INVALID_WORKER')
  assert.equal(await thrownCode(() => workers.heartbeat('worker-missing', '2025-01-01T00:00:00.000Z')), 'NOT_FOUND')

  // g. update patches mutable fields and bumps the revision
  const updated = await workers.update('worker-1', { capabilities: ['nodejs'], payload: { zone: 'us' } }, 3)
  assert.equal(updated.revision, 4)
  assert.deepEqual(updated.capabilities, ['nodejs'])
  assert.deepEqual(updated.payload, { zone: 'us' })
  assert.equal(await thrownCode(() => workers.update('worker-1', { capabilities: [] }, 3)), 'REVISION_CONFLICT')
  assert.equal(await thrownCode(() => workers.update('worker-missing', { capabilities: [] }, 1)), 'NOT_FOUND')

  // h. valid lifecycle transitions offline -> idle -> busy -> idle
  const idle = await workers.transition('worker-1', 'idle', 4)
  assert.equal(idle.status, 'idle')
  assert.equal(idle.revision, 5)
  const busy = await workers.transition('worker-1', 'busy', 5, { workUnitId: 'unit-1' })
  assert.equal(busy.status, 'busy')
  assert.deepEqual(busy.payload, { zone: 'us', workUnitId: 'unit-1' })
  const idleAgain = await workers.transition('worker-1', 'idle', 6)
  assert.equal(idleAgain.status, 'idle')

  // i. invalid transitions / states / revisions / missing while still offline
  assert.equal(await thrownCode(() => workers.transition('worker-2', 'busy', 1)), 'INVALID_TRANSITION')
  assert.equal(await thrownCode(() => workers.transition('worker-2', 'bogus', 1)), 'INVALID_STATE')
  assert.equal(await thrownCode(() => workers.transition('worker-2', 'idle', 99)), 'REVISION_CONFLICT')
  assert.equal(await thrownCode(() => workers.transition('worker-missing', 'idle', 1)), 'NOT_FOUND')

  // j. maintenance round-trip
  const maintenance = await workers.transition('worker-2', 'maintenance', 1)
  assert.equal(maintenance.status, 'maintenance')
  const backToIdle = await workers.transition('worker-2', 'idle', 2)
  assert.equal(backToIdle.status, 'idle')

  // k. list filters
  assert.deepEqual(
    (await workers.list()).map((worker) => worker.workerId),
    ['worker-1', 'worker-2']
  )
  assert.deepEqual(
    (await workers.list({ status: 'idle' })).map((worker) => worker.workerId),
    ['worker-1', 'worker-2']
  )
  assert.deepEqual(
    (await workers.list({ workerType: 'docker' })).map((worker) => worker.workerId),
    ['worker-2']
  )
  assert.deepEqual(await workers.list({ status: ['busy', 'maintenance'] }), [])

  // l. tenant isolation
  assert.equal(await otherOrganization.get('worker-1'), null)
  assert.deepEqual(await otherOrganization.list(), [])
}

// ---------------------------------------------------------------------------
// Run
// ---------------------------------------------------------------------------

await test('work-unit: CRUD, state machine, optimistic locking and tenant isolation', runWorkUnitSuite)
await test('worker: CRUD, heartbeat, state machine, optimistic locking and tenant isolation', runWorkerSuite)

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
