import type { Worker, WorkerCreateInput, WorkerState } from '../../domain/worker.js'

/**
 * Persistence port for the worker context.
 *
 * A worker is a revisioned, organization-scoped node persisted in the V6/V7
 * `workers` table. The port exposes identity lookup, creation, patching, the
 * lifecycle transition and heartbeat recording, never the SQL columns or the
 * optimistic-locking mechanics: adapters translate a stale `expectedRevision`
 * into the shared `REVISION_CONFLICT` error code.
 *
 * Tenant scoping (`organizationId`) is fixed at instantiation time, exactly
 * like the existing SQL adapters: a repository is wired for one organization
 * and every operation is implicitly scoped to it.
 */

/** Tenant scope a worker repository is bound to. */
export interface WorkerRepositoryScope {
  readonly organizationId: string
}

/** Optional narrowing applied by {@link WorkerRepository.list}. */
export interface WorkerListFilter {
  /** One state or a set of states to keep. */
  status?: WorkerState | readonly WorkerState[]
  /** Keep only the nodes of this worker type. */
  workerType?: string
}

export interface WorkerRepository {
  /** The tenant scope every operation is bound to. */
  readonly scope: WorkerRepositoryScope
  /** The worker identified by `workerId`, or `null` when absent. */
  get(workerId: string): Promise<Worker | null>
  /** Creates a fresh worker at revision 1 with the V7 defaults applied. */
  create(input: WorkerCreateInput): Promise<Worker>
  /** Patches mutable fields; fails with `REVISION_CONFLICT` on a stale revision. */
  update(workerId: string, patch: Partial<Worker>, expectedRevision: number): Promise<Worker>
  /**
   * Applies one lifecycle transition and optionally merges a payload update.
   * Fails with `INVALID_TRANSITION` / `INVALID_STATE` / `REVISION_CONFLICT`.
   */
  transition(
    workerId: string,
    nextState: WorkerState,
    expectedRevision: number,
    payloadUpdate?: Record<string, unknown>
  ): Promise<Worker>
  /**
   * Records a heartbeat; when `expectedRevision` is provided a stale revision
   * fails with `REVISION_CONFLICT` instead of updating.
   */
  heartbeat(workerId: string, heartbeatAt: string, expectedRevision?: number): Promise<Worker>
  /** Organization-scoped workers, optionally narrowed by state and type. */
  list(filter?: WorkerListFilter): Promise<Worker[]>
}
