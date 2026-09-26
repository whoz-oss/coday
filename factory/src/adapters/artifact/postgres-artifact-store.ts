/**
 * Composed PostgreSQL {@link ArtifactStore} adapter.
 *
 * The store splits the two storage concerns of an artifact:
 *
 *   - the immutable binary payload lives in object storage (S3 / MinIO),
 *     content-addressed under `objects/<sha256-hex>`;
 *   - the authoritative metadata (statuses, retention window, legal hold and
 *     purge record) lives in the PostgreSQL `artifacts` row.
 *
 * `putArtifact` follows the *upload-then-commit* protocol (Jalon B Amendment 5):
 * the payload is uploaded to a staging key `uploads/<id>.part`, promoted to its
 * content-addressed key, verified in object storage, and only then is the
 * PostgreSQL row committed. A failed verification or commit therefore leaves no
 * authoritative row — only a reclaimable orphan in object storage.
 *
 * Every governance decision (`getArtifactMetadata`, `openArtifact`,
 * `deleteArtifact`, `purgeArtifact`, `setLegalHold`) reads the PostgreSQL row as
 * the source of truth and re-uses the shared pure governance helpers of the
 * in-memory adapter, so both implementations enforce identical rules.
 */

import type {
  ArtifactMetadata,
  ArtifactStore,
  OpenArtifactResult,
  PutArtifactParams,
} from '../../ports/artifact/artifact-store.js'
import { ARTIFACT_HASH_PREFIX, computeArtifactHash, createArtifactId } from './artifact-hash.js'
import {
  buildArtifactMetadata,
  isArtifactDestroyable,
  refreshArtifactMetadata,
  toArtifactBytes,
} from './memory-artifact-store.js'
import type { SqlClient } from '../persistence/sql/db.js'
import {
  createSqlArtifactMetadataRepository,
  type SqlArtifactMetadataRepository,
  type SqlArtifactMetadataRepositoryOptions,
} from '../persistence/sql/sql-artifact-metadata-repository.js'

const DEFAULT_UPLOAD_PREFIX = 'uploads'
const DEFAULT_OBJECT_PREFIX = 'objects'

/** Default retention window, in days, when none is requested nor configured. */
export const DEFAULT_ARTIFACT_RETENTION_DAYS = 90

/** Environment variable overriding the default retention window. */
export const ARTIFACT_RETENTION_DAYS_ENV = 'ARTIFACT_RETENTION_DAYS'

/**
 * Structural object-storage contract used by the store. The concrete
 * {@link S3ObjectClient} satisfies it, as does the offline fake used by tests.
 */
export interface ArtifactBlobClient {
  /** Stores an object at `key`. */
  putObject(key: string, body: Uint8Array, contentType?: string): Promise<void>
  /** Server-side copies `sourceKey` to `destinationKey`. */
  copyObject(sourceKey: string, destinationKey: string): Promise<void>
  /** Reads an object, or returns `null` when it does not exist. */
  getObject(key: string): Promise<{ stream: AsyncIterable<Uint8Array> } | null>
  /** Deletes an object. Returns `true` when a deletion happened. */
  deleteObject(key: string): Promise<boolean>
  /** Optional existence probe used to verify an object before commit. */
  headObject?(key: string): Promise<boolean>
  /** Optional listing used to reclaim orphaned staging uploads. */
  listObjectKeys?(prefix: string): Promise<string[]>
  /** Optional SigV4 presigner for readable payloads. */
  getSignedUrl?(key: string, options?: { expiresInSeconds?: number; now?: Date }): string
}

/** Configuration of {@link PostgresArtifactStore}. */
export interface PostgresArtifactStoreConfig {
  /** Object-storage client for the binary payloads. */
  client: ArtifactBlobClient
  /** Pre-built metadata repository; takes precedence over `sqlClient`. */
  repository?: SqlArtifactMetadataRepository
  /** SQL client used to build a metadata repository when none is provided. */
  sqlClient?: SqlClient
  /** Tenant / hierarchy scoping of the metadata repository. */
  organizationId?: string
  workstreamId?: string
  namespaceId?: string
  workflowId?: string
  /** Prefix of transient staging uploads (default `uploads`). */
  uploadPrefix?: string
  /** Prefix of content-addressed payloads (default `objects`). */
  objectPrefix?: string
  /** Explicit default retention window, overriding the environment. */
  retentionDays?: number
  /** Environment consulted for {@link ARTIFACT_RETENTION_DAYS_ENV}. */
  env?: NodeJS.ProcessEnv
  /** Injectable clock, primarily for deterministic tests. */
  now?: () => Date
}

/** Composed object-storage + PostgreSQL implementation of {@link ArtifactStore}. */
export class PostgresArtifactStore implements ArtifactStore {
  readonly #client: ArtifactBlobClient
  readonly #repository: SqlArtifactMetadataRepository
  readonly #uploadPrefix: string
  readonly #objectPrefix: string
  readonly #retentionDays: number | undefined
  readonly #env: NodeJS.ProcessEnv
  readonly #now: () => Date

  constructor(config: PostgresArtifactStoreConfig) {
    this.#client = config.client
    if (config.repository) {
      this.#repository = config.repository
    } else if (config.sqlClient) {
      const options: SqlArtifactMetadataRepositoryOptions = {
        ...(config.organizationId !== undefined ? { organizationId: config.organizationId } : {}),
        ...(config.workstreamId !== undefined ? { workstreamId: config.workstreamId } : {}),
        ...(config.namespaceId !== undefined ? { namespaceId: config.namespaceId } : {}),
        ...(config.workflowId !== undefined ? { workflowId: config.workflowId } : {}),
      }
      this.#repository = createSqlArtifactMetadataRepository(config.sqlClient, options)
    } else {
      throw new Error('PostgresArtifactStore requires a `repository` or a `sqlClient`')
    }
    this.#uploadPrefix = config.uploadPrefix ?? DEFAULT_UPLOAD_PREFIX
    this.#objectPrefix = config.objectPrefix ?? DEFAULT_OBJECT_PREFIX
    this.#retentionDays = config.retentionDays
    this.#env = config.env ?? process.env
    this.#now = config.now ?? (() => new Date())
  }

  async putArtifact(params: PutArtifactParams): Promise<ArtifactMetadata> {
    const now = this.#now()
    const data = toArtifactBytes(params.data)
    const id = createArtifactId()
    const hash = computeArtifactHash(data)
    const retentionDays = params.retentionDays ?? this.#defaultRetentionDays()
    const metadata = buildArtifactMetadata({
      id,
      owner: params.owner,
      contentType: params.contentType,
      data,
      retentionDays,
      now,
    })
    const stagingKey = this.#stagingKey(id)
    const contentKey = this.#contentKey(hash)

    // 1. Upload the payload to the staging key.
    await this.#client.putObject(stagingKey, Uint8Array.from(data), 'application/octet-stream')
    // 2. Promote the staging object to its immutable content-addressed key.
    await this.#client.copyObject(stagingKey, contentKey)
    // 3. Verify the object exists in object storage before the commit.
    if (!(await this.#objectExists(contentKey))) {
      throw new Error('ARTIFACT_OBJECT_VERIFICATION_FAILED')
    }
    // 4. Commit the authoritative metadata row in PostgreSQL.
    await this.#repository.saveMetadata(metadata, contentKey)
    // 5. Best-effort removal of the staging object.
    await this.#bestEffortDelete(stagingKey)
    return metadata
  }

  async getArtifactMetadata(artifactId: string): Promise<ArtifactMetadata | null> {
    const metadata = await this.#repository.getMetadata(artifactId)
    if (!metadata) return null
    return refreshArtifactMetadata(metadata, this.#now())
  }

  async openArtifact(artifactId: string): Promise<OpenArtifactResult | null> {
    const record = await this.#repository.getMetadataAndStorageKey(artifactId)
    if (!record) return null
    const metadata = refreshArtifactMetadata(record.metadata, this.#now())
    if (metadata.availabilityStatus === 'purged') return null
    const object = await this.#client.getObject(record.storageKey)
    if (!object) return null
    return { stream: object.stream, metadata }
  }

  async deleteArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'deleted')
  }

  /**
   * Returns a pre-signed URL granting temporary read access to an artifact's
   * payload, or `null` when the artifact is unknown, no longer readable
   * (`availabilityStatus !== 'available'`) or when the underlying blob client
   * cannot presign.
   *
   * PostgreSQL metadata stays authoritative: the storage key and availability
   * are read from the repository row, never inferred from the caller.
   */
  async getSignedUrl(artifactId: string, options?: { expiresInSeconds?: number; now?: Date }): Promise<string | null> {
    if (typeof this.#client.getSignedUrl !== 'function') return null
    const record = await this.#repository.getMetadataAndStorageKey(artifactId)
    if (!record) return null
    const metadata = refreshArtifactMetadata(record.metadata, options?.now ?? this.#now())
    if (metadata.availabilityStatus !== 'available') return null
    return this.#client.getSignedUrl(record.storageKey, options)
  }

  async purgeArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'retention-expired')
  }

  async setLegalHold(artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata | null> {
    const now = this.#now()
    return this.#repository.updateLegalHold(artifactId, legalHold, reason, now)
  }

  /**
   * Deletes staging objects left behind by interrupted uploads. Returns the
   * reclaimed keys. Mirrors {@link S3ArtifactStore.collectOrphanedUploads}.
   */
  async collectOrphanedUploads(): Promise<string[]> {
    if (typeof this.#client.listObjectKeys !== 'function') return []
    const keys = await this.#client.listObjectKeys(`${this.#uploadPrefix}/`)
    const reclaimed: string[] = []
    for (const key of keys) {
      if (await this.#bestEffortDelete(key)) reclaimed.push(key)
    }
    return reclaimed
  }

  async #destroy(artifactId: string, reason: string): Promise<boolean> {
    const record = await this.#repository.getMetadataAndStorageKey(artifactId)
    if (!record) return false
    const now = this.#now()
    const metadata = refreshArtifactMetadata(record.metadata, now)
    if (!isArtifactDestroyable(metadata, now)) return false
    const purged = await this.#repository.purgeArtifact(artifactId, reason, now)
    if (!purged) return false
    await this.#bestEffortDelete(record.storageKey)
    return true
  }

  #defaultRetentionDays(): number {
    if (this.#retentionDays !== undefined) return this.#retentionDays
    const parsed = Number.parseInt(this.#env[ARTIFACT_RETENTION_DAYS_ENV] ?? '', 10)
    return Number.isFinite(parsed) && parsed >= 0 ? parsed : DEFAULT_ARTIFACT_RETENTION_DAYS
  }

  async #objectExists(key: string): Promise<boolean> {
    if (typeof this.#client.headObject === 'function') return this.#client.headObject(key)
    const object = await this.#client.getObject(key)
    return object !== null
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
}

/** Wires a composed object-storage + PostgreSQL artifact store. */
export function createPostgresArtifactStore(config: PostgresArtifactStoreConfig): PostgresArtifactStore {
  return new PostgresArtifactStore(config)
}
