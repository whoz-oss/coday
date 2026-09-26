import type { LeaseReleaseResultStatus, WorkUnitLease } from '../../domain/lease/lease.js'

/**
 * Persistence port for the work-unit lease protocol (Jalon C1-T1b).
 *
 * The port describes the four protocol operations of a lease — acquisition
 * (with the `FOR UPDATE SKIP LOCKED` eligibility scan), renewal (heartbeat),
 * release, and the expiry sweep — plus two reads. It exposes no SQL detail: the
 * same contract is exercised against the PostgreSQL adapter and, in tests, the
 * in-memory `SqlClient`.
 *
 * Tenant scoping (`organizationId` / `workstreamId`) is optional per call and
 * defaults to the adapter's wiring-time scope.
 */

/** Inputs of {@link LeaseRepository.acquire}. */
export interface AcquireLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workerId: string
  environmentId?: string | null
  /** Lease time-to-live in milliseconds. */
  ttlMs: number
  /** Clock injection for deterministic tests; defaults to `new Date()`. */
  now?: Date
}

/** Result of a successful {@link LeaseRepository.acquire}. */
export interface AcquireLeaseResult {
  lease: WorkUnitLease
  workUnitId: string
}

/** Inputs of {@link LeaseRepository.renew} (heartbeat). */
export interface RenewLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workUnitId: string
  leaseId: string
  fencingToken: number
  /** New time-to-live in milliseconds, applied from `now`. */
  ttlMs: number
  now?: Date
}

/** Inputs of {@link LeaseRepository.release}. */
export interface ReleaseLeaseOptions {
  organizationId?: string
  workstreamId?: string
  workUnitId: string
  leaseId: string
  /** When provided, must match the current lease token or the call is fenced. */
  fencingToken?: number
  /** Work-unit status to transition to; defaults to `completed`. */
  resultStatus?: LeaseReleaseResultStatus
  now?: Date
}

/** Inputs of {@link LeaseRepository.expire} (the expiry sweep). */
export interface ExpireLeasesOptions {
  organizationId?: string
  workstreamId?: string
  /** Reason stamped on every expired lease; defaults to `heartbeat_timeout`. */
  expiryReason?: string
  now?: Date
}

/** Persistence port of the work-unit lease protocol. */
export interface LeaseRepository {
  /**
   * Leases the next eligible work unit (highest priority, then oldest, honoring
   * `not_before`) or returns `null` when nothing is eligible.
   */
  acquire(options: AcquireLeaseOptions): Promise<AcquireLeaseResult | null>
  /** Extends an active lease's deadline and records a heartbeat. */
  renew(options: RenewLeaseOptions): Promise<WorkUnitLease>
  /** Releases an active lease and transitions its work unit. */
  release(options: ReleaseLeaseOptions): Promise<WorkUnitLease>
  /** Retires every active lease past its deadline and re-queues its work unit. */
  expire(options: ExpireLeasesOptions): Promise<WorkUnitLease[]>
  /** Reads one lease by its full tenant-scoped identity. */
  findByLeaseId(
    organizationId: string,
    workstreamId: string,
    workUnitId: string,
    leaseId: string
  ): Promise<WorkUnitLease | null>
  /** Reads the active lease of a work unit, if any. */
  findActiveLeaseByWorkUnit(
    organizationId: string,
    workstreamId: string,
    workUnitId: string
  ): Promise<WorkUnitLease | null>
}
