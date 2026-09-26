/**
 * Pure lease-protocol domain (Jalon C1-T1b).
 *
 * This module declares the durable vocabulary of the worker lease protocol laid
 * down by the V6 `work_unit_leases` skeleton and extended by the V7 migration:
 * the lease lifecycle states, the explicit error codes, the monotone fencing
 * rule and the expiry rule. Both the SQL adapter and its protocol tests share
 * it.
 *
 * Domain purity: this module carries no `node:*` dependency at all. It is a
 * pure description of the protocol — no I/O, no clock, no driver.
 */

/** Ordered lifecycle states a work-unit lease can take (V6 status CHECK). */
export const WORK_UNIT_LEASE_STATUSES = Object.freeze(['active', 'released', 'expired'] as const)

/** Lifecycle state of a work-unit lease. */
export type WorkUnitLeaseStatus = (typeof WORK_UNIT_LEASE_STATUSES)[number]

/**
 * Work-unit statuses eligible for (re)acquisition during the `FOR UPDATE
 * SKIP LOCKED` scan: a fresh unit (`created`) or a retryable failure (`failed`).
 */
export const LEASE_ELIGIBLE_WORK_UNIT_STATUSES = Object.freeze(['created', 'failed'] as const)

export type LeaseEligibleWorkUnitStatus = (typeof LEASE_ELIGIBLE_WORK_UNIT_STATUSES)[number]

/**
 * Work-unit statuses a lease release can transition the associated unit to:
 * `completed` on success, `failed` on a reported failure, `created` to let the
 * unit be re-acquired.
 */
export const LEASE_RELEASE_RESULT_STATUSES = Object.freeze(['completed', 'failed', 'created'] as const)

export type LeaseReleaseResultStatus = (typeof LEASE_RELEASE_RESULT_STATUSES)[number]

/** Machine-readable reasons for an `active` → `expired` transition. */
export const LEASE_EXPIRY_REASONS = Object.freeze({
  HEARTBEAT_TIMEOUT: 'heartbeat_timeout',
  WORKER_LOST: 'worker_lost',
  RECLAIMED: 'reclaimed',
} as const)

export type LeaseExpiryReason = (typeof LEASE_EXPIRY_REASONS)[keyof typeof LEASE_EXPIRY_REASONS]

/** Explicit, machine-readable lease-protocol error codes. */
export const LEASE_ERROR_CODES = Object.freeze({
  /** A stale / mismatched fencing token was presented. */
  LEASE_FENCED: 'LEASE_FENCED',
  /** No lease row matches the addressed identity. */
  LEASE_NOT_FOUND: 'LEASE_NOT_FOUND',
  /** The lease is past its deadline and can no longer be renewed. */
  LEASE_EXPIRED: 'LEASE_EXPIRED',
  /** The eligible-work-unit scan found nothing to lease. */
  NO_ELIGIBLE_WORK_UNIT: 'NO_ELIGIBLE_WORK_UNIT',
  /** The work unit addressed by the operation does not exist. */
  WORK_UNIT_NOT_FOUND: 'WORK_UNIT_NOT_FOUND',
  /** The operation is not legal for the lease's current state. */
  INVALID_LEASE_STATE: 'INVALID_LEASE_STATE',
} as const)

/** One of the machine-readable lease-protocol error codes. */
export type LeaseErrorCode = (typeof LEASE_ERROR_CODES)[keyof typeof LEASE_ERROR_CODES]

/**
 * Error raised by the lease protocol. Carries a stable {@link LeaseErrorCode}
 * plus structured details so callers (and tests) never depend on the message.
 */
export class LeaseError extends Error {
  readonly code: LeaseErrorCode
  readonly details: Record<string, unknown>

  constructor(code: LeaseErrorCode, details: Record<string, unknown> = {}, cause?: unknown) {
    super(code, cause === undefined ? undefined : { cause: cause as Error })
    this.name = 'LeaseError'
    this.code = code
    this.details = details
  }
}

/**
 * A durable work-unit lease as persisted in `work_unit_leases`. The nullable
 * timestamps mirror the nullable V7 columns (rows created before the protocol
 * predate them).
 */
export interface WorkUnitLease {
  organizationId: string
  workstreamId: string
  workUnitId: string
  leaseId: string
  workerId: string
  environmentId: string | null
  status: WorkUnitLeaseStatus
  /** Monotone fencing token drawn from `work_unit_lease_fencing_seq`. */
  fencingToken: number
  acquiredAt: string | null
  leaseExpiresAt: string | null
  heartbeatAt: string | null
  releasedAt: string | null
  expiryReason: string | null
  createdAt: string
}

/** The (organization, workstream, work-unit, lease) identity of a lease row. */
export interface LeaseIdentity {
  organizationId: string
  workstreamId: string
  workUnitId: string
  leaseId: string
}

/** True when `value` is one of the known lease lifecycle states. */
export function isLeaseStatus(value: unknown): value is WorkUnitLeaseStatus {
  return typeof value === 'string' && (WORK_UNIT_LEASE_STATUSES as readonly string[]).includes(value)
}

/** True when `value` is one of the known lease-protocol error codes. */
export function isLeaseErrorCode(value: unknown): value is LeaseErrorCode {
  return typeof value === 'string' && Object.values(LEASE_ERROR_CODES).includes(value as LeaseErrorCode)
}

/**
 * Computes the deadline of a lease acquired/renewed at `nowIso` with a
 * time-to-live of `ttlMs` milliseconds. Pure: it performs no clock read.
 */
export function computeLeaseExpiresAt(nowIso: string, ttlMs: number): string {
  if (!Number.isFinite(ttlMs) || ttlMs <= 0) throw new RangeError('INVALID_LEASE_TTL')
  return new Date(Date.parse(nowIso) + ttlMs).toISOString()
}

/**
 * True when an `active` lease is past its deadline relative to `nowIso`. A
 * lease with no deadline (a legacy row) is never considered expired by time:
 * only the reaper can retire it.
 */
export function isLeaseExpiredByTime(lease: WorkUnitLease, nowIso: string): boolean {
  if (lease.status !== 'active' || lease.leaseExpiresAt === null) return false
  return Date.parse(lease.leaseExpiresAt) <= Date.parse(nowIso)
}

/**
 * True when the lease is `active` and still within its deadline at `nowIso`
 * (defaults to the current time).
 */
export function isLeaseActive(lease: WorkUnitLease, nowIso: string = new Date().toISOString()): boolean {
  return lease.status === 'active' && !isLeaseExpiredByTime(lease, nowIso)
}

/**
 * The fencing rule: an incoming token is accepted only when it exactly matches
 * the token currently recorded in the database. A stale token (`<` current) is
 * fenced off; an unknown greater token is rejected too, since the authoritative
 * token is always the one the database handed out.
 */
export function isFencingTokenCurrent(current: number, incoming: number): boolean {
  return Number.isFinite(incoming) && incoming === current
}

/**
 * Boolean fencing validator: `true` when `incoming` is the current token, i.e.
 * when the operation may proceed. The throwing counterpart is
 * {@link assertFencingToken}.
 */
export function validateFencingToken(current: number, incoming: number): boolean {
  return isFencingTokenCurrent(current, incoming)
}

/** Throws {@link LeaseError} `LEASE_FENCED` unless the incoming token is current. */
export function assertFencingToken(current: number, incoming: number): void {
  if (!isFencingTokenCurrent(current, incoming)) {
    throw new LeaseError(LEASE_ERROR_CODES.LEASE_FENCED, {
      currentFencingToken: current,
      incomingFencingToken: incoming,
    })
  }
}

/**
 * Throws the precise {@link LeaseError} when a lease cannot be renewed at
 * `nowIso`: `INVALID_LEASE_STATE` when it is not `active`, `LEASE_EXPIRED` when
 * it is `active` but past its deadline.
 */
export function assertLeaseRenewable(lease: WorkUnitLease, nowIso: string): void {
  if (lease.status !== 'active') {
    throw new LeaseError(LEASE_ERROR_CODES.INVALID_LEASE_STATE, { leaseId: lease.leaseId, status: lease.status })
  }
  if (isLeaseExpiredByTime(lease, nowIso)) {
    throw new LeaseError(LEASE_ERROR_CODES.LEASE_EXPIRED, {
      leaseId: lease.leaseId,
      leaseExpiresAt: lease.leaseExpiresAt,
    })
  }
}
