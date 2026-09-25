/**
 * ArtifactStore port.
 *
 * A storage-agnostic contract for the binary artifacts produced and consumed
 * by the Factory (agent briefs, structured results, oracle dumps, evidence
 * payloads…). The port carries no I/O: adapters implement it over an in-memory
 * map, a filesystem, or an S3/MinIO bucket.
 *
 * The metadata deliberately separates three *orthogonal* concerns so a single
 * artifact can be, for example, still retained (`retentionStatus: 'active'`),
 * under legal hold (`legalHold: true`) and yet no longer readable by the
 * runtime (`availabilityStatus: 'purged'`):
 *
 *   - `availabilityStatus` — is the payload physically readable?
 *   - `retentionStatus`    — is the compliance retention window still open?
 *   - `legalHold`          — is destruction forbidden by an explicit hold?
 */

/** Whether the artifact payload can still be opened by the runtime. */
export type ArtifactAvailabilityStatus = 'available' | 'purged' | 'archived'

/** Whether the compliance retention window is still open. */
export type ArtifactRetentionStatus = 'active' | 'expired'

/** Metadata describing a stored artifact. */
export interface ArtifactMetadata {
  /** Stable, content-independent identifier of the artifact. */
  id: string
  /** Owning principal (namespace, agent or user) of the artifact. */
  owner: string
  /** Content address: `sha256:<hex>` over the payload bytes. */
  hash: string
  /** Payload size in bytes. */
  size: number
  /** IANA media type of the payload. */
  contentType: string
  /** Requested retention window, in days, from creation. */
  retentionDays?: number
  /** Current physical availability of the payload. */
  availabilityStatus: ArtifactAvailabilityStatus
  /** Current compliance retention state. */
  retentionStatus: ArtifactRetentionStatus
  /** Whether an explicit legal hold currently forbids destruction. */
  legalHold: boolean
  /** Creation timestamp. */
  createdAt: Date | string
  /** Absolute end of the retention window, when a retention was requested. */
  retentionUntil?: Date | string
  /** Destruction timestamp, set when the payload is purged. */
  purgedAt?: Date | string
  /** Human-readable justification of the destruction. */
  purgeReason?: string
  /** Human-readable justification of the legal hold. */
  legalHoldReason?: string
  /** Timestamp at which the legal hold was last set. */
  legalHoldSetAt?: Date | string
}

/** Input accepted by {@link ArtifactStore.putArtifact}. */
export interface PutArtifactParams {
  /** Owning principal of the artifact. */
  owner: string
  /** IANA media type of the payload. */
  contentType: string
  /** Raw payload bytes. */
  data: Buffer | Uint8Array
  /** Optional retention window, in days, starting at creation. */
  retentionDays?: number
}

/** Result returned by {@link ArtifactStore.openArtifact}. */
export interface OpenArtifactResult {
  /** Payload bytes as an async iterable of chunks. */
  stream: AsyncIterable<Uint8Array>
  /** Metadata of the opened artifact. */
  metadata: ArtifactMetadata
}

/**
 * Storage-agnostic artifact store.
 *
 * Implementations must guarantee that:
 *   - `putArtifact` stores the payload and returns its metadata;
 *   - `openArtifact` returns `null` for unknown or purged artifacts;
 *   - destruction (`deleteArtifact` / `purgeArtifact`) is refused while a legal
 *     hold is active;
 *   - `purgeArtifact` is a no-op while the retention window is still open.
 */
export interface ArtifactStore {
  /** Stores a payload and returns its metadata. */
  putArtifact(params: PutArtifactParams): Promise<ArtifactMetadata>
  /** Returns the metadata of an artifact, or `null` when unknown. */
  getArtifactMetadata(artifactId: string): Promise<ArtifactMetadata | null>
  /** Opens the payload of an available artifact, or `null` when unavailable. */
  openArtifact(artifactId: string): Promise<OpenArtifactResult | null>
  /** Deletes an artifact, unless a legal hold forbids it. */
  deleteArtifact(artifactId: string, reason?: string): Promise<boolean>
  /** Sets or releases the legal hold of an artifact. */
  setLegalHold(artifactId: string, legalHold: boolean, reason?: string): Promise<ArtifactMetadata | null>
  /** Purges an expired-retention artifact, unless a legal hold forbids it. */
  purgeArtifact(artifactId: string, reason?: string): Promise<boolean>
}
