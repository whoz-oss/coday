/**
 * Lease protocol tests (Jalon C1-T1b).
 *
 * Exercises the pure lease domain and the `SqlLeaseRepository` adapter against
 * the shared in-memory `SqlClient`, offline and Docker-free:
 *
 *   1. `acquire`  — `SELECT … FOR UPDATE SKIP LOCKED` eligibility scan
 *      (priority DESC, then created_at ASC, honoring `not_before`), monotone
 *      fencing token from `work_unit_lease_fencing_seq`, work-unit transition
 *      to `running` and `attempt_count` increment.
 *   2. `renew`    — heartbeat, deadline extension and the fencing rule.
 *   3. `release`  — terminal transition, work-unit result status and fencing.
 *   4. `expire`   — expiry sweep, `expiry_reason`, work-unit re-queue.
 *   5. reads      — `findByLeaseId` / `findActiveLeaseByWorkUnit`.
 *
 * ---------------------------------------------------------------------------
 * Running the strict-concurrency test against PostgreSQL
 * ---------------------------------------------------------------------------
 * `SELECT … FOR UPDATE SKIP LOCKED` and the monotone `nextval` sequence are
 * database properties: the in-memory client is single-threaded and cannot
 * pretend to exercise them. When a database is configured the suite therefore
 * adds a strict concurrency scenario (parallel `acquire` calls must never hand
 * the same work unit or the same fencing token to two workers):
 *
 *   # Start PostgreSQL + apply the V1..V7 migrations (idempotent).
 *   docker compose -f factory/infra/docker-compose.yml up -d
 *   docker compose -f factory/infra/docker-compose.yml logs -f flyway
 *
 *   # Point the suite at it (DATABASE_URL wins; otherwise PG* variables).
 *   export DATABASE_URL="postgres://factory:factory_dev_pass@localhost:5432/coday_factory"
 *   # or: export PGHOST=localhost PGPORT=5432 PGDATABASE=coday_factory \
 *   #            PGUSER=factory PGPASSWORD=factory_dev_pass
 *   node factory/tests/test-lease-protocol.mjs
 *
 * The scenario also needs the `pg` driver, which the Factory runtime
 * deliberately does not bundle: install it in the operator's environment
 * (`pnpm add -w pg`) if you want the live run. Without a configured database, or
 * without the driver, the scenario is skipped with a message and the suite stays
 * green and offline.
 *
 * Usage : node factory/tests/test-lease-protocol.mjs
 * Exit code : 0 = every case passed, 1 = at least one failure.
 */

import assert from 'node:assert/strict'
import { register } from 'node:module'

// Register the `.js` → `.ts` resolver before importing the TypeScript adapter
// directly (the runtime bundle does not yet re-export the lease adapter).
register(new URL('./support/node-ts-resolve-hook.mjs', import.meta.url))

const { createInMemorySqlClient } = await import('./support/in-memory-sql-client.mjs')
const { SqlLeaseRepository } = await import('../src/adapters/persistence/sql/sql-lease-repository.ts')
const { LEASE_ERROR_CODES, LEASE_EXPIRY_REASONS } = await import('../src/domain/lease/lease.ts')

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

const ORG = 'default'
const WS = 'default'
const T0 = '2026-01-01T00:00:00.000Z'

/** ISO instant `offsetMs` after the fixed base instant `T0`. */
const T = (offsetMs) => new Date(Date.parse(T0) + offsetMs).toISOString()

/** Asserts a rejection carries the expected lease error code. */
const leaseError = (code) => (error) => {
  assert.equal(error?.code, code, `expected ${code}, got ${error?.code ?? error}`)
  return true
}

/** Seeds one `work_units` row with the V7 scheduling columns. */
function seedWorkUnit(
  client,
  { workUnitId, status = 'created', priority = 0, notBefore = null, attemptCount = 0, createdAt = T0 }
) {
  return client.query(
    `INSERT INTO work_units
       (organization_id, workstream_id, work_unit_id, unit_type, status, revision,
        priority, not_before, attempt_count, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
    [ORG, WS, workUnitId, 'test-unit', status, 1, priority, notBefore, attemptCount, createdAt, createdAt]
  )
}

async function readWorkUnit(client, workUnitId) {
  const { rows } = await client.query(
    `SELECT * FROM work_units WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3`,
    [ORG, WS, workUnitId]
  )
  return rows[0] ?? null
}

// ---------------------------------------------------------------------------
// A. pure domain rules
// ---------------------------------------------------------------------------

await test('pure domain: fencing rule and expiry predicate are consistent', async () => {
  const { isFencingTokenCurrent, validateFencingToken, assertFencingToken, computeLeaseExpiresAt, isLeaseActive } =
    await import('../src/domain/lease/lease.ts')

  assert.equal(isFencingTokenCurrent(7, 7), true)
  assert.equal(isFencingTokenCurrent(7, 6), false)
  assert.equal(validateFencingToken(7, 8), false, 'an unknown greater token is not current')
  assert.throws(() => assertFencingToken(7, 6), leaseError(LEASE_ERROR_CODES.LEASE_FENCED))
  assert.equal(computeLeaseExpiresAt(T0, 1_000), T(1_000))

  const lease = { status: 'active', leaseExpiresAt: T(1_000) }
  assert.equal(isLeaseActive(lease, T0), true)
  assert.equal(isLeaseActive(lease, T(1_000)), false, 'the deadline is inclusive')
})

// ---------------------------------------------------------------------------
// B. acquire
// ---------------------------------------------------------------------------

await test('acquire picks the highest-priority eligible work unit and marks it running', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-low', priority: 0, createdAt: T(1_000) })
  await seedWorkUnit(client, { workUnitId: 'wu-high', priority: 5, createdAt: T(2_000) })
  await seedWorkUnit(client, { workUnitId: 'wu-running', status: 'running', priority: 9 })
  await seedWorkUnit(client, { workUnitId: 'wu-future', priority: 9, notBefore: T(60_000) })
  const repository = new SqlLeaseRepository(client)

  const result = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  assert.ok(result, 'a lease must be acquired')
  assert.equal(result.workUnitId, 'wu-high')
  assert.equal(result.lease.status, 'active')
  assert.equal(result.lease.fencingToken, 1, 'the first token comes from the sequence')
  assert.equal(result.lease.workerId, 'worker-1')
  assert.equal(result.lease.acquiredAt, T0)
  assert.equal(result.lease.heartbeatAt, T0)
  assert.equal(result.lease.leaseExpiresAt, T(30_000))
  assert.equal(result.lease.releasedAt, null)
  assert.equal(result.lease.expiryReason, null)

  const unit = await readWorkUnit(client, 'wu-high')
  assert.equal(unit.status, 'running')
  assert.equal(unit.attempt_count, 1)
  assert.equal(unit.revision, 2)
})

await test('acquire skips locked units and hands distinct tokens to successive workers', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-a', priority: 5, createdAt: T(1_000) })
  await seedWorkUnit(client, { workUnitId: 'wu-b', priority: 0, createdAt: T(2_000) })
  const repository = new SqlLeaseRepository(client)

  const first = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })
  const second = await repository.acquire({ workerId: 'worker-2', ttlMs: 30_000, now: new Date(T(1_000)) })

  assert.equal(first.workUnitId, 'wu-a')
  assert.equal(second.workUnitId, 'wu-b')
  assert.equal(second.lease.fencingToken, first.lease.fencingToken + 1, 'tokens are strictly increasing')
  assert.notEqual(second.lease.leaseId, first.lease.leaseId)
})

await test('acquire returns null when no work unit is eligible', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-done', status: 'completed' })
  await seedWorkUnit(client, { workUnitId: 'wu-cancelled', status: 'cancelled' })
  await seedWorkUnit(client, { workUnitId: 'wu-deferred', status: 'created', notBefore: T(60_000) })
  const repository = new SqlLeaseRepository(client)

  assert.equal(await repository.acquire({ workerId: 'worker-1', ttlMs: 1_000, now: new Date(T0) }), null)
})

await test('acquire re-queues failed work units and grows their attempt count', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-failed', status: 'failed', attemptCount: 3 })
  const repository = new SqlLeaseRepository(client)

  const result = await repository.acquire({ workerId: 'worker-1', ttlMs: 1_000, now: new Date(T0) })

  assert.equal(result.workUnitId, 'wu-failed')
  assert.equal((await readWorkUnit(client, 'wu-failed')).attempt_count, 4)
})

// ---------------------------------------------------------------------------
// C. renew (heartbeat) & fencing
// ---------------------------------------------------------------------------

await test('renew extends the deadline and records a heartbeat', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  const renewed = await repository.renew({
    workUnitId: acquired.workUnitId,
    leaseId: acquired.lease.leaseId,
    fencingToken: acquired.lease.fencingToken,
    ttlMs: 60_000,
    now: new Date(T(5_000)),
  })

  assert.equal(renewed.status, 'active')
  assert.equal(renewed.heartbeatAt, T(5_000))
  assert.equal(renewed.leaseExpiresAt, T(65_000))
  assert.equal(renewed.acquiredAt, T0)
})

await test('renew rejects a stale or mismatched fencing token with LEASE_FENCED', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  await assert.rejects(
    () =>
      repository.renew({
        workUnitId: acquired.workUnitId,
        leaseId: acquired.lease.leaseId,
        fencingToken: acquired.lease.fencingToken - 1,
        ttlMs: 30_000,
        now: new Date(T(1_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_FENCED)
  )
  await assert.rejects(
    () =>
      repository.renew({
        workUnitId: acquired.workUnitId,
        leaseId: acquired.lease.leaseId,
        fencingToken: acquired.lease.fencingToken + 1,
        ttlMs: 30_000,
        now: new Date(T(1_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_FENCED)
  )
  await assert.rejects(
    () =>
      repository.renew({
        workUnitId: 'wu-missing',
        leaseId: 'lease-missing',
        fencingToken: 1,
        ttlMs: 30_000,
        now: new Date(T(1_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND)
  )
})

await test('renew rejects a lease past its deadline with LEASE_EXPIRED', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 10_000, now: new Date(T0) })

  await assert.rejects(
    () =>
      repository.renew({
        workUnitId: acquired.workUnitId,
        leaseId: acquired.lease.leaseId,
        fencingToken: acquired.lease.fencingToken,
        ttlMs: 10_000,
        now: new Date(T(20_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_EXPIRED)
  )
})

// ---------------------------------------------------------------------------
// D. release
// ---------------------------------------------------------------------------

await test('release marks the lease released and completes the work unit', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  const released = await repository.release({
    workUnitId: acquired.workUnitId,
    leaseId: acquired.lease.leaseId,
    fencingToken: acquired.lease.fencingToken,
    now: new Date(T(1_000)),
  })

  assert.equal(released.status, 'released')
  assert.equal(released.releasedAt, T(1_000))

  const unit = await readWorkUnit(client, 'wu-1')
  assert.equal(unit.status, 'completed')
  assert.equal(unit.revision, 3, 'acquire then release each bump the optimistic revision')
  assert.equal(await repository.findActiveLeaseByWorkUnit(ORG, WS, 'wu-1'), null)
})

await test('release honours an explicit result status', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  await repository.release({
    workUnitId: acquired.workUnitId,
    leaseId: acquired.lease.leaseId,
    fencingToken: acquired.lease.fencingToken,
    resultStatus: 'failed',
    now: new Date(T(1_000)),
  })

  assert.equal((await readWorkUnit(client, 'wu-1')).status, 'failed')
})

await test('release enforces the fencing rule and the active-state precondition', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 30_000, now: new Date(T0) })

  await assert.rejects(
    () =>
      repository.release({
        workUnitId: acquired.workUnitId,
        leaseId: acquired.lease.leaseId,
        fencingToken: acquired.lease.fencingToken - 1,
        now: new Date(T(1_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_FENCED)
  )
  await assert.rejects(
    () =>
      repository.release({
        workUnitId: 'wu-missing',
        leaseId: 'lease-missing',
        fencingToken: 1,
        now: new Date(T(1_000)),
      }),
    leaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND)
  )

  await repository.release({
    workUnitId: acquired.workUnitId,
    leaseId: acquired.lease.leaseId,
    fencingToken: acquired.lease.fencingToken,
    now: new Date(T(1_000)),
  })
  await assert.rejects(
    () =>
      repository.release({
        workUnitId: acquired.workUnitId,
        leaseId: acquired.lease.leaseId,
        fencingToken: acquired.lease.fencingToken,
        now: new Date(T(2_000)),
      }),
    leaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE)
  )
})

// ---------------------------------------------------------------------------
// E. expire (sweep)
// ---------------------------------------------------------------------------

await test('expire retires active leases past their deadline and re-queues the work unit', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({ workerId: 'worker-1', ttlMs: 10_000, now: new Date(T0) })

  const swept = await repository.expire({ now: new Date(T(20_000)) })

  assert.equal(swept.length, 1)
  assert.equal(swept[0].leaseId, acquired.lease.leaseId)
  assert.equal(swept[0].status, 'expired')
  assert.equal(swept[0].releasedAt, T(20_000))
  assert.equal(swept[0].expiryReason, LEASE_EXPIRY_REASONS.HEARTBEAT_TIMEOUT)

  const unit = await readWorkUnit(client, 'wu-1')
  assert.equal(unit.status, 'created', 'the expired work unit is re-queued')
  assert.equal(unit.revision, 3, 'acquire then expire each bump the optimistic revision')

  assert.equal(await repository.findActiveLeaseByWorkUnit(ORG, WS, 'wu-1'), null)
  assert.deepEqual(await repository.expire({ now: new Date(T(30_000)) }), [], 'a retired lease is swept only once')
})

await test('expire stamps the caller reason and re-acquires with a greater token', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const first = await repository.acquire({ workerId: 'worker-1', ttlMs: 10_000, now: new Date(T0) })

  const swept = await repository.expire({ expiryReason: 'worker_lost', now: new Date(T(20_000)) })
  assert.equal(swept[0].expiryReason, 'worker_lost')

  const second = await repository.acquire({ workerId: 'worker-2', ttlMs: 10_000, now: new Date(T(20_000)) })
  assert.equal(second.workUnitId, 'wu-1')
  assert.ok(second.lease.fencingToken > first.lease.fencingToken, 'the re-acquisition gets a strictly greater token')
  assert.equal((await readWorkUnit(client, 'wu-1')).attempt_count, 2)
})

// ---------------------------------------------------------------------------
// F. reads
// ---------------------------------------------------------------------------

await test('findByLeaseId and findActiveLeaseByWorkUnit expose the durable lease', async () => {
  const client = createInMemorySqlClient()
  await seedWorkUnit(client, { workUnitId: 'wu-1' })
  const repository = new SqlLeaseRepository(client)
  const acquired = await repository.acquire({
    workerId: 'worker-1',
    environmentId: 'env-1',
    ttlMs: 30_000,
    now: new Date(T0),
  })

  const found = await repository.findByLeaseId(ORG, WS, acquired.workUnitId, acquired.lease.leaseId)
  assert.equal(found.leaseId, acquired.lease.leaseId)
  assert.equal(found.environmentId, 'env-1')
  assert.equal(found.fencingToken, acquired.lease.fencingToken)

  const active = await repository.findActiveLeaseByWorkUnit(ORG, WS, acquired.workUnitId)
  assert.equal(active.leaseId, acquired.lease.leaseId)

  assert.equal(await repository.findByLeaseId(ORG, WS, acquired.workUnitId, 'lease-missing'), null)
  assert.equal(await repository.findActiveLeaseByWorkUnit(ORG, WS, 'wu-missing'), null)
})

// ---------------------------------------------------------------------------
// G. strict concurrency against PostgreSQL (optional, skipped offline)
// ---------------------------------------------------------------------------

async function loadPgPool() {
  if (!process.env.DATABASE_URL && !process.env.PGHOST && !process.env.PGPORT) return null
  let loaded
  try {
    loaded = await import('pg')
  } catch {
    return null
  }
  const pgModule = loaded.default ?? loaded
  if (typeof pgModule?.Pool !== 'function') return null
  const config = process.env.DATABASE_URL
    ? { connectionString: process.env.DATABASE_URL }
    : {
        host: process.env.PGHOST,
        port: Number(process.env.PGPORT ?? 5432),
        database: process.env.PGDATABASE ?? 'coday_factory',
        user: process.env.PGUSER ?? 'factory',
        password: process.env.PGPASSWORD ?? 'factory_dev_pass',
      }
  return new pgModule.Pool({ ...config, max: 8 })
}

await test('strict concurrency: parallel acquires never share a work unit or token (PostgreSQL)', async () => {
  const scope = 'lease-concurrency'
  const ws = 'default'
  const workers = 8

  let pool
  try {
    pool = await loadPgPool()
    if (pool) await pool.query('SELECT 1')
  } catch (error) {
    console.log(`\u21b7 skipped strict-concurrency scenario: PostgreSQL unreachable (${error?.message ?? error})`)
    if (pool) await pool.end().catch(() => undefined)
    return
  }
  if (!pool) {
    console.log(
      '\u21b7 skipped strict-concurrency scenario: no DATABASE_URL/PGHOST and/or pg driver. ' +
        'See the header of this file for the docker compose recipe.'
    )
    return
  }

  const connections = []
  try {
    await pool.query(`DELETE FROM work_unit_leases WHERE organization_id = $1 AND workstream_id = $2`, [scope, ws])
    await pool.query(`DELETE FROM work_units WHERE organization_id = $1 AND workstream_id = $2`, [scope, ws])
    await pool.query(`INSERT INTO organizations (organization_id, name) VALUES ($1, $2) ON CONFLICT DO NOTHING`, [
      scope,
      scope,
    ])
    await pool.query(
      `INSERT INTO workstreams (organization_id, workstream_id, name) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`,
      [scope, ws, ws]
    )
    for (let index = 0; index < workers; index++) {
      await pool.query(
        `INSERT INTO work_units
           (organization_id, workstream_id, work_unit_id, unit_type, status, revision, priority, attempt_count)
         VALUES ($1, $2, $3, $4, 'created', 1, $5, 0)`,
        [scope, ws, `wu-concurrent-${index}`, 'test-unit', index]
      )
    }

    // One dedicated connection per worker: `FOR UPDATE SKIP LOCKED` and the
    // `nextval` sequence are only exercised when the acquires truly overlap.
    for (let index = 0; index < workers; index++) connections.push(await pool.connect())
    const repositories = connections.map(
      (connection) => new SqlLeaseRepository(connection, { organizationId: scope, workstreamId: ws })
    )

    const results = await Promise.all(
      repositories.map((repository, index) => repository.acquire({ workerId: `worker-${index}`, ttlMs: 30_000 }))
    )

    const acquired = results.filter((result) => result !== null)
    assert.equal(acquired.length, workers, 'every worker must win exactly one distinct work unit')
    const workUnitIds = new Set(acquired.map((result) => result.workUnitId))
    const tokens = new Set(acquired.map((result) => result.lease.fencingToken))
    assert.equal(workUnitIds.size, workers, 'no work unit may be handed to two workers')
    assert.equal(tokens.size, workers, 'no fencing token may be handed to two workers')
  } finally {
    for (const connection of connections) connection.release()
    await pool.query(`DELETE FROM work_unit_leases WHERE organization_id = $1 AND workstream_id = $2`, [scope, ws])
    await pool.query(`DELETE FROM work_units WHERE organization_id = $1 AND workstream_id = $2`, [scope, ws])
    await pool.query(`DELETE FROM workstreams WHERE organization_id = $1 AND workstream_id = $2`, [scope, ws])
    await pool.query(`DELETE FROM organizations WHERE organization_id = $1`, [scope])
    await pool.end()
  }
})

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
