/**
 * Admin artifact governance commands tests (Milestone B, wave B5-T2b).
 *
 * Offline, no framework and no network: exits 0 when every case passes, 1
 * otherwise.
 *
 * Usage: node factory/tests/test-artifact-admin-commands.mjs
 *
 * Covers:
 *   1.  Admin purge succeeds on an expired-retention artifact.
 *   2.  Admin purge is refused on active retention.
 *   3.  Admin purge is refused while a legal hold is active.
 *   4.  Admin purge is refused (structured) for an unknown artifact.
 *   5.  Admin legal-hold placement and release; unknown artifact errors.
 *   6.  Triggered GC reclaims orphaned staging uploads (store hook + fallback).
 *   7.  Triggered GC audits blob-store / metadata anomalies
 *       (`blob_without_pg_row`, `pg_purged_or_missing_blob`).
 *   8.  The explicit admin authorization guard gates every admin command
 *       (guard unit cases + HTTP route invocation).
 */

import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import {
  ArtifactAdminError,
  collectAndAuditGarbage,
  purgeArtifactAdmin,
  setLegalHoldAdmin,
} from '../lib/artifact-admin-use-cases.mjs'
import { MemoryArtifactStore } from '../runtime/factory-operational.mjs'
import { checkAdminAuthorization, requireAdminRole } from '../dashboard/http-utils.mjs'
import { handleArtifactAdminRequest } from '../dashboard/artifact-admin-routes.mjs'

const encoder = new TextEncoder()

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

/** Offline object-storage fake, same shape as the other artifact suites. */
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
        yield objects.get(key)
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

async function seed(store, retentionDays, payload = 'payload') {
  return store.putArtifact({
    owner: 'namespace-admin',
    contentType: 'application/octet-stream',
    data: encoder.encode(payload),
    ...(retentionDays !== undefined ? { retentionDays } : {}),
  })
}

// ---------------------------------------------------------------------------
// 1 & 2 & 3 & 4. Admin purge
// ---------------------------------------------------------------------------

await scenario('admin purge destroys an expired-retention artifact', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 0)
  assert.equal(metadata.retentionStatus, 'expired')

  const result = await purgeArtifactAdmin(store, metadata.id, 'retention-elapsed')
  assert.equal(result.success, true)
  assert.equal(result.status, 'purged')
  assert.equal(result.artifactId, metadata.id)
  assert.equal(result.reason, 'retention-elapsed')

  const purged = await store.getArtifactMetadata(metadata.id)
  assert.equal(purged.availabilityStatus, 'purged')
  assert.equal(purged.purgeReason, 'retention-elapsed')
  assert.equal(await store.openArtifact(metadata.id), null)
})

await scenario('admin purge is refused while retention is active', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 30)
  const result = await purgeArtifactAdmin(store, metadata.id, 'premature')
  assert.equal(result.success, false)
  assert.equal(result.status, 'RETENTION_ACTIVE')
  const unchanged = await store.getArtifactMetadata(metadata.id)
  assert.equal(unchanged.availabilityStatus, 'available')
})

await scenario('admin purge is refused while a legal hold is active', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 0)
  await setLegalHoldAdmin(store, metadata.id, true, 'litigation')
  const result = await purgeArtifactAdmin(store, metadata.id, 'attempt')
  assert.equal(result.success, false)
  assert.equal(result.status, 'LEGAL_HOLD_ACTIVE')
  const unchanged = await store.getArtifactMetadata(metadata.id)
  assert.equal(unchanged.availabilityStatus, 'available')
})

await scenario('admin purge reports NOT_FOUND for an unknown artifact', async () => {
  const store = new MemoryArtifactStore()
  const result = await purgeArtifactAdmin(store, 'missing-artifact', 'attempt')
  assert.equal(result.success, false)
  assert.equal(result.status, 'NOT_FOUND')
})

// ---------------------------------------------------------------------------
// 5. Legal hold management
// ---------------------------------------------------------------------------

await scenario('admin legal hold places and releases the hold', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 0)

  const held = await setLegalHoldAdmin(store, metadata.id, true, 'litigation')
  assert.equal(held.legalHold, true)
  assert.equal(held.legalHoldReason, 'litigation')
  assert.ok(held.legalHoldSetAt)

  const refused = await purgeArtifactAdmin(store, metadata.id, 'attempt')
  assert.equal(refused.status, 'LEGAL_HOLD_ACTIVE')

  const released = await setLegalHoldAdmin(store, metadata.id, false, 'released')
  assert.equal(released.legalHold, false)
  assert.equal(released.legalHoldReason, undefined)
  assert.equal(released.legalHoldSetAt, undefined)

  const result = await purgeArtifactAdmin(store, metadata.id, 'gdpr')
  assert.equal(result.success, true)
})

await scenario('admin legal hold throws ARTIFACT_NOT_FOUND for an unknown artifact', async () => {
  const store = new MemoryArtifactStore()
  let thrown
  try {
    await setLegalHoldAdmin(store, 'missing-artifact', true)
  } catch (error) {
    thrown = error
  }
  assert.ok(thrown instanceof ArtifactAdminError)
  assert.equal(thrown.code, 'ARTIFACT_NOT_FOUND')
  assert.equal(thrown.statusCode, 404)
})

// ---------------------------------------------------------------------------
// 6. Triggered GC — staging reclamation
// ---------------------------------------------------------------------------

await scenario('triggered GC reclaims orphaned staging uploads via the store hook', async () => {
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('uploads/orphan.part', encoder.encode('leftover'))
  const calls = []
  const hookStore = {
    async collectOrphanedUploads() {
      calls.push('collectOrphanedUploads')
      const reclaimed = []
      for (const key of [...blobClient.objects.keys()]) {
        if (await blobClient.deleteObject(key)) reclaimed.push(key)
      }
      return reclaimed
    },
  }

  const report = await collectAndAuditGarbage(hookStore, blobClient, { listMetadata: async () => [] })
  assert.deepEqual(calls, ['collectOrphanedUploads'])
  assert.deepEqual(report.reclaimedStagingKeys, ['uploads/orphan.part'])
  assert.equal(blobClient.objects.has('uploads/orphan.part'), false)
  assert.ok(Number.isFinite(Date.parse(report.timestamp)))
})

await scenario('triggered GC falls back to listing uploads when the store exposes no hook', async () => {
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('uploads/orphan-1.part', encoder.encode('leftover'))
  await blobClient.putObject('uploads/orphan-2.part', encoder.encode('leftover'))
  await blobClient.putObject('objects/keep', encoder.encode('payload'))
  const store = new MemoryArtifactStore()

  const report = await collectAndAuditGarbage(store, blobClient, { listMetadata: async () => [] })
  assert.deepEqual(report.reclaimedStagingKeys.sort(), ['uploads/orphan-1.part', 'uploads/orphan-2.part'])
  assert.deepEqual([...blobClient.objects.keys()], ['objects/keep'])
})

// ---------------------------------------------------------------------------
// 7. Triggered GC — anomaly audit
// ---------------------------------------------------------------------------

await scenario('triggered GC audits blob_without_pg_row anomalies', async () => {
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('objects/orphan-blob', encoder.encode('payload'))
  const store = new MemoryArtifactStore()

  const report = await collectAndAuditGarbage(store, blobClient, { listMetadata: async () => [] })
  assert.deepEqual(report.scannedBlobKeys, ['objects/orphan-blob'])
  assert.equal(report.scannedMetadataRows, 0)
  assert.equal(report.anomalies.length, 1)
  const [anomaly] = report.anomalies
  assert.equal(anomaly.type, 'blob_without_pg_row')
  assert.equal(anomaly.storageKey, 'objects/orphan-blob')
  assert.ok(anomaly.details)
})

await scenario('triggered GC audits pg_purged_or_missing_blob anomalies', async () => {
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('objects/consistent', encoder.encode('payload'))
  const store = new MemoryArtifactStore()

  const listMetadata = async () => [
    { artifactId: 'art-consistent', storageKey: 'objects/consistent', availabilityStatus: 'available' },
    { artifactId: 'art-purged', storageKey: 'objects/purged-blob', availabilityStatus: 'purged' },
    { artifactId: 'art-missing', storageKey: 'objects/missing-blob', availabilityStatus: 'available' },
  ]

  const report = await collectAndAuditGarbage(store, blobClient, { listMetadata })
  assert.equal(report.scannedMetadataRows, 3)

  const byArtifact = new Map(report.anomalies.map((entry) => [entry.artifactId, entry]))
  assert.equal(report.anomalies.length, 2)
  assert.equal(byArtifact.get('art-purged').type, 'pg_purged_or_missing_blob')
  assert.match(byArtifact.get('art-purged').details, /purged/)
  assert.equal(byArtifact.get('art-missing').type, 'pg_purged_or_missing_blob')
  assert.equal(byArtifact.get('art-missing').storageKey, 'objects/missing-blob')
  assert.equal(byArtifact.has('art-consistent'), false, 'a consistent row must not be flagged')
})

await scenario('triggered GC report exposes a structured, stable shape', async () => {
  const blobClient = new FakeS3ObjectClient()
  const store = new MemoryArtifactStore()
  const report = await collectAndAuditGarbage(store, blobClient)
  assert.deepEqual(Object.keys(report).sort(), [
    'anomalies',
    'reclaimedStagingKeys',
    'scannedBlobKeys',
    'scannedMetadataRows',
    'timestamp',
  ])
  assert.ok(Array.isArray(report.reclaimedStagingKeys))
  assert.ok(Array.isArray(report.anomalies))
  assert.ok(Array.isArray(report.scannedBlobKeys))
})

// ---------------------------------------------------------------------------
// 8. Explicit admin authorization
// ---------------------------------------------------------------------------

await scenario('checkAdminAuthorization inspects the TrustContext', () => {
  assert.deepEqual(checkAdminAuthorization(null), { authorized: false, reason: 'MISSING_TRUST_CONTEXT' })
  assert.deepEqual(checkAdminAuthorization({ roles: [], scopes: [] }), {
    authorized: false,
    reason: 'INSUFFICIENT_ADMIN_PERMISSIONS',
  })
  assert.equal(checkAdminAuthorization({ roles: ['viewer'], scopes: [] }).authorized, false)
  assert.equal(checkAdminAuthorization({ roles: ['admin'], scopes: [] }).authorized, true)
  assert.equal(checkAdminAuthorization({ roles: [], scopes: ['admin:*'] }).authorized, true)
  assert.equal(checkAdminAuthorization({ roles: [], scopes: ['*'] }).authorized, true)
})

await scenario('requireAdminRole enforces the guard with a 403 contract', () => {
  assert.equal(requireAdminRole({ roles: ['admin'] }), true)
  let thrown
  try {
    requireAdminRole({ roles: ['viewer'] })
  } catch (error) {
    thrown = error
  }
  assert.ok(thrown)
  assert.equal(thrown.code, 'FORBIDDEN_ADMIN_REQUIRED')
  assert.equal(thrown.statusCode, 403)
})

function createRouteHarness() {
  const context = { response: null }
  return {
    context,
    send: (status, body) => {
      context.response = { status, body }
    },
    readBody: async () => context.body ?? {},
  }
}

await scenario('admin routes explicitly invoke the authorization guard and block non-admins', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 0)
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('uploads/orphan.part', encoder.encode('leftover'))

  const harness = createRouteHarness()
  let purged = false
  const spyStore = {
    putArtifact: (params) => store.putArtifact(params),
    getArtifactMetadata: (id) => store.getArtifactMetadata(id),
    openArtifact: (id) => store.openArtifact(id),
    deleteArtifact: (id, reason) => store.deleteArtifact(id, reason),
    setLegalHold: (id, legalHold, reason) => store.setLegalHold(id, legalHold, reason),
    async purgeArtifact(id, reason) {
      purged = true
      return store.purgeArtifact(id, reason)
    },
  }

  // Non-admin request: the guard is invoked, refuses, and the store is untouched.
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/purge`,
    trustContext: { roles: ['viewer'], scopes: [] },
    readBody: harness.readBody,
    send: harness.send,
    store: spyStore,
    blobClient,
  })
  assert.equal(harness.context.response.status, 403)
  assert.equal(harness.context.response.body.error.code, 'FORBIDDEN_ADMIN_REQUIRED')
  assert.equal(purged, false, 'the store must not be mutated for a non-admin')

  // Admin request: the same route now succeeds.
  harness.context.body = { reason: 'admin' }
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/purge`,
    trustContext: { roles: ['admin'], scopes: [] },
    readBody: harness.readBody,
    send: harness.send,
    store: spyStore,
    blobClient,
  })
  assert.equal(harness.context.response.status, 200)
  assert.equal(harness.context.response.body.data.success, true)
  assert.equal(purged, true)
})

await scenario('admin legal-hold and GC routes are gated the same way', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await seed(store, 0)
  const blobClient = new FakeS3ObjectClient()
  await blobClient.putObject('uploads/orphan.part', encoder.encode('leftover'))

  // Legal hold, non-admin -> 403.
  const nonAdmin = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/legal-hold`,
    trustContext: { roles: ['viewer'], scopes: [] },
    readBody: async () => ({ legalHold: true }),
    send: nonAdmin.send,
    store,
    blobClient,
  })
  assert.equal(nonAdmin.context.response.status, 403)
  assert.equal((await store.getArtifactMetadata(metadata.id)).legalHold, false)

  // Legal hold, admin -> 200.
  const admin = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: `/api/factory/admin/artifacts/${metadata.id}/legal-hold`,
    trustContext: { scopes: ['admin:*'] },
    readBody: async () => ({ legalHold: true, reason: 'litigation' }),
    send: admin.send,
    store,
    blobClient,
  })
  assert.equal(admin.context.response.status, 200)
  assert.equal(admin.context.response.body.data.legalHold, true)

  // GC, non-admin -> 403 and nothing reclaimed.
  const gcDenied = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: '/api/factory/admin/artifacts/gc',
    trustContext: null,
    readBody: async () => ({}),
    send: gcDenied.send,
    store,
    blobClient,
  })
  assert.equal(gcDenied.context.response.status, 403)
  assert.equal(blobClient.objects.has('uploads/orphan.part'), true)

  // GC, admin -> 200 and the orphan is reclaimed.
  const gcAllowed = createRouteHarness()
  await handleArtifactAdminRequest({
    method: 'POST',
    path: '/api/factory/admin/artifacts/gc',
    trustContext: { roles: ['admin'] },
    readBody: async () => ({}),
    send: gcAllowed.send,
    store,
    blobClient,
  })
  assert.equal(gcAllowed.context.response.status, 200)
  assert.deepEqual(gcAllowed.context.response.body.data.reclaimedStagingKeys, ['uploads/orphan.part'])
})

await scenario('admin routes reject non-POST admin commands after authorizing', async () => {
  const harness = createRouteHarness()
  const handled = await handleArtifactAdminRequest({
    method: 'GET',
    path: '/api/factory/admin/artifacts/gc',
    trustContext: { roles: ['admin'] },
    readBody: harness.readBody,
    send: harness.send,
    store: new MemoryArtifactStore(),
    blobClient: new FakeS3ObjectClient(),
  })
  assert.equal(handled, true)
  assert.equal(harness.context.response.status, 405)
})

await scenario('admin routes ignore unrelated paths', async () => {
  const harness = createRouteHarness()
  const handled = await handleArtifactAdminRequest({
    method: 'POST',
    path: '/api/factory/workflows/wf/anything',
    trustContext: { roles: ['admin'] },
    readBody: harness.readBody,
    send: harness.send,
  })
  assert.equal(handled, false)
  assert.equal(harness.context.response, null)
})

await scenario('the route module marks requireAdminRole as the explicit auth point', async () => {
  const source = await readFile(new URL('../dashboard/artifact-admin-routes.mjs', import.meta.url), 'utf8')
  assert.match(source, /requireAdminRole\(trustContext\)/)
})

// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
