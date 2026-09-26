/**
 * Global artifact wiring test (Milestone B, wave B5-T3).
 *
 * Offline, no framework, no network and no database: exits 0 when every case
 * passes, 1 otherwise.
 *
 * Usage: node factory/tests/test-artifact-global-wiring.mjs
 *
 * Covers the composition root's *selection* of the artifact store and the
 * availability of every capability the dashboard relies on:
 *
 *   A. `loadConfig` exposes the artifact (S3/MinIO + retention/presign) config.
 *   B. Filesystem authority selects the in-memory `MemoryArtifactStore`.
 *   C. PostgreSQL authority composes `PostgresArtifactStore` (object client +
 *      SQL metadata repository) and exposes put / open / getSignedUrl.
 *   D. An explicit S3 configuration + a SQL client composes the store with a
 *      real `S3ObjectClient` even while the rest of the stores stay on `fs`.
 *   E. The configured retention window becomes the store default.
 *   F. `createApplication` attaches an explicit `artifactAdmin` capability
 *      (purge, legal hold, triggered GC) over the exact composed instances.
 *   G. The HTTP server wires `handleArtifactAdminRequest` to those instances.
 *   H. A live request to `/api/factory/admin/artifacts/gc` returns 200.
 */

import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  MemoryArtifactStore,
  PostgresArtifactStore,
  S3ObjectClient,
} from '../runtime/factory-operational.mjs'
import {
  createAdapters,
  createApplication,
  createCompositionRoot,
  createStores,
  loadConfig,
  resolveArtifactStore,
} from '../dashboard/composition-root.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

const __dirname = dirname(fileURLToPath(import.meta.url))
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

/** Offline object-storage fake, same shape as the other artifact suites. */
class FakeBlobClient {
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
    return {
      stream: (async function* () {
        yield body
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

  getSignedUrl(key, options) {
    return `https://object-store.invalid/coday-artifacts/${key}?X-Amz-Expires=${options?.expiresInSeconds ?? 900}`
  }
}

const BIND = { FACTORY_BIND_HOST: '127.0.0.1' }

// ---------------------------------------------------------------------------
// A. Configuration
// ---------------------------------------------------------------------------

await scenario('loadConfig exposes artifact config with safe defaults', () => {
  const config = loadConfig({ ...BIND })
  assert.deepEqual(config.artifact, {
    s3Endpoint: null,
    s3Region: 'us-east-1',
    s3Bucket: 'coday-artifacts',
    s3AccessKeyId: null,
    s3SecretAccessKey: null,
    s3SessionToken: null,
    artifactSignedUrlTtl: undefined,
    artifactRetentionDays: undefined,
    useMemoryBlobClient: false,
  })
})

await scenario('loadConfig parses S3 / retention / presign environment', () => {
  const config = loadConfig({
    ...BIND,
    S3_ENDPOINT: 'http://localhost:9000',
    S3_REGION: 'eu-west-3',
    S3_BUCKET: 'coday-artifacts',
    S3_ACCESS_KEY_ID: 'factory',
    S3_SECRET_ACCESS_KEY: 'factory_dev_pass',
    S3_SESSION_TOKEN: 'session-token',
    ARTIFACT_RETENTION_DAYS: '90',
    ARTIFACT_SIGNED_URL_TTL: '900',
  })
  assert.equal(config.artifact.s3Endpoint, 'http://localhost:9000')
  assert.equal(config.artifact.s3Region, 'eu-west-3')
  assert.equal(config.artifact.s3AccessKeyId, 'factory')
  assert.equal(config.artifact.s3SecretAccessKey, 'factory_dev_pass')
  assert.equal(config.artifact.s3SessionToken, 'session-token')
  assert.equal(config.artifact.artifactRetentionDays, 90)
  assert.equal(config.artifact.artifactSignedUrlTtl, 900)
})

// ---------------------------------------------------------------------------
// B. Filesystem authority → MemoryArtifactStore
// ---------------------------------------------------------------------------

await scenario('fs persistence selects MemoryArtifactStore and remains usable', async () => {
  const config = loadConfig({ ...BIND })
  const stores = createStores(config)
  assert.ok(stores.artifactStore instanceof MemoryArtifactStore)
  assert.ok(stores.artifactBlobClient, 'a blob-client shape is always exposed')
  assert.equal(stores.artifactMetadataRepository, null)

  const metadata = await stores.artifactStore.putArtifact({
    owner: 'ns-wiring',
    contentType: 'text/plain',
    data: encoder.encode('memory-wired artifact'),
  })
  const opened = await stores.artifactStore.openArtifact(metadata.id)
  assert.ok(opened)
  assert.equal((await readAll(opened.stream)).toString('utf8'), 'memory-wired artifact')
})

// ---------------------------------------------------------------------------
// C. PostgreSQL authority → composed PostgresArtifactStore
// ---------------------------------------------------------------------------

await scenario('sql persistence composes PostgresArtifactStore over blob + SQL', async () => {
  const config = loadConfig({ ...BIND, FACTORY_PERSISTENCE: 'sql' })
  const blobClient = new FakeBlobClient()
  const stores = createStores(config, { sqlClient: createInMemorySqlClient(), artifactBlobClient: blobClient })

  assert.ok(stores.artifactStore instanceof PostgresArtifactStore)
  assert.equal(stores.artifactBlobClient, blobClient)
  assert.ok(stores.artifactMetadataRepository, 'the SQL metadata repository is wired')
  assert.equal(stores.__persistenceAuthority, 'sql')

  const metadata = await stores.artifactStore.putArtifact({
    owner: 'ns-wiring',
    contentType: 'text/plain',
    data: encoder.encode('postgres-wired artifact'),
  })
  const opened = await stores.artifactStore.openArtifact(metadata.id)
  assert.equal((await readAll(opened.stream)).toString('utf8'), 'postgres-wired artifact')

  const url = await stores.artifactStore.getSignedUrl(metadata.id)
  assert.match(url, /^https:\/\/object-store\.invalid\/coday-artifacts\/objects\//)
})

// ---------------------------------------------------------------------------
// D. Explicit S3 config + SQL client → S3ObjectClient composition
// ---------------------------------------------------------------------------

await scenario('S3 configuration + sqlClient composes PostgresArtifactStore with S3ObjectClient', () => {
  const config = loadConfig({
    ...BIND,
    S3_ENDPOINT: 'http://localhost:9000',
    S3_ACCESS_KEY_ID: 'factory',
    S3_SECRET_ACCESS_KEY: 'factory_dev_pass',
    S3_BUCKET: 'coday-artifacts',
  })
  const stores = createStores(config, { sqlClient: createInMemorySqlClient() })
  assert.ok(stores.artifactStore instanceof PostgresArtifactStore)
  assert.ok(stores.artifactBlobClient instanceof S3ObjectClient)
})

await scenario('ARTIFACT_MEMORY_BLOB_CLIENT selects the in-memory blob fallback', () => {
  const config = loadConfig({
    ...BIND,
    FACTORY_PERSISTENCE: 'sql',
    ARTIFACT_MEMORY_BLOB_CLIENT: 'true',
    S3_ENDPOINT: 'http://localhost:9000',
    S3_ACCESS_KEY_ID: 'factory',
    S3_SECRET_ACCESS_KEY: 'factory_dev_pass',
  })
  assert.equal(config.artifact.useMemoryBlobClient, true)
  const selection = resolveArtifactStore(config, { sqlClient: createInMemorySqlClient() })
  assert.ok(selection.artifactStore instanceof PostgresArtifactStore)
  assert.ok(!(selection.artifactBlobClient instanceof S3ObjectClient))
})

await scenario('resolveArtifactStore is exported and selects by mode', () => {
  const fsConfig = loadConfig({ ...BIND })
  const fsSelection = resolveArtifactStore(fsConfig)
  assert.ok(fsSelection.artifactStore instanceof MemoryArtifactStore)

  const sqlConfig = loadConfig({ ...BIND, FACTORY_PERSISTENCE: 'sql' })
  const sqlSelection = resolveArtifactStore(sqlConfig, {
    sqlClient: createInMemorySqlClient(),
    artifactBlobClient: new FakeBlobClient(),
  })
  assert.ok(sqlSelection.artifactStore instanceof PostgresArtifactStore)
})

// ---------------------------------------------------------------------------
// E. Configured retention becomes the store default
// ---------------------------------------------------------------------------

await scenario('ARTIFACT_RETENTION_DAYS becomes the composed store default', async () => {
  const config = loadConfig({ ...BIND, FACTORY_PERSISTENCE: 'sql', ARTIFACT_RETENTION_DAYS: '42' })
  const stores = createStores(config, { sqlClient: createInMemorySqlClient(), artifactBlobClient: new FakeBlobClient() })
  const metadata = await stores.artifactStore.putArtifact({
    owner: 'ns-wiring',
    contentType: 'text/plain',
    data: encoder.encode('retained'),
  })
  assert.equal(metadata.retentionDays, 42)
})

// ---------------------------------------------------------------------------
// F. Application attaches the explicit artifactAdmin capability
// ---------------------------------------------------------------------------

await scenario('createApplication attaches artifactAdmin over the composed instances', async () => {
  const config = loadConfig({ ...BIND, FACTORY_PERSISTENCE: 'sql' })
  const blobClient = new FakeBlobClient()
  const stores = createStores(config, { sqlClient: createInMemorySqlClient(), artifactBlobClient: blobClient })
  const adapters = createAdapters(config, stores)
  const application = createApplication(config, stores, adapters)

  assert.equal(typeof application.artifactAdmin.purgeArtifact, 'function')
  assert.equal(typeof application.artifactAdmin.setLegalHold, 'function')
  assert.equal(typeof application.artifactAdmin.collectAndAuditGarbage, 'function')

  const metadata = await stores.artifactStore.putArtifact({
    owner: 'ns-wiring',
    contentType: 'text/plain',
    data: encoder.encode('governed'),
    retentionDays: 0,
  })

  // Legal hold blocks destruction; releasing it unlocks the purge.
  const held = await application.artifactAdmin.setLegalHold(metadata.id, true, 'litigation')
  assert.equal(held.legalHold, true)
  const refused = await application.artifactAdmin.purgeArtifact(metadata.id, 'too early')
  assert.equal(refused.success, false)
  assert.equal(refused.status, 'LEGAL_HOLD_ACTIVE')

  const released = await application.artifactAdmin.setLegalHold(metadata.id, false)
  assert.equal(released.legalHold, false)
  const purged = await application.artifactAdmin.purgeArtifact(metadata.id, 'expired')
  assert.equal(purged.success, true)
  assert.equal(purged.status, 'purged')
})

await scenario('artifactAdmin.collectAndAuditGarbage uses the injected metadata lister', async () => {
  const config = loadConfig({ ...BIND, FACTORY_PERSISTENCE: 'sql' })
  const blobClient = new FakeBlobClient()
  const stores = createStores(config, {
    sqlClient: createInMemorySqlClient(),
    artifactBlobClient: blobClient,
  })
  const adapters = createAdapters(config, stores)
  const application = createApplication(config, stores, adapters)

  const metadata = await stores.artifactStore.putArtifact({
    owner: 'ns-wiring',
    contentType: 'text/plain',
    data: encoder.encode('auditable'),
  })
  const [storageKey] = [...blobClient.objects.keys()].filter((key) => key.startsWith('objects/'))
  assert.ok(storageKey)

  // A matching metadata row and a matching blob: no anomaly.
  const clean = await application.artifactAdmin.collectAndAuditGarbage({
    listMetadata: async () => [{ artifactId: metadata.id, storageKey, availabilityStatus: 'available' }],
  })
  assert.deepEqual(clean.anomalies, [])
  assert.equal(clean.scannedMetadataRows, 1)

  // An orphaned blob with no authoritative row is audited.
  await blobClient.putObject('objects/deadbeef', encoder.encode('orphan'))
  const audited = await application.artifactAdmin.collectAndAuditGarbage({
    listMetadata: async () => [{ artifactId: metadata.id, storageKey, availabilityStatus: 'available' }],
  })
  assert.ok(audited.anomalies.some((anomaly) => anomaly.type === 'blob_without_pg_row'))
})

// ---------------------------------------------------------------------------
// G. HTTP wiring static guard
// ---------------------------------------------------------------------------

await scenario('composition-root wires the admin artifact route to the composed instances', async () => {
  const source = await readFile(join(__dirname, '..', 'dashboard', 'composition-root.mjs'), 'utf8')
  assert.match(source, /import \{ handleArtifactAdminRequest \} from '\.\/artifact-admin-routes\.mjs'/)
  assert.match(source, /handleArtifactAdminRequest\(\{/)
  assert.match(source, /store: artifactStore/)
  assert.match(source, /blobClient: artifactBlobClient/)
  assert.match(source, /listMetadata: artifactListMetadata/)
  for (const fn of ['purgeArtifactAdmin', 'setLegalHoldAdmin', 'collectAndAuditGarbage']) {
    assert.ok(source.includes(fn), `composition root must use ${fn}`)
  }
})

// ---------------------------------------------------------------------------
// H. Live HTTP route
// ---------------------------------------------------------------------------

await scenario('POST /api/factory/admin/artifacts/gc answers 200 on loopback', async () => {
  const root = await mkdtemp(join(tmpdir(), 'factory-artifact-wiring-'))
  try {
    const { server, config } = createCompositionRoot({
      ...BIND,
      PORT: '0',
      FACTORY_DATA_ROOT: root,
      // The boundary refuses loopback-dev unless explicitly enabled (B6-T2b).
      FACTORY_ALLOW_LOOPBACK_DEV: 'true',
    })
    assert.ok(config.artifact.s3Bucket)
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
    try {
      const { port } = server.address()
      const response = await fetch(`http://127.0.0.1:${port}/api/factory/admin/artifacts/gc`, { method: 'POST' })
      assert.equal(response.status, 200)
      const body = await response.json()
      assert.ok(Array.isArray(body.data.reclaimedStagingKeys))
      assert.ok(Array.isArray(body.data.anomalies))
    } finally {
      await new Promise((resolve) => server.close(resolve))
    }
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

console.log(`\nResult: ${passed} passed, ${failed} failed`)
process.exit(failed ? 1 : 0)
