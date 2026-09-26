/**
 * Worker runtime core — thin loop orchestrator (Jalon C2-T1).
 *
 * Responsibilities:
 *   * register the worker and keep its liveness heartbeat alive;
 *   * poll the lease repository for eligible work units up to `concurrency`;
 *   * heartbeat each held lease with its fencing token;
 *   * delegate execution to the injected {@link WorkExecutor} behind an
 *     {@link AbortController};
 *   * release the lease (which atomically commits the terminal work-unit
 *     status) when execution finishes;
 *   * abort and *discard* the outcome when the lease is fenced/expired;
 *   * drain gracefully on shutdown without claiming new work.
 *
 * Explicit non-goals (NO DUPLICATE SAFETY RULES): this module never computes
 * expiry, never validates fencing tokens and never enforces exclusivity. The
 * lease adapter and the pure lease domain own all of that. When a protocol
 * error (`LEASE_FENCED`, `LEASE_EXPIRED`, `LEASE_NOT_FOUND`) comes back from a
 * heartbeat, the runtime simply cancels the in-flight execution and refuses any
 * terminal commit for that job.
 *
 * The `acquire` operation is the claim *and* the `created|failed → running`
 * transition: the C1 lease adapter performs it inside the same transaction that
 * inserts the lease and draws a fresh fencing token. The runtime therefore
 * never re-transitions the work unit to `running` (doing so would both duplicate
 * the adapter rule and be rejected as an invalid transition).
 */

import { LEASE_ERROR_CODES, type LeaseReleaseResultStatus, type WorkUnitLease } from '../lease/lease.js'
import type { AcquireLeaseResult } from '../../ports/persistence/lease-repository.js'
import type { WorkUnit } from '../work-unit.js'
import { canTransitionWorker, type Worker, type WorkerState } from '../worker.js'
import type {
  WorkExecutionResult,
  WorkerRuntimeClock,
  WorkerRuntimeConfig,
  WorkerRuntimeDeps,
  WorkerRuntimeIdGenerator,
  WorkerRuntimeLogger,
  WorkerRuntimeStatus,
  WorkerRuntimeStopOptions,
  WorkerRuntimeTimers,
} from './types.js'

/** Protocol error codes that mean "this worker no longer owns the lease". */
const FENCING_ERROR_CODES: ReadonlySet<string> = new Set([
  LEASE_ERROR_CODES.LEASE_FENCED,
  LEASE_ERROR_CODES.LEASE_EXPIRED,
  LEASE_ERROR_CODES.LEASE_NOT_FOUND,
])

const DEFAULT_PROTOCOL_VERSION = '1'

const defaultTimers: WorkerRuntimeTimers = {
  setInterval: (handler, timeout) => setInterval(handler, timeout),
  clearInterval: (handle) => {
    clearInterval(handle as ReturnType<typeof setInterval>)
  },
  setTimeout: (handler, timeout) => setTimeout(handler, timeout),
  clearTimeout: (handle) => {
    clearTimeout(handle as ReturnType<typeof setTimeout>)
  },
}

const noopLogger: WorkerRuntimeLogger = {
  info: () => {},
  warn: () => {},
  error: () => {},
  debug: () => {},
}

/** One in-flight execution tracked by the runtime. */
interface ActiveJob {
  readonly workUnitId: string
  readonly leaseId: string
  readonly fencingToken: number
  readonly controller: AbortController
  /** Set once the lease was lost (fenced/expired): the outcome is discarded. */
  fenced: boolean
  /** Set when a forced drain aborted the execution: the unit is re-queued. */
  stopRequested: boolean
  heartbeatHandle: unknown | null
}

/** Human-readable message of an unknown thrown value. */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

/** Machine-readable code of an unknown thrown value, when present. */
function errorCode(error: unknown): string | null {
  const code = (error as { code?: unknown } | null | undefined)?.code
  return typeof code === 'string' ? code : null
}

/**
 * Orchestrates the worker lifecycle over the C1 lease protocol. All
 * dependencies are injected so the loop runs against real adapters or an
 * in-memory harness alike.
 */
export class WorkerRuntime {
  readonly #config: WorkerRuntimeConfig
  readonly #leaseRepo: WorkerRuntimeDeps['leaseRepo']
  readonly #workUnitRepo: WorkerRuntimeDeps['workUnitRepo']
  readonly #workerRepo: WorkerRuntimeDeps['workerRepo']
  readonly #executor: WorkerRuntimeDeps['executor']
  readonly #clock: WorkerRuntimeClock
  readonly #timers: WorkerRuntimeTimers
  readonly #logger: WorkerRuntimeLogger
  readonly #idGenerator: WorkerRuntimeIdGenerator
  readonly #concurrency: number
  readonly #instanceId: string

  #status: WorkerRuntimeStatus = 'stopped'
  #worker: Worker | null = null
  #workerHeartbeatHandle: unknown | null = null
  readonly #active = new Map<string, ActiveJob>()
  readonly #sleepers = new Set<() => void>()
  readonly #slotWaiters = new Set<() => void>()
  #workerQueue: Promise<unknown> = Promise.resolve()
  #loopRunning = false

  constructor(config: WorkerRuntimeConfig, deps: WorkerRuntimeDeps) {
    this.#config = config
    this.#leaseRepo = deps.leaseRepo
    this.#workUnitRepo = deps.workUnitRepo
    this.#workerRepo = deps.workerRepo
    this.#executor = deps.executor
    this.#clock = deps.clock ?? (() => new Date())
    this.#timers = deps.timers ?? defaultTimers
    this.#logger = deps.logger ?? noopLogger
    this.#idGenerator = deps.idGenerator ?? (() => crypto.randomUUID())
    this.#concurrency = Math.max(1, Math.trunc(config.concurrency ?? 1))
    this.#instanceId = this.#idGenerator()
  }

  /** Current lifecycle state. */
  get status(): WorkerRuntimeStatus {
    return this.#status
  }

  /** Number of executions currently in flight. */
  get activeCount(): number {
    return this.#active.size
  }

  /** Identifier of this runtime instance (generated from the injected source). */
  get instanceId(): string {
    return this.#instanceId
  }

  /** The worker row as last observed; `null` before {@link start}. */
  get worker(): Worker | null {
    return this.#worker
  }

  /**
   * Registers the worker, flips it to `idle`, starts its liveness heartbeat and
   * enters the claim loop. Idempotent: a second call while running is a no-op.
   */
  async start(): Promise<void> {
    if (this.#status !== 'stopped') return
    this.#status = 'starting'
    try {
      await this.#ensureWorker()
    } catch (error) {
      this.#status = 'stopped'
      this.#logger.error('worker registration failed', {
        workerId: this.#config.workerId,
        error: errorMessage(error),
      })
      throw error
    }
    this.#status = 'running'
    this.#startWorkerHeartbeat()
    this.#startLoop()
    this.#logger.info('worker runtime started', {
      workerId: this.#config.workerId,
      concurrency: this.#concurrency,
    })
  }

  /**
   * Gracefully drains the runtime: stops claiming new work, lets in-flight
   * executions finish (or aborts them past `drainTimeoutMs`), then marks the
   * worker `offline`.
   */
  async stop(options: WorkerRuntimeStopOptions = {}): Promise<void> {
    if (this.#status === 'stopped') return
    this.#status = 'draining'
    this.#wakeSleepers()
    this.#notifySlot()

    if (options.drainTimeoutMs !== undefined) {
      await this.#drainWithTimeout(options.drainTimeoutMs)
    } else {
      await this.#waitForIdle()
    }

    this.#status = 'stopped'
    this.#stopWorkerHeartbeat()
    await this.#transitionWorker('offline')
    this.#wakeSleepers()
    this.#notifySlot()
    this.#logger.info('worker runtime stopped', { workerId: this.#config.workerId })
  }

  // -------------------------------------------------------------------------
  // Worker registration & liveness
  // -------------------------------------------------------------------------

  async #ensureWorker(): Promise<void> {
    const nowIso = this.#clock().toISOString()
    let worker = await this.#workerRepo.get(this.#config.workerId)
    if (worker === null) {
      worker = await this.#workerRepo.create({
        workerId: this.#config.workerId,
        workerType: this.#config.workerType,
        status: 'idle',
        lastHeartbeatAt: nowIso,
        protocolVersion: this.#config.protocolVersion ?? DEFAULT_PROTOCOL_VERSION,
        capabilities: [...(this.#config.capabilities ?? [])],
        payload: { runtimeInstanceId: this.#instanceId },
      })
    } else if (worker.status !== 'idle') {
      worker = await this.#workerRepo.transition(worker.workerId, 'idle', worker.revision)
    }
    this.#worker = worker
  }

  #startWorkerHeartbeat(): void {
    if (this.#workerHeartbeatHandle !== null) return
    this.#workerHeartbeatHandle = this.#timers.setInterval(() => {
      void this.#beatWorker()
    }, this.#config.heartbeatIntervalMs)
  }

  #stopWorkerHeartbeat(): void {
    if (this.#workerHeartbeatHandle === null) return
    this.#timers.clearInterval(this.#workerHeartbeatHandle)
    this.#workerHeartbeatHandle = null
  }

  async #beatWorker(): Promise<void> {
    try {
      await this.#serializeWorker(async () => {
        if (this.#worker === null) return
        this.#worker = await this.#workerRepo.heartbeat(this.#worker.workerId, this.#clock().toISOString())
      })
    } catch (error) {
      this.#logger.warn('worker heartbeat failed', {
        workerId: this.#config.workerId,
        error: errorMessage(error),
      })
    }
  }

  /** Serializes worker mutations so revision compare-and-swaps never race. */
  #serializeWorker<T>(task: () => Promise<T>): Promise<T> {
    const run = this.#workerQueue.then(task, task)
    this.#workerQueue = run.then(
      () => undefined,
      () => undefined
    )
    return run
  }

  async #transitionWorker(next: WorkerState): Promise<void> {
    try {
      await this.#serializeWorker(async () => {
        const current = await this.#workerRepo.get(this.#config.workerId)
        if (current === null) return
        this.#worker = current
        if (current.status === next) return
        if (!canTransitionWorker(current.status, next)) return
        this.#worker = await this.#workerRepo.transition(current.workerId, next, current.revision)
      })
    } catch (error) {
      this.#logger.warn('worker state transition failed', {
        workerId: this.#config.workerId,
        next,
        error: errorMessage(error),
      })
    }
  }

  // -------------------------------------------------------------------------
  // Claim loop
  // -------------------------------------------------------------------------

  #startLoop(): void {
    if (this.#loopRunning) return
    this.#loopRunning = true
    void this.#runLoop().finally(() => {
      this.#loopRunning = false
    })
  }

  async #runLoop(): Promise<void> {
    while (this.#status === 'running') {
      if (this.#active.size >= this.#concurrency) {
        await this.#waitSlot()
        continue
      }
      let claimed = false
      try {
        claimed = await this.#claimOnce()
      } catch (error) {
        this.#logger.error('worker claim failed', {
          workerId: this.#config.workerId,
          error: errorMessage(error),
        })
      }
      if (claimed) continue
      if (this.#status !== 'running') break
      await this.#sleep(this.#config.pollBackoffMs)
    }
  }

  /** Attempts a single claim; returns `true` when a job was spawned. */
  async #claimOnce(): Promise<boolean> {
    const acquired = await this.#leaseRepo.acquire({
      organizationId: this.#config.organizationId,
      workstreamId: this.#config.workstreamId,
      workerId: this.#config.workerId,
      environmentId: this.#config.environmentId ?? null,
      ttlMs: this.#config.leaseTtlMs,
      now: this.#clock(),
    })
    if (acquired === null) return false

    const workUnit = await this.#workUnitRepo.get(acquired.workUnitId)
    if (workUnit === null) {
      this.#logger.error('claimed work unit not found', { workUnitId: acquired.workUnitId })
      // We hold a lease with nothing to execute: hand it straight back.
      await this.#safeRelease(acquired.lease, 'failed')
      return false
    }

    this.#spawnJob(acquired, workUnit)
    return true
  }

  #spawnJob(acquired: AcquireLeaseResult, workUnit: WorkUnit): void {
    const job: ActiveJob = {
      workUnitId: acquired.workUnitId,
      leaseId: acquired.lease.leaseId,
      fencingToken: acquired.lease.fencingToken,
      controller: new AbortController(),
      fenced: false,
      stopRequested: false,
      heartbeatHandle: null,
    }
    this.#active.set(job.leaseId, job)
    if (this.#active.size === 1) void this.#transitionWorker('busy')
    void this.#processJob(job, acquired.lease, workUnit)
  }

  // -------------------------------------------------------------------------
  // Execution & lease heartbeat
  // -------------------------------------------------------------------------

  async #processJob(job: ActiveJob, lease: WorkUnitLease, workUnit: WorkUnit): Promise<void> {
    this.#startLeaseHeartbeat(job)
    try {
      let result: WorkExecutionResult | null = null
      let failure: unknown = null
      try {
        result = await this.#executor.execute(workUnit, job.controller.signal)
      } catch (error) {
        failure = error
      }
      this.#stopLeaseHeartbeat(job)

      if (job.fenced) {
        // FENCING GUARANTEE: never commit a terminal state for a lost lease.
        this.#logger.warn('lease fenced: discarding execution outcome', {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          fencingToken: job.fencingToken,
        })
        return
      }
      if (job.stopRequested) {
        // Forced drain: re-queue the unit so another worker can pick it up.
        await this.#safeRelease(lease, 'created')
        return
      }
      if (failure !== null) {
        this.#logger.error('work unit execution failed', {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          error: errorMessage(failure),
        })
        await this.#safeRelease(lease, 'failed')
        return
      }
      if (result !== null && result.status === 'completed') {
        if (result.payloadUpdate !== undefined) {
          await this.#applyPayloadUpdate(workUnit, result.payloadUpdate)
          if (job.fenced) return
        }
        await this.#safeRelease(lease, 'completed')
        return
      }
      this.#logger.warn('work unit reported a failure result', {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        code: result?.error?.code ?? null,
      })
      await this.#safeRelease(lease, 'failed')
    } catch (error) {
      this.#logger.error('worker job processing failed', {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        error: errorMessage(error),
      })
    } finally {
      this.#stopLeaseHeartbeat(job)
      this.#active.delete(job.leaseId)
      if (this.#active.size === 0 && this.#status === 'running') {
        void this.#transitionWorker('idle')
      }
      this.#notifySlot()
    }
  }

  #startLeaseHeartbeat(job: ActiveJob): void {
    if (job.heartbeatHandle !== null) return
    job.heartbeatHandle = this.#timers.setInterval(() => {
      void this.#renewLease(job)
    }, this.#config.heartbeatIntervalMs)
  }

  #stopLeaseHeartbeat(job: ActiveJob): void {
    if (job.heartbeatHandle === null) return
    this.#timers.clearInterval(job.heartbeatHandle)
    job.heartbeatHandle = null
  }

  async #renewLease(job: ActiveJob): Promise<void> {
    if (job.fenced) return
    try {
      await this.#leaseRepo.renew({
        organizationId: this.#config.organizationId,
        workstreamId: this.#config.workstreamId,
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        fencingToken: job.fencingToken,
        ttlMs: this.#config.leaseTtlMs,
        now: this.#clock(),
      })
    } catch (error) {
      const code = errorCode(error)
      if (code !== null && FENCING_ERROR_CODES.has(code)) {
        job.fenced = true
        this.#stopLeaseHeartbeat(job)
        job.controller.abort(error instanceof Error ? error : new Error(String(error)))
        this.#logger.warn('lease heartbeat fenced: aborting execution', {
          workUnitId: job.workUnitId,
          leaseId: job.leaseId,
          code,
        })
        return
      }
      this.#logger.warn('lease heartbeat failed', {
        workUnitId: job.workUnitId,
        leaseId: job.leaseId,
        error: errorMessage(error),
      })
    }
  }

  async #applyPayloadUpdate(workUnit: WorkUnit, payloadUpdate: Record<string, unknown>): Promise<void> {
    try {
      await this.#workUnitRepo.update(
        workUnit.workUnitId,
        { payload: { ...workUnit.payload, ...payloadUpdate } },
        workUnit.revision
      )
    } catch (error) {
      this.#logger.warn('work unit payload update failed', {
        workUnitId: workUnit.workUnitId,
        error: errorMessage(error),
      })
    }
  }

  /**
   * Releases a lease and swallows protocol errors: a fenced release is the
   * adapter's decision to reject, and it must not crash the loop.
   */
  async #safeRelease(lease: WorkUnitLease, resultStatus: LeaseReleaseResultStatus): Promise<void> {
    try {
      await this.#leaseRepo.release({
        organizationId: this.#config.organizationId,
        workstreamId: this.#config.workstreamId,
        workUnitId: lease.workUnitId,
        leaseId: lease.leaseId,
        fencingToken: lease.fencingToken,
        resultStatus,
        now: this.#clock(),
      })
    } catch (error) {
      this.#logger.error('lease release failed', {
        workUnitId: lease.workUnitId,
        leaseId: lease.leaseId,
        resultStatus,
        error: errorMessage(error),
      })
    }
  }

  // -------------------------------------------------------------------------
  // Drain helpers
  // -------------------------------------------------------------------------

  async #waitForIdle(): Promise<void> {
    while (this.#active.size > 0) {
      await this.#waitSlot()
    }
  }

  async #drainWithTimeout(timeoutMs: number): Promise<void> {
    if (this.#active.size === 0) return
    const drained = await Promise.race([this.#waitForIdle().then(() => true), this.#sleep(timeoutMs).then(() => false)])
    if (drained) return
    this.#logger.warn('drain timeout reached: aborting in-flight executions', {
      workerId: this.#config.workerId,
      activeCount: this.#active.size,
    })
    for (const job of [...this.#active.values()]) {
      job.stopRequested = true
      this.#stopLeaseHeartbeat(job)
      job.controller.abort(new Error('DRAIN_TIMEOUT'))
    }
    await this.#waitForIdle()
  }

  #waitSlot(): Promise<void> {
    return new Promise<void>((resolve) => {
      this.#slotWaiters.add(resolve)
    })
  }

  #notifySlot(): void {
    const waiters = [...this.#slotWaiters]
    this.#slotWaiters.clear()
    for (const waiter of waiters) waiter()
  }

  #sleep(ms: number): Promise<void> {
    if (ms <= 0) return Promise.resolve()
    return new Promise<void>((resolve) => {
      let handle: unknown = null
      let settled = false
      const wake = (): void => {
        if (settled) return
        settled = true
        this.#sleepers.delete(wake)
        if (handle !== null) this.#timers.clearTimeout(handle)
        resolve()
      }
      handle = this.#timers.setTimeout(wake, ms)
      this.#sleepers.add(wake)
    })
  }

  #wakeSleepers(): void {
    for (const wake of [...this.#sleepers]) wake()
  }
}
