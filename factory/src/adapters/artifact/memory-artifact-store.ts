/**
 * In-memory {@link ArtifactStore} adapter.
 *
 * Reference implementation used by the offline tests and by local fast
 * mocking. It models the same lifecycle guarantees as the S3 adapter —
 * content addressing, retention window, legal hold and purge — without any
 * I/O, so both adapters share the pure governance helpers exported here.
 */

import type {
  ArtifactMetadata,
  ArtifactRetentionStatus,
  ArtifactStore,
  OpenArtifactResult,
  PutArtifactParams,
} from '../../ports/artifact/artifact-store.js'
import { computeArtifactHash, createArtifactId } from './artifact-hash.js'

const MILLISECONDS_PER_DAY = 24 * 60 * 60 * 1000

/** Normalises `Buffer | Uint8Array` input to a plain byte view. */
export function toArtifactBytes(data: Buffer | Uint8Array): Uint8Array {
  return data instanceof Uint8Array ? data : new Uint8Array(data)
}

/** Parses a `Date | string` timestamp into epoch milliseconds. */
export function artifactTimestamp(value: Date | string): number {
  return value instanceof Date ? value.getTime() : Date.parse(value)
}

/** Computes the absolute retention deadline, when a window was requested. */
export function computeRetentionUntil(createdAt: Date, retentionDays?: number): string | undefined {
  if (retentionDays === undefined) return undefined
  return new Date(createdAt.getTime() + retentionDays * MILLISECONDS_PER_DAY).toISOString()
}

/** Evaluates whether the retention window is still open at `now`. */
export function isRetentionActive(metadata: ArtifactMetadata, now: Date): boolean {
  if (metadata.retentionUntil === undefined) return false
  return artifactTimestamp(metadata.retentionUntil) > now.getTime()
}

/** Recomputes the derived retention status of an artifact at `now`. */
export function computeRetentionStatus(metadata: ArtifactMetadata, now: Date): ArtifactRetentionStatus {
  return isRetentionActive(metadata, now) ? 'active' : 'expired'
}

/** Returns a copy of the metadata with its derived retention status refreshed. */
export function refreshArtifactMetadata(metadata: ArtifactMetadata, now: Date): ArtifactMetadata {
  return { ...metadata, retentionStatus: computeRetentionStatus(metadata, now) }
}

/**
 * Whether destruction is allowed at `now`: the payload must still be
 * available, no legal hold may be active, and the retention window must have
 * closed (or never been opened).
 */
export function isArtifactDestroyable(metadata: ArtifactMetadata, now: Date): boolean {
  if (metadata.availabilityStatus === 'purged') return false
  if (metadata.legalHold) return false
  return !isRetentionActive(metadata, now)
}

/** Inputs needed to derive the initial metadata of a stored artifact. */
export interface BuildArtifactMetadataParams {
  /** Stable identifier of the artifact. */
  id: string
  /** Owning principal of the artifact. */
  owner: string
  /** IANA media type of the payload. */
  contentType: string
  /** Raw payload bytes, used for content addressing and sizing. */
  data: Uint8Array
  /** Optional retention window, in days, starting at `now`. */
  retentionDays?: number
  /** Creation instant. */
  now: Date
}

/** Derives the initial metadata of a freshly stored artifact. */
export function buildArtifactMetadata(params: BuildArtifactMetadataParams): ArtifactMetadata {
  const retentionUntil = computeRetentionUntil(params.now, params.retentionDays)
  const metadata: ArtifactMetadata = {
    id: params.id,
    owner: params.owner,
    hash: computeArtifactHash(params.data),
    size: params.data.byteLength,
    contentType: params.contentType,
    availabilityStatus: 'available',
    retentionStatus: retentionUntil !== undefined ? 'active' : 'expired',
    legalHold: false,
    createdAt: params.now.toISOString(),
    ...(params.retentionDays !== undefined ? { retentionDays: params.retentionDays } : {}),
    ...(retentionUntil !== undefined ? { retentionUntil } : {}),
  }
  return refreshArtifactMetadata(metadata, params.now)
}

interface MemoryArtifactEntry {
  metadata: ArtifactMetadata
  data: Uint8Array
}

/** Options accepted by {@link MemoryArtifactStore}. */
export interface MemoryArtifactStoreOptions {
  /** Chunk size used when streaming payloads, in bytes. */
  chunkSize?: number
  /** Injectable clock, primarily for deterministic tests. */
  now?: () => Date
}

async function* streamArtifactBytes(data: Uint8Array, chunkSize: number): AsyncIterable<Uint8Array> {
  const size = Math.max(1, chunkSize)
  for (let offset = 0; offset < data.byteLength; offset += size) {
    yield data.subarray(offset, Math.min(offset + size, data.byteLength))
  }
}

/** In-memory implementation of the {@link ArtifactStore} port. */
export class MemoryArtifactStore implements ArtifactStore {
  readonly #entries = new Map<string, MemoryArtifactEntry>()
  readonly #chunkSize: number
  readonly #now: () => Date

  constructor(options: MemoryArtifactStoreOptions = {}) {
    this.#chunkSize = options.chunkSize ?? 64 * 1024
    this.#now = options.now ?? (() => new Date())
  }

  async putArtifact(params: PutArtifactParams): Promise<ArtifactMetadata> {
    const now = this.#now()
    const data = toArtifactBytes(params.data)
    const metadata = buildArtifactMetadata({
      id: createArtifactId(),
      owner: params.owner,
      contentType: params.contentType,
      data,
      now,
      ...(params.retentionDays !== undefined ? { retentionDays: params.retentionDays } : {}),
    })
    this.#entries.set(metadata.id, { metadata, data: Uint8Array.from(data) })
    return metadata
  }

  async getArtifactMetadata(artifactId: string): Promise<ArtifactMetadata | null> {
    const entry = this.#entries.get(artifactId)
    if (!entry) return null
    return refreshArtifactMetadata(entry.metadata, this.#now())
  }

  async openArtifact(artifactId: string): Promise<OpenArtifactResult | null> {
    const entry = this.#entries.get(artifactId)
    if (!entry) return null
    if (entry.metadata.availabilityStatus === 'purged') return null
    return {
      stream: streamArtifactBytes(entry.data, this.#chunkSize),
      metadata: refreshArtifactMetadata(entry.metadata, this.#now()),
    }
  }

  async deleteArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'deleted')
  }

  async purgeArtifact(artifactId: string, reason?: string): Promise<boolean> {
    return this.#destroy(artifactId, reason ?? 'retention-expired')
  }

  async setLegalHold(artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata | null> {
    const entry = this.#entries.get(artifactId)
    if (!entry) return null
    const now = this.#now()
    const { legalHoldReason: _previousReason, legalHoldSetAt: _previousSetAt, ...rest } = entry.metadata
    const updated: ArtifactMetadata = legalHold
      ? {
          ...rest,
          legalHold: true,
          ...(reason !== undefined ? { legalHoldReason: reason } : {}),
          legalHoldSetAt: now.toISOString(),
        }
      : { ...rest, legalHold: false }
    const refreshed = refreshArtifactMetadata(updated, now)
    entry.metadata = refreshed
    return refreshed
  }

  #destroy(artifactId: string, reason: string): Promise<boolean> {
    const entry = this.#entries.get(artifactId)
    if (!entry) return Promise.resolve(false)
    const now = this.#now()
    if (!isArtifactDestroyable(entry.metadata, now)) return Promise.resolve(false)
    entry.metadata = refreshArtifactMetadata(
      {
        ...entry.metadata,
        availabilityStatus: 'purged',
        purgedAt: now.toISOString(),
        purgeReason: reason,
      },
      now
    )
    entry.data = new Uint8Array(0)
    return Promise.resolve(true)
  }
}

/** Wires an in-memory artifact store. */
export function createMemoryArtifactStore(options?: MemoryArtifactStoreOptions): MemoryArtifactStore {
  return new MemoryArtifactStore(options)
}
