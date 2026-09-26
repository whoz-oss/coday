/**
 * Presigned S3 / MinIO URLs for readable artifacts (B5-T2a).
 *
 * Offline, no framework and no network: exits 0 when every case passes, 1
 * otherwise.
 *
 * Usage: node factory/tests/test-artifact-signed-urls.mjs
 *
 * Covers:
 *   A. `S3ObjectClient.getSignedUrl` SigV4 query-parameter format, verified
 *      against an independent reference signer computed with `node:crypto`.
 *   B. Configurable TTL: explicit option, `ARTIFACT_SIGNED_URL_TTL` env var and
 *      the 900s default.
 *   C. `PostgresArtifactStore.getSignedUrl` governance: available artifacts
 *      produce a URL, unknown / purged artifacts return `null`, options are
 *      forwarded, and a blob client without presigning support returns `null`.
 */

import assert from 'node:assert/strict'
import { createHash, createHmac } from 'node:crypto'

import {
  PostgresArtifactStore,
  S3ObjectClient,
  createPostgresArtifactStore,
  createSqlArtifactMetadataRepository,
} from '../runtime/factory-operational.mjs'
import { createInMemorySqlClient } from './support/in-memory-sql-client.mjs'

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

// ---------------------------------------------------------------------------
// Independent reference SigV4 signer (no import from the code under test)
// ---------------------------------------------------------------------------

function encodeComponent(value) {
  return encodeURIComponent(value).replace(/[!'()*]/g, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase()}`)
}

function encodeKeyPath(key) {
  return key.split('/').map(encodeComponent).join('/')
}

function referenceSignedUrl(config, key, { expiresInSeconds = 900, now } = {}) {
  const base = config.endpoint.replace(/\/+$/, '')
  const host = new URL(base).host
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, '')
  const dateStamp = amzDate.slice(0, 8)
  const canonicalUri = `/${encodeComponent(config.bucket)}${key ? `/${encodeKeyPath(key)}` : ''}`
  const scope = `${dateStamp}/${config.region}/s3/aws4_request`

  const query = {
    'X-Amz-Algorithm': 'AWS4-HMAC-SHA256',
    'X-Amz-Credential': `${config.accessKeyId}/${scope}`,
    'X-Amz-Date': amzDate,
    'X-Amz-Expires': String(expiresInSeconds),
    'X-Amz-SignedHeaders': 'host',
  }
  if (config.sessionToken !== undefined) query['X-Amz-Security-Token'] = config.sessionToken

  const canonicalQuery = Object.keys(query)
    .sort()
    .map((name) => `${encodeComponent(name)}=${encodeComponent(query[name])}`)
    .join('&')
  const canonicalRequest = [
    'GET',
    canonicalUri,
    canonicalQuery,
    `host:${host}\n`,
    'host',
    'UNSIGNED-PAYLOAD',
  ].join('\n')

  const stringToSign = [
    'AWS4-HMAC-SHA256',
    amzDate,
    scope,
    createHash('sha256').update(canonicalRequest).digest('hex'),
  ].join('\n')
  const hmac = (keyValue, data) => createHmac('sha256', keyValue).update(data, 'utf8').digest()
  const signingKey = hmac(
    hmac(hmac(hmac(`AWS4${config.secretAccessKey}`, dateStamp), config.region), 's3'),
    'aws4_request'
  )
  const signature = createHmac('sha256', signingKey).update(stringToSign, 'utf8').digest('hex')

  return `${base}${canonicalUri}?${canonicalQuery}&X-Amz-Signature=${signature}`
}

// ---------------------------------------------------------------------------
// Offline object-storage fake (same shape as `test-artifact-store.mjs`)
// ---------------------------------------------------------------------------

class FakeStoreBlobClient {
  constructor(presigner) {
    this.objects = new Map()
    this.presigner = presigner
    this.signedUrlCalls = []
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
    this.signedUrlCalls.push({ key, options })
    if (this.presigner) return this.presigner(key, options)
    return `https://object-store.invalid/coday/${key}?X-Amz-Expires=${options?.expiresInSeconds ?? 900}`
  }
}

function createStore({ presigner, env } = {}) {
  const client = new FakeStoreBlobClient(presigner)
  const repository = createSqlArtifactMetadataRepository(createInMemorySqlClient())
  const store = createPostgresArtifactStore({
    client,
    repository,
    ...(env !== undefined ? { env } : {}),
  })
  return { client, repository, store }
}

// ---------------------------------------------------------------------------
// A. Low-level SigV4 presigning
// ---------------------------------------------------------------------------

const S3_CONFIG = {
  endpoint: 'http://localhost:9000',
  region: 'us-east-1',
  bucket: 'coday-artifacts',
  accessKeyId: 'factory',
  secretAccessKey: 'factory_dev_pass',
}
const FIXED_NOW = new Date('2026-01-01T00:00:00.000Z')

await scenario('exposes the presigning method', () => {
  assert.equal(typeof S3ObjectClient.prototype.getSignedUrl, 'function')
})

await scenario('getSignedUrl produces a SigV4 query-parameter GET URL', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  const url = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })

  assert.match(url, /^http:\/\/localhost:9000\/coday-artifacts\/objects\/abc123\?/)
  const params = new URL(url).searchParams
  assert.equal(params.get('X-Amz-Algorithm'), 'AWS4-HMAC-SHA256')
  assert.equal(params.get('X-Amz-Credential'), 'factory/20260101/us-east-1/s3/aws4_request')
  assert.equal(params.get('X-Amz-Date'), '20260101T000000Z')
  assert.equal(params.get('X-Amz-Expires'), '900')
  assert.equal(params.get('X-Amz-SignedHeaders'), 'host')
  assert.equal(params.get('X-Amz-Security-Token'), null)
  assert.match(params.get('X-Amz-Signature'), /^[0-9a-f]{64}$/)

  // The canonical query parameters are sorted; the signature is appended last.
  const names = [...params.keys()]
  assert.equal(names.at(-1), 'X-Amz-Signature')
  const signed = names.slice(0, -1)
  assert.deepEqual(signed, [...signed].sort())
})

await scenario('getSignedUrl signature matches an independent reference signer', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  for (const key of ['objects/abc123', 'objects/a b/c-d_e', '']) {
    const url = client.getSignedUrl(key, { now: FIXED_NOW })
    assert.equal(url, referenceSignedUrl(S3_CONFIG, key, { now: FIXED_NOW }))
  }
})

await scenario('getSignedUrl signs the quoted UNSIGNED-PAYLOAD marker', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  const url = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })
  // Re-derive the signature with the standard presigned payload marker.
  assert.equal(url, referenceSignedUrl(S3_CONFIG, 'objects/abc123', { now: FIXED_NOW }))
  assert.doesNotMatch(url, /x-amz-content-sha256/i)
})

await scenario('getSignedUrl encodes the session token when present', () => {
  const client = new S3ObjectClient({ ...S3_CONFIG, sessionToken: 'session+token/value' })
  const url = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })
  const params = new URL(url).searchParams
  assert.equal(params.get('X-Amz-Security-Token'), 'session+token/value')
  assert.equal(url, referenceSignedUrl({ ...S3_CONFIG, sessionToken: 'session+token/value' }, 'objects/abc123', { now: FIXED_NOW }))
})

// ---------------------------------------------------------------------------
// B. Configurable TTL
// ---------------------------------------------------------------------------

await scenario('explicit expiresInSeconds sets X-Amz-Expires', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  const url = client.getSignedUrl('objects/abc123', { expiresInSeconds: 3600, now: FIXED_NOW })
  assert.equal(new URL(url).searchParams.get('X-Amz-Expires'), '3600')
})

await scenario('ARTIFACT_SIGNED_URL_TTL env var configures the TTL', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  const previous = process.env.ARTIFACT_SIGNED_URL_TTL
  try {
    process.env.ARTIFACT_SIGNED_URL_TTL = '1800'
    const url = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })
    assert.equal(new URL(url).searchParams.get('X-Amz-Expires'), '1800')

    // An explicit option wins over the environment.
    const explicit = client.getSignedUrl('objects/abc123', { expiresInSeconds: 60, now: FIXED_NOW })
    assert.equal(new URL(explicit).searchParams.get('X-Amz-Expires'), '60')

    // Invalid values fall back to the default.
    process.env.ARTIFACT_SIGNED_URL_TTL = 'not-a-number'
    const invalid = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })
    assert.equal(new URL(invalid).searchParams.get('X-Amz-Expires'), '900')
  } finally {
    if (previous === undefined) delete process.env.ARTIFACT_SIGNED_URL_TTL
    else process.env.ARTIFACT_SIGNED_URL_TTL = previous
  }
})

await scenario('default TTL of 900s applies when nothing is configured', () => {
  const client = new S3ObjectClient(S3_CONFIG)
  const previous = process.env.ARTIFACT_SIGNED_URL_TTL
  delete process.env.ARTIFACT_SIGNED_URL_TTL
  try {
    const url = client.getSignedUrl('objects/abc123', { now: FIXED_NOW })
    assert.equal(new URL(url).searchParams.get('X-Amz-Expires'), '900')
  } finally {
    if (previous !== undefined) process.env.ARTIFACT_SIGNED_URL_TTL = previous
  }
})

// ---------------------------------------------------------------------------
// C. PostgresArtifactStore governance
// ---------------------------------------------------------------------------

await scenario('store presigns an available artifact through the blob client', async () => {
  const presigner = new S3ObjectClient(S3_CONFIG)
  const { client, store } = createStore({ presigner: (key, options) => presigner.getSignedUrl(key, options) })
  const metadata = await store.putArtifact({
    owner: 'namespace-a',
    contentType: 'text/plain',
    data: encoder.encode('readable-artifact'),
    retentionDays: 30,
  })

  const url = await store.getSignedUrl(metadata.id, { now: FIXED_NOW })
  assert.equal(typeof url, 'string')
  assert.match(url, /^http:\/\/localhost:9000\/coday-artifacts\/objects\/[0-9a-f]{64}\?/)
  assert.equal(new URL(url).searchParams.get('X-Amz-Algorithm'), 'AWS4-HMAC-SHA256')
  assert.equal(client.signedUrlCalls.length, 1)
  assert.equal(client.signedUrlCalls[0].key, `objects/${metadata.hash.slice('sha256:'.length)}`)
})

await scenario('store forwards expiry options to the blob client', async () => {
  const { client, store } = createStore()
  const metadata = await store.putArtifact({
    owner: 'namespace-a',
    contentType: 'text/plain',
    data: encoder.encode('custom-ttl'),
    retentionDays: 30,
  })

  const url = await store.getSignedUrl(metadata.id, { expiresInSeconds: 123 })
  assert.equal(typeof url, 'string')
  assert.equal(client.signedUrlCalls.at(-1).options.expiresInSeconds, 123)
})

await scenario('store returns null for an unknown artifact', async () => {
  const { client, store } = createStore()
  assert.equal(await store.getSignedUrl('does-not-exist'), null)
  assert.equal(client.signedUrlCalls.length, 0)
})

await scenario('store returns null for a purged artifact', async () => {
  const { store } = createStore()
  const metadata = await store.putArtifact({
    owner: 'namespace-b',
    contentType: 'text/plain',
    data: encoder.encode('ephemeral'),
    retentionDays: 0,
  })
  assert.equal(await store.purgeArtifact(metadata.id, 'retention-expired'), true)
  assert.equal((await store.getArtifactMetadata(metadata.id)).availabilityStatus, 'purged')
  assert.equal(await store.getSignedUrl(metadata.id), null)
})

await scenario('store returns null when the blob client cannot presign', async () => {
  const { client, store } = createStore()
  const metadata = await store.putArtifact({
    owner: 'namespace-c',
    contentType: 'text/plain',
    data: encoder.encode('no-presign'),
    retentionDays: 30,
  })
  client.getSignedUrl = undefined
  assert.equal(await store.getSignedUrl(metadata.id), null)
})

await scenario('PostgresArtifactStore exposes getSignedUrl as an instance method', async () => {
  const { store } = createStore()
  assert.equal(typeof PostgresArtifactStore.prototype.getSignedUrl, 'function')
  assert.equal(store instanceof PostgresArtifactStore, true)
})

// ---------------------------------------------------------------------------

console.log(`\n${passed} passed, ${failed} failed`)
if (failed > 0) process.exit(1)
