/**
 * SQL artifact metadata repository & composed PostgreSQL ArtifactStore (B5-T1).
 *
 * Offline, no framework and no database: exits 0 when every case passes, 1
 * otherwise.
 *
 * Usage: node factory/tests/test-artifact-metadata-postgres.mjs
 *
 * The binary payloads are kept in an in-memory fake object client (same shape
 * as `FakeS3ObjectClient` in `test-artifact-store.mjs`) and the authoritative
 * metadata rows live in `createInMemorySqlClient()`, so the whole run needs
 * neither MinIO nor PostgreSQL.
 *
 * Covers:
 *   1.  `putArtifact` -> `getArtifactMetadata` -> `openArtifact` round-trip.
 *   2.  Upload-then-commit failure: PG commit error leaves an orphaned blob and
 *       no authoritative row.
 *   3.  Default retention (90 days / `ARTIFACT_RETENTION_DAYS`) and the
 *       active-retention purge no-op.
 *   4.  Retention expiry unlocks `purgeArtifact`, recording the purge in PG.
 *   5.  Legal hold blocks deletion / purge; releasing it unlocks purge.
 *   6.  Governance parity with the in-memory adapter.
 */

import assert from 'node:assert/strict'

import {
  MemoryArtifactStore,
  PostgresArtifactStore,
  SqlArtifactMetadataRepository,
  computeArtifactHash,
  createPostgresArtifactStore,
  createSqlArtifactMetadataRepository,
} from '../runtime/factory-operational.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

const encoder = new TextEncoder()
const DAY = 24 * 60 * 60 * 1000

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

let passed = 0
let failed = 0

async function scenario(name, fn) {
  try {
    await fn()
    passed++
    console.log(`✓ ${name}`)
  } catch (error) {
    failed++
    console.log(`✗ ${name}`)
    console.log(`  ${error?.stack ?? error}`)
  }
}

async function readAll(stream) {
  const chunks = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks)
}

// ---------------------------------------------------------------------------
// Offline object-storage fake
// ---------------------------------------------------------------------------

class FakeS3ObjectClient {
  constructor() {
    this.objects = new Map()
  }

  async putObject(key, body) {
    this.objects.set(key, Uint8Array.from(body))
  }

  async copyObject(sourceKey, destinationKey) {
    const source = this.objects.get(sourceKey)
    if (!source) throw new Error(`missing source ${sourceKey}`)
    this.objects.set(destinationKey, Uint8Array.from(source))
  }

  async getObject(key) {
    const body = this.objects.get(key)
    if (!body) return null
    const objects = this.objects
    return {
      stream: (async function* () {
        for (let offset = 0; offset < body.byteLength; offset += 3) {
          yield objects.get(key).subarray(offset, Math.min(offset + 3, body.byteLength))
        }
      })(),
    }
  }

  async headObject(key) {
    return this.objects.has(key)
  }

  async deleteObject(key) {
    return this.objects.delete(key)
  }

  async listObjectKeys(prefix) {
    return [...this.objects.keys()].filter((key) => key.startsWith(prefix))
  }
}

/** An in-memory SQL client whose `INSERT INTO artifacts` always fails. */
function createFailingInsertClient() {
  const inner = createInMemorySqlClient()
  return {
    inner,
    async query(text, params = []) {
      if (/INSERT\s+INTO\s+artifacts/i.test(text)) throw new Error('SIMULATED_PG_FAILURE')
      return inner.query(text, params)
    },
  }
}

function createStore(options = {}) {
  const client = options.client ?? new FakeS3ObjectClient()
  const sqlClient = options.sqlClient ?? createInMemorySqlClient()
  const repository = options.repository ?? createSqlArtifactMetadataRepository(sqlClient)
  const store = createPostgresArtifactStore({
    client,
    repository,
    ...(options.now !== undefined ? { now: options.now } : {}),
    ...(options.env !== undefined ? { env: options.env } : {}),
  })
  return { client, sqlClient, repository, store }
}

// ---------------------------------------------------------------------------
// 1. Public surface
// ---------------------------------------------------------------------------

await scenario('exposes the repository and store classes and factories', () => {
  assert.equal(typeof SqlArtifactMetadataRepository, 'function')
  assert.equal(typeof createSqlArtifactMetadataRepository, 'function')
  assert.equal(typeof PostgresArtifactStore, 'function')
  assert.equal(typeof createPostgresArtifactStore, 'function')
})

// ---------------------------------------------------------------------------
// 2. Round-trip
// ---------------------------------------------------------------------------

await scenario('putArtifact -> getArtifactMetadata -> openArtifact round-trip', async () => {
  const { client, store } = createStore()
  const payload = encoder.encode('postgres-artifact')
  const metadata = await store.putArtifact({
    owner: 'namespace-a',
    contentType: 'text/plain',
    data: payload,
    retentionDays: 30,
  })

  assert.equal(metadata.owner, 'namespace-a')
  assert.equal(metadata.contentType, 'text/plain')
  assert.equal(metadata.size, payload.byteLength)
  assert.equal(metadata.hash, computeArtifactHash(payload))
  assert.equal(metadata.availabilityStatus, 'available')
  assert.equal(metadata.retentionStatus, 'active')
  assert.equal(metadata.legalHold, false)
  assert.equal(metadata.retentionDays, 30)
  assert.ok(metadata.retentionUntil)

  const digest = metadata.hash.slice('sha256:'.length)
  assert.deepEqual([...client.objects.keys()].sort(), [`objects/${digest}`])
  assert.equal(client.objects.has(`uploads/${metadata.id}.part`), false)

  const found = await store.getArtifactMetadata(metadata.id)
  assert.deepEqual(found, metadata)
  assert.equal(await store.getArtifactMetadata('unknown-id'), null)

  const opened = await store.openArtifact(metadata.id)
  assert.ok(opened)
  assert.deepEqual(await readAll(opened.stream), Buffer.from(payload))
  assert.equal(opened.metadata.id, metadata.id)
  assert.equal(await store.openArtifact('unknown-id'), null)
})

await scenario('structured owner namespaces the row (namespace/workflow)', async () => {
  const { sqlClient, store } = createStore()
  const metadata = await store.putArtifact({
    owner: 'team/flow',
    contentType: 'application/octet-stream',
    data: encoder.encode('scoped'),
    retentionDays: 1,
  })
  const { rows } = await sqlClient.query(
    'SELECT organization_id, workstream_id, namespace_id, workflow_id FROM artifacts WHERE artifact_id = $1',
    [metadata.id]
  )
  assert.equal(rows.length, 1)
  assert.equal(rows[0].organization_id, 'default')
  assert.equal(rows[0].workstream_id, 'default')
  assert.equal(rows[0].namespace_id, 'team')
  assert.equal(rows[0].workflow_id, 'flow')
  assert.equal((await store.getArtifactMetadata(metadata.id)).owner, 'team/flow')
})

// ---------------------------------------------------------------------------
// 2. Upload-then-commit failure
// ---------------------------------------------------------------------------

await scenario('upload-then-commit failure leaves an orphan and no authoritative row', async () => {
  const client = new FakeS3ObjectClient()
  const failing = createFailingInsertClient()
  const store = createPostgresArtifactStore({ client, sqlClient: failing })

  let thrown
  try {
    await store.putArtifact({
      owner: 'namespace-b',
      contentType: 'application/octet-stream',
      data: encoder.encode('orphan-me'),
      retentionDays: 10,
    })
  } catch (error) {
    thrown = error
  }
  assert.ok(thrown, 'putArtifact must reject when the PG commit fails')
  assert.match(String(thrown.message), /SIMULATED_PG_FAILURE/)

  // The staging blob was uploaded and never cleaned up: it is reclaimable.
  const orphanUploads = [...client.objects.keys()].filter((key) => key.startsWith('uploads/'))
  assert.equal(orphanUploads.length, 1)
  assert.match(orphanUploads[0], /^uploads\/.+\.part$/)

  // No authoritative row was committed.
  const { rows } = await failing.inner.query('SELECT * FROM artifacts')
  assert.equal(rows.length, 0)

  const reclaimed = await store.collectOrphanedUploads()
  assert.deepEqual(reclaimed, orphanUploads)
  assert.equal(
    [...client.objects.keys()].some((key) => key.startsWith('uploads/')),
    false
  )
})

// ---------------------------------------------------------------------------
// 3. Default retention
// ---------------------------------------------------------------------------

await scenario('default retention of 90 days is applied when omitted', async () => {
  const { store } = createStore({ env: {} })
  const metadata = await store.putArtifact({
    owner: 'namespace-c',
    contentType: 'text/plain',
    data: encoder.encode('retained-by-default'),
  })
  assert.equal(metadata.retentionDays, 90)
  assert.equal(metadata.retentionStatus, 'active')
  assert.equal(await store.purgeArtifact(metadata.id, 'too early'), false)
  assert.equal(await store.deleteArtifact(metadata.id, 'too early'), false)
  const unchanged = await store.getArtifactMetadata(metadata.id)
  assert.equal(unchanged.availabilityStatus, 'available')
})

await scenario('ARTIFACT_RETENTION_DAYS configures the default, params win', async () => {
  const { store } = createStore({ env: { ARTIFACT_RETENTION_DAYS: '7' } })
  const fromEnv = await store.putArtifact({
    owner: 'namespace-c',
    contentType: 'text/plain',
    data: encoder.encode('env-retention'),
  })
  assert.equal(fromEnv.retentionDays, 7)

  const explicit = await store.putArtifact({
    owner: 'namespace-c',
    contentType: 'text/plain',
    data: encoder.encode('explicit-retention'),
    retentionDays: 2,
  })
  assert.equal(explicit.retentionDays, 2)
})

// ---------------------------------------------------------------------------
// 4. Retention expiry & purge
// ---------------------------------------------------------------------------

await scenario('expired retention allows purgeArtifact and records the purge in PG', async () => {
  let clock = new Date('2026-01-01T00:00:00.000Z')
  const { sqlClient, store } = createStore({ now: () => clock })
  const metadata = await store.putArtifact({
    owner: 'namespace-d',
    contentType: 'text/plain',
    data: encoder.encode('expiring'),
    retentionDays: 30,
  })
  assert.equal(metadata.retentionStatus, 'active')
  assert.equal(await store.purgeArtifact(metadata.id), false)

  // The stored column stays 'active'; the read recomputes from the window.
  const stale = await sqlClient.query('SELECT retention_status FROM artifacts WHERE artifact_id = $1', [metadata.id])
  assert.equal(stale.rows[0].retention_status, 'active')

  clock = new Date(clock.getTime() + 31 * DAY)
  const refreshed = await store.getArtifactMetadata(metadata.id)
  assert.equal(refreshed.retentionStatus, 'expired')

  assert.equal(await store.purgeArtifact(metadata.id, 'retention-elapsed'), true)
  const purged = await store.getArtifactMetadata(metadata.id)
  assert.equal(purged.availabilityStatus, 'purged')
  assert.equal(purged.purgeReason, 'retention-elapsed')
  assert.ok(purged.purgedAt)
  assert.equal(await store.openArtifact(metadata.id), null)
  assert.equal(await store.purgeArtifact(metadata.id, 'again'), false)

  const { rows } = await sqlClient.query(
    'SELECT availability_status, purge_reason, purged_at, legal_hold FROM artifacts WHERE artifact_id = $1',
    [metadata.id]
  )
  assert.equal(rows[0].availability_status, 'purged')
  assert.equal(rows[0].purge_reason, 'retention-elapsed')
  assert.ok(rows[0].purged_at)
  assert.equal(rows[0].legal_hold, false)
})

// ---------------------------------------------------------------------------
// 5. Legal hold
// ---------------------------------------------------------------------------

await scenario('legal hold blocks destruction until released', async () => {
  const { sqlClient, store } = createStore()
  const metadata = await store.putArtifact({
    owner: 'namespace-e',
    contentType: 'text/plain',
    data: encoder.encode('held'),
    retentionDays: 0,
  })
  assert.equal(metadata.retentionStatus, 'expired')

  const held = await store.setLegalHold(metadata.id, true, 'litigation')
  assert.equal(held.legalHold, true)
  assert.equal(held.legalHoldReason, 'litigation')
  assert.ok(held.legalHoldSetAt)

  assert.equal(await store.deleteArtifact(metadata.id), false)
  assert.equal(await store.purgeArtifact(metadata.id), false)
  assert.ok(await store.openArtifact(metadata.id))

  const { rows } = await sqlClient.query('SELECT legal_hold, legal_hold_reason FROM artifacts WHERE artifact_id = $1', [
    metadata.id,
  ])
  assert.equal(rows[0].legal_hold, true)
  assert.equal(rows[0].legal_hold_reason, 'litigation')

  const released = await store.setLegalHold(metadata.id, false)
  assert.equal(released.legalHold, false)
  assert.equal(released.legalHoldReason, undefined)
  assert.equal(released.legalHoldSetAt, undefined)

  assert.equal(await store.purgeArtifact(metadata.id, 'erasure request'), true)
  const purged = await store.getArtifactMetadata(metadata.id)
  assert.equal(purged.availabilityStatus, 'purged')
  assert.equal(purged.purgeReason, 'erasure request')
  assert.equal(await store.setLegalHold('unknown-id', true), null)
})

// ---------------------------------------------------------------------------
// 6. Governance parity with the in-memory adapter
// ---------------------------------------------------------------------------

async function governanceReport(store) {
  const ephemeral = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('ephemeral'),
    retentionDays: 0,
  })
  const purgeEphemeral = await store.purgeArtifact(ephemeral.id, 'early')
  const ephemeralMeta = await store.getArtifactMetadata(ephemeral.id)

  const active = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('active'),
    retentionDays: 30,
  })
  const purgeActive = await store.purgeArtifact(active.id)
  const activeMeta = await store.getArtifactMetadata(active.id)

  const holdable = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('holdable'),
    retentionDays: 0,
  })
  const held = await store.setLegalHold(holdable.id, true, 'audit')
  const deleteHeld = await store.deleteArtifact(holdable.id)
  const purgeHeld = await store.purgeArtifact(holdable.id)
  const released = await store.setLegalHold(holdable.id, false)
  const purgeReleased = await store.purgeArtifact(holdable.id, 'gdpr')
  const releasedMeta = await store.getArtifactMetadata(holdable.id)

  return {
    purgeEphemeral,
    ephemeralStatus: ephemeralMeta.availabilityStatus,
    ephemeralReason: ephemeralMeta.purgeReason,
    ephemeralPurgedAt: Boolean(ephemeralMeta.purgedAt),
    purgeActive,
    activeStatus: activeMeta.availabilityStatus,
    holdFlag: held.legalHold,
    holdReason: held.legalHoldReason,
    holdSetAt: Boolean(held.legalHoldSetAt),
    deleteHeld,
    purgeHeld,
    releaseFlag: released.legalHold,
    releaseReason: released.legalHoldReason,
    purgeReleased,
    releasedStatus: releasedMeta.availabilityStatus,
    releasedReason: releasedMeta.purgeReason,
    unknownMetadata: await store.getArtifactMetadata('nope'),
    unknownDelete: await store.deleteArtifact('nope'),
    unknownPurge: await store.purgeArtifact('nope'),
    unknownHold: await store.setLegalHold('nope', true),
  }
}

await scenario('governance parity with MemoryArtifactStore', async () => {
  const memoryReport = await governanceReport(new MemoryArtifactStore())
  const { store } = createStore()
  const postgresReport = await governanceReport(store)
  assert.deepEqual(postgresReport, memoryReport)
})

// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
