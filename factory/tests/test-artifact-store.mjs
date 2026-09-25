/**
 * ArtifactStore port & adapters tests (Milestone B, wave B1).
 *
 * Offline, no framework: exits 0 when every case passes, 1 otherwise.
 * Usage: node factory/tests/test-artifact-store.mjs
 *
 * Covers:
 *   1.  Content addressing (`computeArtifactHash`, `createArtifactId`).
 *   2.  `MemoryArtifactStore.putArtifact` metadata (owner, hash, size, type).
 *   3.  `getArtifactMetadata` / `openArtifact` round-trip and unknown ids.
 *   4.  Retention window (`retentionStatus`, `retentionUntil`) enforcement.
 *   5.  Legal hold: set, release, and destruction refusal while active.
 *   6.  `deleteArtifact` / `purgeArtifact` transitions to `availabilityStatus: 'purged'`.
 *   7.  S3/MinIO adapter upload-then-commit protocol against a fake object client.
 *   8.  Orphaned staging upload collection.
 */

import assert from 'node:assert/strict'

import {
  MemoryArtifactStore,
  S3ArtifactStore,
  computeArtifactHash,
  createArtifactId,
  isRetentionActive,
} from '../runtime/factory-operational.mjs'

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

async function readAll(stream) {
  const chunks = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks)
}

// ---------------------------------------------------------------------------
// In-memory fake S3 client (offline, no network)
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
    const self = this
    return {
      stream: (async function* () {
        for (let offset = 0; offset < body.byteLength; offset += 3) {
          yield self.objects.get(key).subarray(offset, Math.min(offset + 3, body.byteLength))
        }
      })(),
    }
  }

  async deleteObject(key) {
    return this.objects.delete(key)
  }

  async listObjectKeys(prefix) {
    return [...this.objects.keys()].filter((key) => key.startsWith(prefix))
  }
}

// ---------------------------------------------------------------------------
// 1. Content addressing
// ---------------------------------------------------------------------------

await scenario('computeArtifactHash is a deterministic sha256 content address', () => {
  const payload = encoder.encode('factory-artifact')
  const hash = computeArtifactHash(payload)
  assert.match(hash, /^sha256:[0-9a-f]{64}$/)
  assert.equal(hash, computeArtifactHash(Buffer.from('factory-artifact')))
  assert.notEqual(hash, computeArtifactHash(encoder.encode('factory-artifact!')))
})

await scenario('createArtifactId yields unique identifiers', () => {
  const ids = new Set(Array.from({ length: 50 }, () => createArtifactId()))
  assert.equal(ids.size, 50)
})

// ---------------------------------------------------------------------------
// 2 & 3. Memory adapter round-trip
// ---------------------------------------------------------------------------

await scenario('putArtifact stores metadata and payload', async () => {
  const store = new MemoryArtifactStore()
  const payload = encoder.encode('hello artifact')
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
  assert.ok(Number.isFinite(Date.parse(metadata.createdAt)))

  const found = await store.getArtifactMetadata(metadata.id)
  assert.deepEqual(found, metadata)
  assert.equal(await store.getArtifactMetadata('unknown-id'), null)

  const opened = await store.openArtifact(metadata.id)
  assert.ok(opened)
  assert.deepEqual(await readAll(opened.stream), Buffer.from(payload))
  assert.equal((await store.openArtifact('unknown-id')), null)
})

await scenario('memory adapter streams large payloads in chunks', async () => {
  const store = new MemoryArtifactStore({ chunkSize: 4 })
  const payload = encoder.encode('0123456789')
  const metadata = await store.putArtifact({ owner: 'ns', contentType: 'application/octet-stream', data: payload })
  const opened = await store.openArtifact(metadata.id)
  const chunks = []
  for await (const chunk of opened.stream) chunks.push(Buffer.from(chunk).toString('utf8'))
  assert.deepEqual(chunks, ['0123', '4567', '89'])
})

// ---------------------------------------------------------------------------
// 4. Retention
// ---------------------------------------------------------------------------

await scenario('retention window gates purgeArtifact and deleteArtifact', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('retained'),
    retentionDays: 30,
  })
  assert.equal(metadata.retentionStatus, 'active')
  assert.equal(isRetentionActive(metadata, new Date()), true)
  assert.equal(await store.purgeArtifact(metadata.id, 'too early'), false)
  assert.equal(await store.deleteArtifact(metadata.id, 'too early'), false)
  assert.equal((await store.getArtifactMetadata(metadata.id))?.availabilityStatus, 'available')
})

await scenario('zero-day retention expires immediately and allows purge', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('ephemeral'),
    retentionDays: 0,
  })
  assert.equal(metadata.retentionStatus, 'expired')
  assert.equal(await store.purgeArtifact(metadata.id, 'retention elapsed'), true)
  const purged = await store.getArtifactMetadata(metadata.id)
  assert.equal(purged?.availabilityStatus, 'purged')
  assert.ok(purged?.purgedAt)
  assert.equal(purged?.purgeReason, 'retention elapsed')
  assert.equal(await store.openArtifact(metadata.id), null)
  assert.equal(await store.purgeArtifact(metadata.id), false)
})

// ---------------------------------------------------------------------------
// 5 & 6. Legal hold and deletion
// ---------------------------------------------------------------------------

await scenario('legal hold blocks destruction until released', async () => {
  const store = new MemoryArtifactStore()
  const metadata = await store.putArtifact({
    owner: 'ns',
    contentType: 'text/plain',
    data: encoder.encode('held'),
  })
  const held = await store.setLegalHold(metadata.id, true, 'litigation')
  assert.equal(held?.legalHold, true)
  assert.equal(held?.legalHoldReason, 'litigation')
  assert.ok(held?.legalHoldSetAt)
  assert.equal(await store.deleteArtifact(metadata.id), false)
  assert.equal(await store.purgeArtifact(metadata.id), false)
  assert.ok(await store.openArtifact(metadata.id))

  const released = await store.setLegalHold(metadata.id, false)
  assert.equal(released?.legalHold, false)
  assert.equal(released?.legalHoldReason, undefined)
  assert.equal(await store.deleteArtifact(metadata.id, 'erasure request'), true)
  const purged = await store.getArtifactMetadata(metadata.id)
  assert.equal(purged?.availabilityStatus, 'purged')
  assert.equal(purged?.purgeReason, 'erasure request')
  assert.equal(await store.deleteArtifact(metadata.id), false)
  assert.equal(await store.setLegalHold('unknown-id', true), null)
})

// ---------------------------------------------------------------------------
// 7 & 8. S3 / MinIO adapter
// ---------------------------------------------------------------------------

function createFakeS3ArtifactStore() {
  const client = new FakeS3ObjectClient()
  const store = new S3ArtifactStore({
    client,
    endpoint: 'http://localhost:9000',
    region: 'us-east-1',
    bucket: 'coday-artifacts',
    accessKeyId: 'factory',
    secretAccessKey: 'factory_dev_pass',
  })
  return { client, store }
}

await scenario('S3 adapter commits staged uploads to content-addressed keys', async () => {
  const { client, store } = createFakeS3ArtifactStore()
  const payload = encoder.encode('s3-artifact')
  const metadata = await store.putArtifact({
    owner: 'namespace-b',
    contentType: 'application/octet-stream',
    data: payload,
    retentionDays: 7,
  })
  const digest = metadata.hash.slice('sha256:'.length)
  const keys = [...client.objects.keys()].sort()
  assert.deepEqual(keys, [`metadata/${metadata.id}.json`, `objects/${digest}`])
  assert.equal(client.objects.has(`uploads/${metadata.id}.part`), false)
  assert.deepEqual(Buffer.from(client.objects.get(`objects/${digest}`)), Buffer.from(payload))

  const opened = await store.openArtifact(metadata.id)
  assert.ok(opened)
  assert.deepEqual(await readAll(opened.stream), Buffer.from(payload))
  assert.equal((await store.getArtifactMetadata('missing'))?.id, undefined)

  // Retention window is enforced by the S3 adapter too.
  assert.equal(await store.purgeArtifact(metadata.id), false)
  assert.equal(await store.deleteArtifact(metadata.id), false)

  // Legal hold blocks destruction of a destroyable (expired-retention) artifact.
  const target = await store.putArtifact({
    owner: 'namespace-b',
    contentType: 'application/octet-stream',
    data: encoder.encode('s3-held'),
  })
  const held = await store.setLegalHold(target.id, true, 'audit')
  assert.equal(held?.legalHold, true)
  assert.equal(await store.deleteArtifact(target.id), false)
  await store.setLegalHold(target.id, false)
  assert.equal(await store.deleteArtifact(target.id, 'gdpr'), true)
  const purged = await store.getArtifactMetadata(target.id)
  assert.equal(purged?.availabilityStatus, 'purged')
  assert.equal(purged?.purgeReason, 'gdpr')
  assert.equal(await store.openArtifact(target.id), null)
})

await scenario('S3 adapter collects orphaned staging uploads', async () => {
  const { client, store } = createFakeS3ArtifactStore()
  await client.putObject('uploads/orphan-1.part', encoder.encode('leftover'))
  await client.putObject('uploads/orphan-2.part', encoder.encode('leftover'))
  await client.putObject('objects/keep', encoder.encode('payload'))
  const reclaimed = (await store.collectOrphanedUploads()).sort()
  assert.deepEqual(reclaimed, ['uploads/orphan-1.part', 'uploads/orphan-2.part'])
  assert.deepEqual([...client.objects.keys()], ['objects/keep'])
})

// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
