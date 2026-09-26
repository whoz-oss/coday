/**
 * Admin governance use cases for factory artifacts (B5-T2b).
 *
 * Three *explicit* admin operations, always triggered by a human/operator —
 * never by a timer, never automatically:
 *
 *   1. {@link purgeArtifactAdmin} — destroys an artifact whose compliance
 *      retention window has expired, and only then. An active retention window
 *      or an active legal hold refuses the destruction.
 *   2. {@link setLegalHoldAdmin} — places or releases an explicit legal hold,
 *      the authoritative « do not destroy » switch.
 *   3. {@link collectAndAuditGarbage} — reclaims orphaned staging uploads and
 *      audits the object store against the authoritative metadata rows, so the
 *      two can never silently diverge.
 *
 * The `ArtifactStore` port (frozen) carries no listing, so the GC use case
 * reconciles object storage against an *injected* metadata lister: in
 * production the composition root wires the SQL repository; the port itself is
 * never widened. The module is pure orchestration — it owns no I/O and no
 * state, and is safe to bundle into `runtime/factory-operational.mjs`.
 */

import type {
  ArtifactAvailabilityStatus,
  ArtifactMetadata,
  ArtifactStore,
} from '../../ports/artifact/artifact-store.js'
import type { ArtifactBlobClient } from '../../adapters/artifact/postgres-artifact-store.js'

/** Default prefix of transient staging uploads. */
export const ARTIFACT_ADMIN_UPLOAD_PREFIX = 'uploads'

/** Default prefix of content-addressed payload blobs. */
export const ARTIFACT_ADMIN_OBJECT_PREFIX = 'objects'

/** Terminal statuses of an admin purge attempt. */
export type ArtifactAdminPurgeStatus = 'purged' | 'NOT_FOUND' | 'LEGAL_HOLD_ACTIVE' | 'RETENTION_ACTIVE'

/** Structured outcome of {@link purgeArtifactAdmin}. */
export type ArtifactAdminPurgeResult =
  | {
      success: true
      artifactId: string
      reason: string
      status: 'purged'
      metadata: ArtifactMetadata
    }
  | {
      success: false
      artifactId: string
      reason: string
      status: 'NOT_FOUND' | 'LEGAL_HOLD_ACTIVE' | 'RETENTION_ACTIVE'
    }

/** Kinds of blob-store / metadata divergence audited by the GC use case. */
export type ArtifactGcAnomalyType = 'blob_without_pg_row' | 'pg_purged_or_missing_blob'

/** A single divergence between object storage and the authoritative rows. */
export interface ArtifactGcAnomaly {
  /** Anomaly classification. */
  type: ArtifactGcAnomalyType
  /** Artifact id, when the anomaly originates from a metadata row. */
  artifactId?: string
  /** Object-storage key involved in the divergence. */
  storageKey?: string
  /** Human-readable explanation, for operators and audit logs. */
  details: string
}

/**
 * Authoritative metadata row projection used for reconciliation. The frozen SQL
 * repository is wrapped by the caller into this minimal shape.
 */
export interface ArtifactGcMetadataRow {
  artifactId: string
  storageKey: string
  availabilityStatus: ArtifactAvailabilityStatus
}

/** Options of {@link collectAndAuditGarbage}. */
export interface ArtifactGarbageCollectionOptions {
  /** Prefix of transient staging uploads (default {@link ARTIFACT_ADMIN_UPLOAD_PREFIX}). */
  uploadPrefix?: string
  /** Prefix of content-addressed payload blobs (default {@link ARTIFACT_ADMIN_OBJECT_PREFIX}). */
  objectPrefix?: string
  /**
   * Lists the authoritative metadata rows to reconcile against object storage.
   * Injected because the frozen `ArtifactStore` port and SQL repository expose
   * no listing; production wires the repository read here.
   */
  listMetadata?: () => Promise<ArtifactGcMetadataRow[]>
  /** Injectable clock, primarily for a deterministic report timestamp. */
  now?: () => Date
}

/** Structured report returned by {@link collectAndAuditGarbage}. */
export interface ArtifactGarbageCollectionReport {
  /** Staging keys reclaimed during this run. */
  reclaimedStagingKeys: string[]
  /** Detected blob-store / metadata divergences. */
  anomalies: ArtifactGcAnomaly[]
  /** Content-addressed blob keys that were scanned. */
  scannedBlobKeys: string[]
  /** Number of authoritative metadata rows considered. */
  scannedMetadataRows: number
  /** Instant at which the GC run completed (ISO 8601). */
  timestamp: string
}

/** Error raised by an admin use case when the target artifact is unknown. */
export class ArtifactAdminError extends Error {
  /** Stable machine-readable error code. */
  readonly code: string
  /** Transport-agnostic HTTP status suggestion. */
  readonly statusCode: number

  constructor(code: string, message: string, statusCode: number) {
    super(message)
    this.name = 'ArtifactAdminError'
    this.code = code
    this.statusCode = statusCode
  }
}

/** Structural capability: reclaiming a store's own orphaned staging uploads. */
interface OrphanUploadCollector {
  collectOrphanedUploads(): Promise<string[]>
}

/** True when the store exposes its staging-upload reclamation hook. */
function isOrphanUploadCollector(store: unknown): store is OrphanUploadCollector {
  return (
    !!store &&
    typeof store === 'object' &&
    typeof (store as { collectOrphanedUploads?: unknown }).collectOrphanedUploads === 'function'
  )
}

/**
 * Explicit admin purge of an artifact whose retention window has expired.
 *
 * Refuses — without throwing — when the artifact is unknown
 * (`NOT_FOUND`), under an active legal hold (`LEGAL_HOLD_ACTIVE`) or still
 * within its retention window (`RETENTION_ACTIVE`). This is a governance
 * command: it is never scheduled, never triggered by a timer.
 */
export async function purgeArtifactAdmin(
  store: ArtifactStore,
  artifactId: string,
  reason: string
): Promise<ArtifactAdminPurgeResult> {
  const metadata = await store.getArtifactMetadata(artifactId)
  if (!metadata) {
    return { success: false, artifactId, reason, status: 'NOT_FOUND' }
  }
  if (metadata.legalHold) {
    return { success: false, artifactId, reason, status: 'LEGAL_HOLD_ACTIVE' }
  }
  if (metadata.retentionStatus === 'active') {
    return { success: false, artifactId, reason, status: 'RETENTION_ACTIVE' }
  }

  const purged = await store.purgeArtifact(artifactId, reason)
  if (!purged) {
    // The store refused the destruction (a concurrent mutation or a stale read).
    // Re-read to classify the refusal precisely.
    const latest = await store.getArtifactMetadata(artifactId)
    if (!latest) return { success: false, artifactId, reason, status: 'NOT_FOUND' }
    if (latest.legalHold) return { success: false, artifactId, reason, status: 'LEGAL_HOLD_ACTIVE' }
    return { success: false, artifactId, reason, status: 'RETENTION_ACTIVE' }
  }

  const refreshed = await store.getArtifactMetadata(artifactId)
  const metadataAfterPurge: ArtifactMetadata = refreshed ?? { ...metadata, availabilityStatus: 'purged' }
  return { success: true, artifactId, reason, status: 'purged', metadata: metadataAfterPurge }
}

/**
 * Explicit admin action placing (`legalHold: true`) or releasing
 * (`legalHold: false`) a legal hold. Throws {@link ArtifactAdminError}
 * (`ARTIFACT_NOT_FOUND`) when the artifact does not exist.
 */
export async function setLegalHoldAdmin(
  store: ArtifactStore,
  artifactId: string,
  legalHold: boolean,
  reason?: string
): Promise<ArtifactMetadata> {
  const updated = await store.setLegalHold(artifactId, legalHold, reason)
  if (!updated) {
    throw new ArtifactAdminError('ARTIFACT_NOT_FOUND', `Artifact ${artifactId} not found`, 404)
  }
  return updated
}

/** Reclaims staging uploads, delegating to the store hook when available. */
async function reclaimOrphanedUploads(
  store: ArtifactStore,
  blobClient: ArtifactBlobClient,
  uploadPrefix: string
): Promise<string[]> {
  if (isOrphanUploadCollector(store)) {
    return store.collectOrphanedUploads()
  }
  if (typeof blobClient.listObjectKeys !== 'function') return []
  const keys = await blobClient.listObjectKeys(`${uploadPrefix}/`)
  const reclaimed: string[] = []
  for (const key of keys) {
    const deleted = await blobClient.deleteObject(key)
    if (deleted) reclaimed.push(key)
  }
  return reclaimed
}

/** Lists object keys under a prefix, tolerating clients without listing. */
async function listObjectKeys(blobClient: ArtifactBlobClient, prefix: string): Promise<string[]> {
  if (typeof blobClient.listObjectKeys !== 'function') return []
  return blobClient.listObjectKeys(prefix)
}

/**
 * Explicit, operator-triggered garbage collection with anomaly audit.
 *
 * Reclaims staging uploads (`uploads/`), then reconciles the content-addressed
 * blobs (`objects/`) against the authoritative metadata rows:
 *
 *   - `blob_without_pg_row` — a blob exists in object storage with no
 *     corresponding metadata row (an interrupted upload that escaped cleanup).
 *   - `pg_purged_or_missing_blob` — a metadata row is marked `purged`, or the
 *     row exists but its binary blob is missing from object storage.
 *
 * Returns the structured {@link ArtifactGarbageCollectionReport}. No timer, no
 * background loop: nothing happens unless this function is called.
 */
export async function collectAndAuditGarbage(
  store: ArtifactStore,
  blobClient: ArtifactBlobClient,
  options: ArtifactGarbageCollectionOptions = {}
): Promise<ArtifactGarbageCollectionReport> {
  const uploadPrefix = options.uploadPrefix ?? ARTIFACT_ADMIN_UPLOAD_PREFIX
  const objectPrefix = options.objectPrefix ?? ARTIFACT_ADMIN_OBJECT_PREFIX
  const now = options.now ?? (() => new Date())

  const reclaimedStagingKeys = await reclaimOrphanedUploads(store, blobClient, uploadPrefix)
  const scannedBlobKeys = await listObjectKeys(blobClient, `${objectPrefix}/`)
  const metadataRows = options.listMetadata ? await options.listMetadata() : []

  const blobKeySet = new Set(scannedBlobKeys)
  const referencedKeys = new Set(metadataRows.map((row) => row.storageKey))
  const anomalies: ArtifactGcAnomaly[] = []

  for (const storageKey of scannedBlobKeys) {
    if (!referencedKeys.has(storageKey)) {
      anomalies.push({
        type: 'blob_without_pg_row',
        storageKey,
        details: `Blob ${storageKey} has no authoritative metadata row`,
      })
    }
  }

  for (const row of metadataRows) {
    if (row.availabilityStatus === 'purged') {
      anomalies.push({
        type: 'pg_purged_or_missing_blob',
        artifactId: row.artifactId,
        storageKey: row.storageKey,
        details: `Metadata row ${row.artifactId} is marked purged`,
      })
    } else if (!blobKeySet.has(row.storageKey)) {
      anomalies.push({
        type: 'pg_purged_or_missing_blob',
        artifactId: row.artifactId,
        storageKey: row.storageKey,
        details: `Blob ${row.storageKey} for artifact ${row.artifactId} is missing from object storage`,
      })
    }
  }

  return {
    reclaimedStagingKeys,
    anomalies,
    scannedBlobKeys,
    scannedMetadataRows: metadataRows.length,
    timestamp: now().toISOString(),
  }
}
