/**
 * SQL artifact metadata repository adapter (V6 `artifacts` table).
 *
 * The `artifacts` row is the *authoritative* record of an artifact's
 * governance state: the three orthogonal status dimensions
 * (`availability_status`, `retention_status`, `legal_hold`), the retention
 * window, the legal-hold bookkeeping and the purge record all live here. The
 * binary payload itself lives in object storage, addressed by `storage_key`.
 *
 * Tenant / hierarchy scoping mirrors the V6 primary key
 * `(organization_id, workstream_id, namespace_id, workflow_id, artifact_id)`:
 * organization and workstream are fixed at wiring time (defaulting to
 * `'default'`), while namespace and workflow default to `'default'` but can be
 * overridden per call or derived from the artifact owner (`namespace/workflow`).
 * Reads, legal-hold updates and purges only filter on the organization,
 * workstream and the globally unique `artifact_id`, so an artifact can be
 * looked up without re-supplying its hierarchy.
 */

import type {
  ArtifactAvailabilityStatus,
  ArtifactMetadata,
  ArtifactRetentionStatus,
} from '../../../ports/artifact/artifact-store.js'
import {
  DEFAULT_ORGANIZATION_ID,
  DEFAULT_WORKSTREAM_ID,
  parseJsonColumn,
  type SqlClient,
  type SqlQueryResult,
} from './db.js'
import { withTransaction } from './unit-of-work.js'

/** Default namespace used when no scoping is provided. */
export const DEFAULT_NAMESPACE_ID = 'default'

/** Default workflow used when no scoping is provided. */
export const DEFAULT_WORKFLOW_ID = 'default'

/** Full tenant / hierarchy scope of an artifact row. */
export interface SqlArtifactMetadataScope {
  organizationId: string
  workstreamId: string
  namespaceId: string
  workflowId: string
}

/** Partial scope accepted by the repository read / write methods. */
export type SqlArtifactMetadataScopeOverride = Partial<SqlArtifactMetadataScope>

/** Wiring options for {@link SqlArtifactMetadataRepository}. */
export interface SqlArtifactMetadataRepositoryOptions {
  organizationId?: string
  workstreamId?: string
  namespaceId?: string
  workflowId?: string
}

/** Result of {@link SqlArtifactMetadataRepository.getMetadataAndStorageKey}. */
export interface SqlArtifactMetadataRecord {
  metadata: ArtifactMetadata
  storageKey: string
}

/** Domain fields persisted verbatim in the `payload` JSONB column. */
interface ArtifactPayload {
  owner?: string
  retentionDays?: number
}

/** Durable row shape of the V6 `artifacts` table. */
interface ArtifactRow {
  organization_id: string
  workstream_id: string
  namespace_id: string
  workflow_id: string
  artifact_id: string
  availability_status: string
  retention_status: string
  legal_hold: boolean
  retention_until: string | Date | null
  purged_at: string | Date | null
  purge_reason: string | null
  legal_hold_reason: string | null
  legal_hold_set_at: string | Date | null
  content_hash: string
  size: number | string
  content_type: string
  storage_key: string
  payload: unknown
  created_at: string | Date
  updated_at: string | Date
}

const ARTIFACT_COLUMNS = [
  'organization_id',
  'workstream_id',
  'namespace_id',
  'workflow_id',
  'artifact_id',
  'availability_status',
  'retention_status',
  'legal_hold',
  'retention_until',
  'purged_at',
  'purge_reason',
  'legal_hold_reason',
  'legal_hold_set_at',
  'content_hash',
  'size',
  'content_type',
  'storage_key',
  'payload',
  'created_at',
  'updated_at',
].join(', ')

const ARTIFACT_INSERT_COLUMNS = ARTIFACT_COLUMNS

/** Normalises a `Date | string | null` timestamp to an ISO string. */
function toIsoTimestamp(value: string | Date | null | undefined): string | undefined {
  if (value === null || value === undefined) return undefined
  return value instanceof Date ? value.toISOString() : String(value)
}

/** Coerces an ISO string / `Date` input into an ISO string. */
function isoFromInput(value: Date | string | undefined): string | undefined {
  if (value === undefined) return undefined
  return value instanceof Date ? value.toISOString() : String(value)
}

/** Maps the DB availability enum onto the domain vocabulary. */
function toAvailabilityStatus(value: string): ArtifactAvailabilityStatus {
  if (value === 'purged') return 'purged'
  if (value === 'available') return 'available'
  return 'archived'
}

/** Maps the DB retention enum onto the domain vocabulary. */
function toRetentionStatus(value: string): ArtifactRetentionStatus {
  return value === 'expired' ? 'expired' : 'active'
}

/** Parses the JSONB `payload` column into its typed domain shape. */
function parseArtifactPayload(value: unknown): ArtifactPayload {
  try {
    const parsed = parseJsonColumn<ArtifactPayload | null>(value)
    return parsed ?? {}
  } catch {
    return {}
  }
}

/**
 * Derives the namespace / workflow scope from an owner, when the owner is
 * structured as `namespace/workflow`. A bare owner is used as the namespace.
 */
function parseOwnerScope(owner: string): { namespaceId?: string; workflowId?: string } {
  if (!owner) return {}
  const separator = owner.indexOf('/')
  if (separator > 0 && separator < owner.length - 1) {
    return { namespaceId: owner.slice(0, separator), workflowId: owner.slice(separator + 1) }
  }
  return { namespaceId: owner }
}

/** SQL-backed, authoritative artifact metadata repository. */
export class SqlArtifactMetadataRepository {
  readonly #client: SqlClient
  readonly #options: SqlArtifactMetadataRepositoryOptions

  constructor(client: SqlClient, options: SqlArtifactMetadataRepositoryOptions = {}) {
    this.#client = client
    this.#options = options
  }

  /** Resolves an effective scope from an optional per-call override. */
  #resolveScope(scope?: SqlArtifactMetadataScopeOverride): SqlArtifactMetadataScope {
    return {
      organizationId: scope?.organizationId ?? this.#options.organizationId ?? DEFAULT_ORGANIZATION_ID,
      workstreamId: scope?.workstreamId ?? this.#options.workstreamId ?? DEFAULT_WORKSTREAM_ID,
      namespaceId: scope?.namespaceId ?? this.#options.namespaceId ?? DEFAULT_NAMESPACE_ID,
      workflowId: scope?.workflowId ?? this.#options.workflowId ?? DEFAULT_WORKFLOW_ID,
    }
  }

  /** Resolves the scope used when persisting, defaulting from the owner. */
  #resolveSaveScope(owner: string, scope?: SqlArtifactMetadataScopeOverride): SqlArtifactMetadataScope {
    const resolved = this.#resolveScope(scope)
    if (scope?.namespaceId === undefined && this.#options.namespaceId === undefined) {
      const parsed = parseOwnerScope(owner)
      if (parsed.namespaceId !== undefined) resolved.namespaceId = parsed.namespaceId
    }
    if (scope?.workflowId === undefined && this.#options.workflowId === undefined) {
      const parsed = parseOwnerScope(owner)
      if (parsed.workflowId !== undefined) resolved.workflowId = parsed.workflowId
    }
    return resolved
  }

  /** Builds the optional hierarchy narrowing of a lookup query. */
  #scopeFilter(
    sql: string,
    params: unknown[],
    scope?: SqlArtifactMetadataScopeOverride
  ): { sql: string; params: unknown[] } {
    let clause = sql
    const filters = [...params]
    if (scope?.namespaceId !== undefined) {
      filters.push(scope.namespaceId)
      clause += ` AND namespace_id = $${filters.length}`
    }
    if (scope?.workflowId !== undefined) {
      filters.push(scope.workflowId)
      clause += ` AND workflow_id = $${filters.length}`
    }
    return { sql: clause, params: filters }
  }

  /** Maps a durable row into the domain {@link ArtifactMetadata}. */
  #toMetadata(row: ArtifactRow): ArtifactMetadata {
    const payload = parseArtifactPayload(row.payload)
    const createdAt = toIsoTimestamp(row.created_at) ?? new Date().toISOString()
    const retentionUntil = toIsoTimestamp(row.retention_until)
    const purgedAt = toIsoTimestamp(row.purged_at)
    const legalHoldSetAt = toIsoTimestamp(row.legal_hold_set_at)
    return {
      id: row.artifact_id,
      owner: payload.owner ?? row.namespace_id,
      hash: row.content_hash,
      size: Number(row.size),
      contentType: row.content_type,
      availabilityStatus: toAvailabilityStatus(row.availability_status),
      retentionStatus: toRetentionStatus(row.retention_status),
      legalHold: row.legal_hold === true,
      createdAt,
      ...(payload.retentionDays !== undefined ? { retentionDays: payload.retentionDays } : {}),
      ...(retentionUntil !== undefined ? { retentionUntil } : {}),
      ...(purgedAt !== undefined ? { purgedAt } : {}),
      ...(row.purge_reason != null ? { purgeReason: row.purge_reason } : {}),
      ...(row.legal_hold_reason != null ? { legalHoldReason: row.legal_hold_reason } : {}),
      ...(legalHoldSetAt !== undefined ? { legalHoldSetAt } : {}),
    }
  }

  async #selectRow(artifactId: string, scope?: SqlArtifactMetadataScopeOverride): Promise<ArtifactRow | null> {
    const resolved = this.#resolveScope(scope)
    const filter = this.#scopeFilter(
      `SELECT ${ARTIFACT_COLUMNS} FROM artifacts
       WHERE organization_id = $1 AND workstream_id = $2 AND artifact_id = $3`,
      [resolved.organizationId, resolved.workstreamId, artifactId],
      scope
    )
    const { rows } = await this.#client.query<ArtifactRow>(filter.sql, filter.params)
    return rows[0] ?? null
  }

  /**
   * Inserts (or upserts) the authoritative metadata row for an artifact,
   * returning the persisted metadata.
   */
  async saveMetadata(
    metadata: ArtifactMetadata,
    storageKey: string,
    scope?: SqlArtifactMetadataScopeOverride
  ): Promise<ArtifactMetadata> {
    const resolved = this.#resolveSaveScope(metadata.owner, scope)
    const createdAt = isoFromInput(metadata.createdAt) ?? new Date().toISOString()
    const payload = JSON.stringify({
      owner: metadata.owner,
      ...(metadata.retentionDays !== undefined ? { retentionDays: metadata.retentionDays } : {}),
    } satisfies ArtifactPayload)
    const retentionUntil = isoFromInput(metadata.retentionUntil) ?? null
    const purgedAt = isoFromInput(metadata.purgedAt) ?? null
    const legalHoldSetAt = isoFromInput(metadata.legalHoldSetAt) ?? null
    const values: unknown[] = [
      resolved.organizationId,
      resolved.workstreamId,
      resolved.namespaceId,
      resolved.workflowId,
      metadata.id,
      metadata.availabilityStatus,
      metadata.retentionStatus,
      metadata.legalHold,
      retentionUntil,
      purgedAt,
      metadata.purgeReason ?? null,
      metadata.legalHoldReason ?? null,
      legalHoldSetAt,
      metadata.hash,
      metadata.size,
      metadata.contentType,
      storageKey,
      payload,
      createdAt,
      createdAt,
    ]
    await withTransaction(this.#client, async (tx) => {
      await tx.query(
        `INSERT INTO artifacts (${ARTIFACT_INSERT_COLUMNS}) VALUES (${values.map((_, index) => `$${index + 1}`).join(', ')})
         ON CONFLICT (organization_id, workstream_id, namespace_id, workflow_id, artifact_id)
         DO UPDATE SET
           availability_status = EXCLUDED.availability_status,
           retention_status = EXCLUDED.retention_status,
           legal_hold = EXCLUDED.legal_hold,
           retention_until = EXCLUDED.retention_until,
           purged_at = EXCLUDED.purged_at,
           purge_reason = EXCLUDED.purge_reason,
           legal_hold_reason = EXCLUDED.legal_hold_reason,
           legal_hold_set_at = EXCLUDED.legal_hold_set_at,
           content_hash = EXCLUDED.content_hash,
           size = EXCLUDED.size,
           content_type = EXCLUDED.content_type,
           storage_key = EXCLUDED.storage_key,
           payload = EXCLUDED.payload,
           updated_at = EXCLUDED.updated_at`,
        values
      )
    })
    return metadata
  }

  /** Returns the artifact metadata, or `null` when no row exists. */
  async getMetadata(artifactId: string, scope?: SqlArtifactMetadataScopeOverride): Promise<ArtifactMetadata | null> {
    const row = await this.#selectRow(artifactId, scope)
    return row ? this.#toMetadata(row) : null
  }

  /** Returns the metadata together with its object-storage key. */
  async getMetadataAndStorageKey(
    artifactId: string,
    scope?: SqlArtifactMetadataScopeOverride
  ): Promise<SqlArtifactMetadataRecord | null> {
    const row = await this.#selectRow(artifactId, scope)
    if (!row) return null
    return { metadata: this.#toMetadata(row), storageKey: row.storage_key }
  }

  /**
   * Sets or releases the legal hold of an artifact, returning the refreshed
   * metadata (or `null` when the artifact is unknown).
   */
  async updateLegalHold(
    artifactId: string,
    legalHold: boolean,
    reason?: string,
    now: Date = new Date(),
    scope?: SqlArtifactMetadataScopeOverride
  ): Promise<ArtifactMetadata | null> {
    const resolved = this.#resolveScope(scope)
    const nowIso = now.toISOString()
    const result: SqlQueryResult = await this.#client.query(
      `UPDATE artifacts
         SET legal_hold = $1, legal_hold_reason = $2, legal_hold_set_at = $3, updated_at = $4
       WHERE organization_id = $5 AND workstream_id = $6 AND artifact_id = $7`,
      [
        legalHold,
        legalHold ? (reason ?? null) : null,
        legalHold ? nowIso : null,
        nowIso,
        resolved.organizationId,
        resolved.workstreamId,
        artifactId,
      ]
    )
    if (!result.rowCount) return null
    const row = await this.#selectRow(artifactId, scope)
    return row ? this.#toMetadata(row) : null
  }

  /**
   * Purges an artifact: flips `availability_status` to `'purged'` and records
   * the reason and timestamp. The SQL guard (`legal_hold = FALSE` and
   * `availability_status <> 'purged'`) enforces the golden rule: a held
   * artifact can never be purged. Returns `true` when a row was updated.
   */
  async purgeArtifact(
    artifactId: string,
    reason: string,
    now: Date = new Date(),
    scope?: SqlArtifactMetadataScopeOverride
  ): Promise<boolean> {
    const resolved = this.#resolveScope(scope)
    const nowIso = now.toISOString()
    const result: SqlQueryResult = await this.#client.query(
      `UPDATE artifacts
         SET availability_status = 'purged', purged_at = $1, purge_reason = $2, updated_at = $3
       WHERE organization_id = $4 AND workstream_id = $5 AND artifact_id = $6
         AND legal_hold = $7 AND availability_status <> 'purged'`,
      [nowIso, reason, nowIso, resolved.organizationId, resolved.workstreamId, artifactId, false]
    )
    return (result.rowCount ?? 0) > 0
  }
}

/** Wires a SQL artifact metadata repository around a database client. */
export function createSqlArtifactMetadataRepository(
  client: SqlClient,
  options: SqlArtifactMetadataRepositoryOptions = {}
): SqlArtifactMetadataRepository {
  return new SqlArtifactMetadataRepository(client, options)
}
