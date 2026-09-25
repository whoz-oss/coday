/**
 * S3 / MinIO {@link ArtifactStore} adapter.
 *
 * Key layout (the prefixes are configurable):
 *
 *   - `objects/<sha256-hex>`   content-addressed payload, immutable;
 *   - `metadata/<id>.json`     authoritative metadata, the commit marker;
 *   - `uploads/<id>.part`      transient staging object, deleted at commit.
 *
 * `putArtifact` follows an *upload-then-commit* protocol: the payload is first
 * uploaded to a staging key, copied to its content-addressed key, and only then
 * is the metadata object written. A crash before the metadata write leaves an
 * orphaned staging object, reclaimed by {@link S3ArtifactStore.collectOrphanedUploads}.
 *
 * The governance helpers are shared with the in-memory adapter so both enforce
 * exactly the same retention and legal-hold semantics.
 */

import type {
  ArtifactMetadata,
  ArtifactStore,
  OpenArtifactResult,
  PutArtifactParams,
} from '../../ports/artifact/artifact-store.js'
import { computeArtifactHash, createArtifactId, ARTIFACT_HASH_PREFIX } from './artifact-hash.js'
import {
  buildArtifactMetadata,
  isArtifactDestroyable,
  refreshArtifactMetadata,
  toArtifactBytes,
} from './memory-artifact-store.js'
import { S3ObjectClient, type S3ObjectClientConfig } from './s3-object-client.js'

const DEFAULT_UPLOAD_PREFIX = 'uploads'
const DEFAULT_OBJECT_PREFIX = 'objects'
const DEFAULT_METADATA_PREFIX = 'metadata'

/** Tunable options for {@link S3ArtifactStore}. */
export interface S3ArtifactStoreOptions {
  /** Pre-built object client; when omitted one is created from the config. */
  client?: S3ObjectClient
  /** Prefix of transient staging objects. */
  uploadPrefix?: string
  /** Prefix of content-addressed payload objects. */
  objectPrefix?: string
  /** Prefix of metadata objects. */
  metadataPrefix?: string
  /** Injectable clock, primarily for tests. */
  now?: () => Date
}

/** Full configuration of {@link S3ArtifactStore}. */
export type S3ArtifactStoreConfig = S3ObjectClientConfig & S3ArtifactStoreOptions

async function readAllBytes(stream: AsyncIterable<Uint8Array>): Promise<Uint8Array> {
  const chunks: Uint8Array[] = []
  let total = 0
  for await (const chunk of stream) {
    chunks.push(chunk)
    total += chunk.byteLength
  }
  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  return bytes
}

function encodeJson(value: unknown): Uint8Array {
  return Buffer.from(JSON.stringify(value), 'utf8')
}

/** S3 / MinIO implementation of the {@link ArtifactStore} port. */
export class S3ArtifactStore implements ArtifactStore {
  readonly #client: S3ObjectClient
  readonly #uploadPrefix: string
  readonly #objectPrefix: string
  readonly #metadataPrefix: string
  readonly #now: () => Date

  constructor(config: S3ArtifactStoreConfig) {
    this.#client = config.client ?? new S3ObjectClient(config)
    this.#uploadPrefix = config.uploadPrefix ?? DEFAULT_UPLOAD_PREFIX
    this.#objectPrefix = config.objectPrefix ?? DEFAULT_OBJECT_PREFIX
    this.#metadataPrefix = config.metadataPrefix ?? DEFAULT_METADATA_PREFIX
    this.#now = config.now ?? (() => new Date())
  }

  async putArtifact(params: PutArtifactParams): Promise<ArtifactMetadata> {
    const now = this.#now()
    const data = toArtifactBytes(params.data)
    const id = createArtifactId()
    const hash = computeArtifactHash(data)
    const metadata = buildArtifactMetadata({
      id,
      owner: params.owner,
      contentType: params.contentType,
      data,
      now,
      ...(params.retentionDays !== undefined ? { retentionDays: params.retentionDays } : {}),
    })

    await this.#client.putObject(this.#stagingKey(id), Uint8Array.from(data), 'application/octet-stream')
    await this.#client.copyObject(this.#stagingKey(id), this.#contentKey(hash))
    await this.#client.putObject(this.#metadataKey(id), encodeJson(metadata), 'application/json')
    await this.#bestEffortDelete(this.#stagingKey(id))
    return metadata
  }

  async getArtifactMetadata(artifactId: string): Promise<ArtifactMetadata | null> {
    const response = await this.#client.getObject(this.#metadataKey(artifactId))
    if (!response) return null
    const bytes = await readAllBytes(response.stream)
    const metadata = JSON.parse(Buffer.from(bytes).toString('utf8')) as ArtifactMetadata
    return refreshArtifactMetadata(metadata, this.#now())
  }

  async openArtifact(artifactId: string): Promise<OpenArtifactResult | null> {
    const metadata = await this.getArtifactMetadata(artifactId)
    if (!metadata) return null
    if (metadata.availabilityStatus === 'purged') return null
    const object = await this.#client.getObject(this.#contentKey(metadata.hash))
    if (!object) return null
    return { stream: object.stream, metadata }
  }

  async deleteArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'deleted')
  }

  async purgeArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'retention-expired')
  }

  async setLegalHold(artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata | null> {
    const metadata = await this.getArtifactMetadata(artifactId)
    if (!metadata) return null
    const now = this.#now()
    const { legalHoldReason: _previousReason, legalHoldSetAt: _previousSetAt, ...rest } = metadata
    const updated: ArtifactMetadata = legalHold
      ? {
          ...rest,
          legalHold: true,
          ...(reason !== undefined ? { legalHoldReason: reason } : {}),
          legalHoldSetAt: now.toISOString(),
        }
      : { ...rest, legalHold: false }
    const refreshed = refreshArtifactMetadata(updated, now)
    await this.#client.putObject(this.#metadataKey(artifactId), encodeJson(refreshed), 'application/json')
    return refreshed
  }

  /**
   * Deletes staging objects left behind by interrupted uploads. Returns the
   * keys that were reclaimed.
   */
  async collectOrphanedUploads(): Promise<string[]> {
    const keys = await this.#client.listObjectKeys(`${this.#uploadPrefix}/`)
    const reclaimed: string[] = []
    for (const key of keys) {
      if (await this.#bestEffortDelete(key)) reclaimed.push(key)
    }
    return reclaimed
  }

  async #destroy(artifactId: string, reason: string): Promise<boolean> {
    const metadata = await this.getArtifactMetadata(artifactId)
    if (!metadata) return false
    const now = this.#now()
    if (!isArtifactDestroyable(metadata, now)) return false
    const purged = refreshArtifactMetadata(
      {
        ...metadata,
        availabilityStatus: 'purged',
        purgedAt: now.toISOString(),
        purgeReason: reason,
      },
      now
    )
    await this.#client.putObject(this.#metadataKey(artifactId), encodeJson(purged), 'application/json')
    await this.#bestEffortDelete(this.#contentKey(metadata.hash))
    return true
  }

  async #bestEffortDelete(key: string): Promise<boolean> {
    try {
      return await this.#client.deleteObject(key)
    } catch {
      return false
    }
  }

  #stagingKey(id: string): string {
    return `${this.#uploadPrefix}/${id}.part`
  }

  #contentKey(hash: string): string {
    const digest = hash.startsWith(`${ARTIFACT_HASH_PREFIX}:`) ? hash.slice(ARTIFACT_HASH_PREFIX.length + 1) : hash
    return `${this.#objectPrefix}/${digest}`
  }

  #metadataKey(id: string): string {
    return `${this.#metadataPrefix}/${id}.json`
  }
}

/** Wires an S3 / MinIO artifact store. */
export function createS3ArtifactStore(config: S3ArtifactStoreConfig): S3ArtifactStore {
  return new S3ArtifactStore(config)
}
