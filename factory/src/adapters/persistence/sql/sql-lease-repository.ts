import { randomUUID } from 'node:crypto'

import {
  LEASE_ERROR_CODES,
  LEASE_EXPIRY_REASONS,
  LeaseError,
  assertFencingToken,
  assertLeaseRenewable,
  computeLeaseExpiresAt,
  isLeaseStatus,
  type WorkUnitLease,
  type WorkUnitLeaseStatus,
} from '../../../domain/lease/lease.js'
import type {
  AcquireLeaseOptions,
  AcquireLeaseResult,
  ExpireLeasesOptions,
  LeaseRepository,
  ReleaseLeaseOptions,
  RenewLeaseOptions,
} from '../../../ports/persistence/lease-repository.js'
import { DEFAULT_ORGANIZATION_ID, DEFAULT_WORKSTREAM_ID, type SqlClient } from './db.js'
import { withTransaction } from './unit-of-work.js'

/**
 * SQL work-unit lease repository adapter (Jalon C1-T1b).
 *
 * Implements the lease protocol over the V6 `work_unit_leases` skeleton
 * extended by V7 (`fencing_token`, `acquired_at`, `lease_expires_at`,
 * `heartbeat_at`, `released_at`, `expiry_reason`) and the V7 `work_units`
 * scheduling columns (`priority`, `not_before`, `attempt_count`).
 *
 * Every protocol mutation runs inside a single {@link withTransaction} unit of
 * work so the lease row and the work-unit row always flip together:
 *   * `acquire` scans the eligible work units with `FOR UPDATE SKIP LOCKED`,
 *     draws the next monotone token from `work_unit_lease_fencing_seq`, inserts
 *     the lease and advances the work unit to `running`.
 *   * `renew` is the heartbeat: it validates state + fencing token, then
 *     extends the deadline.
 *   * `release` validates the token, marks the lease `released` and transitions
 *     the work unit.
 *   * `expire` sweeps every active lease past its deadline, marks it `expired`
 *     and re-queues its work unit as `created`.
 *
 * The fencing rule is authoritative: any protocol operation presented a token
 * that is not the current one is rejected with `LEASE_FENCED`.
 *
 * Tenant scoping (organization + workstream) is fixed at wiring time and can be
 * overridden per call.
 */

export interface SqlLeaseRepositoryOptions {
  organizationId?: string
  workstreamId?: string
}

interface WorkUnitCandidateRow {
  work_unit_id: string
  revision: number
  attempt_count: number
}

interface SequenceRow {
  fencing_token: string | number
}

interface LeaseRow {
  organization_id: string
  workstream_id: string
  work_unit_id: string
  lease_id: string
  worker_id: string
  environment_id: string | null
  status: string
  fencing_token: string | number | null
  acquired_at: string | Date | null
  lease_expires_at: string | Date | null
  heartbeat_at: string | Date | null
  released_at: string | Date | null
  expiry_reason: string | null
  created_at: string | Date | null
}

/** Normalizes the date shapes returned by `pg` (Date) and the in-memory client (string). */
function toIso(value: string | Date | null | undefined): string | null {
  if (value === null || value === undefined) return null
  if (value instanceof Date) return value.toISOString()
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}

function toIsoRequired(value: string | Date | null | undefined): string {
  return toIso(value) ?? new Date(0).toISOString()
}

function mapLease(row: LeaseRow): WorkUnitLease {
  const status: WorkUnitLeaseStatus = isLeaseStatus(row.status) ? row.status : 'expired'
  return {
    organizationId: row.organization_id,
    workstreamId: row.workstream_id,
    workUnitId: row.work_unit_id,
    leaseId: row.lease_id,
    workerId: row.worker_id,
    environmentId: row.environment_id ?? null,
    status,
    fencingToken: Number(row.fencing_token ?? 0),
    acquiredAt: toIso(row.acquired_at),
    leaseExpiresAt: toIso(row.lease_expires_at),
    heartbeatAt: toIso(row.heartbeat_at),
    releasedAt: toIso(row.released_at),
    expiryReason: row.expiry_reason ?? null,
    createdAt: toIsoRequired(row.created_at),
  }
}

export class SqlLeaseRepository implements LeaseRepository {
  readonly #client: SqlClient
  readonly #organizationId: string
  readonly #workstreamId: string

  constructor(client: SqlClient, options: SqlLeaseRepositoryOptions = {}) {
    this.#client = client
    this.#organizationId = options.organizationId ?? DEFAULT_ORGANIZATION_ID
    this.#workstreamId = options.workstreamId ?? DEFAULT_WORKSTREAM_ID
  }

  async #selectLease(
    client: SqlClient,
    organizationId: string,
    workstreamId: string,
    workUnitId: string,
    leaseId: string,
    forUpdate = false
  ): Promise<WorkUnitLease | null> {
    const { rows } = await client.query<LeaseRow>(
      `SELECT * FROM work_unit_leases
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND lease_id = $4${
         forUpdate ? ' FOR UPDATE' : ''
       }`,
      [organizationId, workstreamId, workUnitId, leaseId]
    )
    const row = rows[0]
    return row ? mapLease(row) : null
  }

  async acquire(options: AcquireLeaseOptions): Promise<AcquireLeaseResult | null> {
    const organizationId = options.organizationId ?? this.#organizationId
    const workstreamId = options.workstreamId ?? this.#workstreamId
    const nowIso = (options.now ?? new Date()).toISOString()
    const leaseExpiresAt = computeLeaseExpiresAt(nowIso, options.ttlMs)
    const environmentId = options.environmentId ?? null

    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query<WorkUnitCandidateRow>(
        `SELECT work_unit_id, revision, attempt_count FROM work_units
         WHERE organization_id = $1 AND workstream_id = $2
           AND status IN ('created', 'failed')
           AND (not_before IS NULL OR not_before <= $3)
         ORDER BY priority DESC, created_at ASC
         LIMIT 1
         FOR UPDATE SKIP LOCKED`,
        [organizationId, workstreamId, nowIso]
      )
      const candidate = rows[0]
      if (!candidate) return null

      const sequence = await tx.query<SequenceRow>(`SELECT nextval('work_unit_lease_fencing_seq') AS fencing_token`)
      const fencingToken = Number(sequence.rows[0]?.fencing_token ?? 0)
      const leaseId = `lease_${randomUUID()}`

      await tx.query(
        `INSERT INTO work_unit_leases
           (organization_id, workstream_id, work_unit_id, lease_id, worker_id, environment_id, status,
            fencing_token, acquired_at, lease_expires_at, heartbeat_at, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, 'active', $7, $8, $9, $10, $11)`,
        [
          organizationId,
          workstreamId,
          candidate.work_unit_id,
          leaseId,
          options.workerId,
          environmentId,
          fencingToken,
          nowIso,
          leaseExpiresAt,
          nowIso,
          nowIso,
        ]
      )

      const { rowCount } = await tx.query(
        `UPDATE work_units
           SET status = 'running', attempt_count = attempt_count + 1, revision = revision + 1, updated_at = $1
         WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4`,
        [nowIso, organizationId, workstreamId, candidate.work_unit_id]
      )
      if (!rowCount) {
        throw new LeaseError(LEASE_ERROR_CODES.WORK_UNIT_NOT_FOUND, { workUnitId: candidate.work_unit_id })
      }

      const lease = await this.#selectLease(tx, organizationId, workstreamId, candidate.work_unit_id, leaseId)
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId })
      return { lease, workUnitId: candidate.work_unit_id }
    })
  }

  async renew(options: RenewLeaseOptions): Promise<WorkUnitLease> {
    const organizationId = options.organizationId ?? this.#organizationId
    const workstreamId = options.workstreamId ?? this.#workstreamId
    const nowIso = (options.now ?? new Date()).toISOString()
    const leaseExpiresAt = computeLeaseExpiresAt(nowIso, options.ttlMs)

    return withTransaction(this.#client, async (tx) => {
      const lease = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId, true)
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      assertLeaseRenewable(lease, nowIso)
      assertFencingToken(lease.fencingToken, options.fencingToken)

      await tx.query(
        `UPDATE work_unit_leases SET lease_expires_at = $1, heartbeat_at = $2
         WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5 AND lease_id = $6`,
        [leaseExpiresAt, nowIso, organizationId, workstreamId, options.workUnitId, options.leaseId]
      )

      const updated = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId)
      if (!updated) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      return updated
    })
  }

  async release(options: ReleaseLeaseOptions): Promise<WorkUnitLease> {
    const organizationId = options.organizationId ?? this.#organizationId
    const workstreamId = options.workstreamId ?? this.#workstreamId
    const nowIso = (options.now ?? new Date()).toISOString()
    const resultStatus = options.resultStatus ?? 'completed'

    return withTransaction(this.#client, async (tx) => {
      const lease = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId, true)
      if (!lease) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      if (options.fencingToken !== undefined) assertFencingToken(lease.fencingToken, options.fencingToken)
      if (lease.status !== 'active') {
        throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId, status: lease.status })
      }

      await tx.query(
        `UPDATE work_unit_leases SET status = 'released', released_at = $1
         WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4 AND lease_id = $5`,
        [nowIso, organizationId, workstreamId, options.workUnitId, options.leaseId]
      )

      const { rowCount } = await tx.query(
        `UPDATE work_units SET status = $1, revision = revision + 1, updated_at = $2
         WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5`,
        [resultStatus, nowIso, organizationId, workstreamId, options.workUnitId]
      )
      if (!rowCount) throw new LeaseError(LEASE_ERROR_CODES.WORK_UNIT_NOT_FOUND, { workUnitId: options.workUnitId })

      const released = await this.#selectLease(tx, organizationId, workstreamId, options.workUnitId, options.leaseId)
      if (!released) throw new LeaseError(LEASE_ERROR_CODES.LEASE_NOT_FOUND, { leaseId: options.leaseId })
      return released
    })
  }

  async expire(options: ExpireLeasesOptions): Promise<WorkUnitLease[]> {
    const organizationId = options.organizationId ?? this.#organizationId
    const workstreamId = options.workstreamId ?? this.#workstreamId
    const nowIso = (options.now ?? new Date()).toISOString()
    const expiryReason = options.expiryReason ?? LEASE_EXPIRY_REASONS.HEARTBEAT_TIMEOUT

    return withTransaction(this.#client, async (tx) => {
      const { rows } = await tx.query<LeaseRow>(
        `SELECT * FROM work_unit_leases
         WHERE organization_id = $1 AND workstream_id = $2 AND status = 'active' AND lease_expires_at < $3
         FOR UPDATE`,
        [organizationId, workstreamId, nowIso]
      )

      const expired: WorkUnitLease[] = []
      for (const row of rows) {
        const lease = mapLease(row)
        await tx.query(
          `UPDATE work_unit_leases SET status = 'expired', released_at = $1, expiry_reason = $2
           WHERE organization_id = $3 AND workstream_id = $4 AND work_unit_id = $5 AND lease_id = $6`,
          [nowIso, expiryReason, organizationId, workstreamId, lease.workUnitId, lease.leaseId]
        )
        await tx.query(
          `UPDATE work_units SET status = 'created', revision = revision + 1, updated_at = $1
           WHERE organization_id = $2 AND workstream_id = $3 AND work_unit_id = $4`,
          [nowIso, organizationId, workstreamId, lease.workUnitId]
        )
        // `attempt_count` is deliberately NOT bumped here: it was already
        // incremented when the lease was acquired, so the sweep only re-queues
        // the unit (no double counting of a single execution attempt).
        expired.push({ ...lease, status: 'expired', releasedAt: nowIso, expiryReason })
      }
      return expired
    })
  }

  async findByLeaseId(
    organizationId: string,
    workstreamId: string,
    workUnitId: string,
    leaseId: string
  ): Promise<WorkUnitLease | null> {
    return this.#selectLease(this.#client, organizationId, workstreamId, workUnitId, leaseId)
  }

  async findActiveLeaseByWorkUnit(
    organizationId: string,
    workstreamId: string,
    workUnitId: string
  ): Promise<WorkUnitLease | null> {
    const { rows } = await this.#client.query<LeaseRow>(
      `SELECT * FROM work_unit_leases
       WHERE organization_id = $1 AND workstream_id = $2 AND work_unit_id = $3 AND status = 'active'
       ORDER BY fencing_token DESC
       LIMIT 1`,
      [organizationId, workstreamId, workUnitId]
    )
    const row = rows[0]
    return row ? mapLease(row) : null
  }
}

/** Wires a SQL lease repository around a database client. */
export function createSqlLeaseRepository(
  client: SqlClient,
  options: SqlLeaseRepositoryOptions = {}
): SqlLeaseRepository {
  return new SqlLeaseRepository(client, options)
}
