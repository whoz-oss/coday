/**
 * Worker runtime core — vocabulary and injected ports (Jalon C2-T1).
 *
 * The worker runtime is a *thin* loop orchestrator: it registers the worker,
 * claims a work unit through the C1 lease protocol, runs the injected
 * {@link WorkExecutor} and releases the lease. It deliberately owns **no**
 * safety rule: fencing, expiry and exclusivity are the persistence adapters'
 * and the pure lease-domain's authority (`factory/src/domain/lease/`). The
 * runtime only reacts to the machine-readable protocol errors they raise
 * (`LEASE_FENCED`, `LEASE_EXPIRED`, …) by cancelling the execution.
 *
 * This module is pure vocabulary: interfaces and type aliases only, no I/O.
 */

import type { LeaseRepository } from '../../ports/persistence/lease-repository.js'
import type { WorkUnitRepository } from '../../ports/persistence/work-unit-repository.js'
import type { WorkerRepository } from '../../ports/persistence/worker-repository.js'
import type { WorkUnit } from '../work-unit.js'

/** Structured failure reported by an executor alongside its result. */
export interface WorkExecutionError {
  /** Stable machine-readable failure code. */
  code: string
  /** Human-readable failure message. */
  message: string
  /** Optional structured details for observability. */
  details?: Record<string, unknown>
}

/** Outcome of one work-unit execution as reported by the {@link WorkExecutor}. */
export interface WorkExecutionResult {
  /** Terminal status the work unit should reach on a clean (non-fenced) run. */
  status: 'completed' | 'failed'
  /**
   * Optional payload patch to merge into the work unit before the terminal
   * release. Ignored when the lease was fenced.
   */
  payloadUpdate?: Record<string, unknown>
  /** Populated when `status` is `failed`. */
  error?: WorkExecutionError
}

/**
 * Injected job executor. The runtime hands it the claimed work unit and an
 * {@link AbortSignal} that fires when the lease is lost (fenced/expired) or the
 * runtime is force-draining. The executor must observe the signal and stop.
 */
export interface WorkExecutor {
  execute(workUnit: WorkUnit, signal: AbortSignal): Promise<WorkExecutionResult>
}

/** Static identity and tuning of one worker runtime instance. */
export interface WorkerRuntimeConfig {
  /** Tenant scope of the lease / work-unit repositories. */
  organizationId: string
  /** Workstream scope of the lease / work-unit repositories. */
  workstreamId: string
  /** Durable worker identity registered in the `workers` table. */
  workerId: string
  /** Worker type recorded at registration. */
  workerType: string
  /** Lease time-to-live, refreshed by every heartbeat. */
  leaseTtlMs: number
  /** Interval between liveness and lease heartbeats. */
  heartbeatIntervalMs: number
  /** Delay before polling again after an empty (or failed) claim scan. */
  pollBackoffMs: number
  /** Maximum number of concurrent executions; defaults to 1. */
  concurrency?: number
  /** Optional environment bound to every acquired lease. */
  environmentId?: string | null
  /** Optional capability list advertised at worker registration. */
  capabilities?: readonly string[]
  /** Optional protocol version advertised at worker registration. */
  protocolVersion?: string
}

/** Minimal structured logger the runtime writes to. */
export interface WorkerRuntimeLogger {
  info(message: string, details?: Record<string, unknown>): void
  warn(message: string, details?: Record<string, unknown>): void
  error(message: string, details?: Record<string, unknown>): void
  debug?(message: string, details?: Record<string, unknown>): void
}

/** Injected clock; every timestamp the runtime writes goes through it. */
export type WorkerRuntimeClock = () => Date

/** Injected timer seam so the loop can be driven deterministically in tests. */
export interface WorkerRuntimeTimers {
  setInterval(handler: () => void, timeout: number): unknown
  clearInterval(handle: unknown): void
  setTimeout(handler: () => void, timeout: number): unknown
  clearTimeout(handle: unknown): void
}

/** Injected id generator (runtime instance id and future correlation ids). */
export type WorkerRuntimeIdGenerator = () => string

/** Everything the runtime needs to be wired against, all injectable. */
export interface WorkerRuntimeDeps {
  leaseRepo: LeaseRepository
  workUnitRepo: WorkUnitRepository
  workerRepo: WorkerRepository
  executor: WorkExecutor
  /** Defaults to `() => new Date()`. */
  clock?: WorkerRuntimeClock
  /** Defaults to the global Node timers. */
  timers?: WorkerRuntimeTimers
  /** Defaults to a no-op logger. */
  logger?: WorkerRuntimeLogger
  /** Defaults to `crypto.randomUUID`. */
  idGenerator?: WorkerRuntimeIdGenerator
}

/** Lifecycle state of a runtime instance. */
export type WorkerRuntimeStatus = 'stopped' | 'starting' | 'running' | 'draining'

/** Options accepted by {@link WorkerRuntime.stop}. */
export interface WorkerRuntimeStopOptions {
  /**
   * When set, running executions are aborted after this many milliseconds of
   * drain and their leases are released back to `created` (re-queued). When
   * omitted, the runtime waits indefinitely for the in-flight units.
   */
  drainTimeoutMs?: number
}
