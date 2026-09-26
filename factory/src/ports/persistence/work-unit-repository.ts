import type { WorkUnit, WorkUnitCreateInput, WorkUnitState } from '../../domain/work-unit.js'

/**
 * Persistence port for the work-unit context.
 *
 * A work unit is a revisioned, tenant-scoped aggregate persisted in the V6/V7
 * `work_units` table. The port exposes identity lookup, creation, patching and
 * the lifecycle transition, never the SQL columns or the optimistic-locking
 * mechanics: adapters translate a stale `expectedRevision` into the shared
 * `REVISION_CONFLICT` error code.
 *
 * Tenant scoping (`organizationId`, `workstreamId`) is fixed at instantiation
 * time, exactly like the existing SQL adapters: a repository is wired for one
 * tenant/workstream and every operation is implicitly scoped to it.
 */

/** Tenant scope a work-unit repository is bound to. */
export interface WorkUnitRepositoryScope {
  readonly organizationId: string
  readonly workstreamId: string
}

/** Optional narrowing applied by {@link WorkUnitRepository.list}. */
export interface WorkUnitListFilter {
  /** One state or a set of states to keep. */
  status?: WorkUnitState | readonly WorkUnitState[]
  /** Keep only units whose `priority` is at least this value. */
  priorityMin?: number
  /** Maximum number of units returned after ordering. */
  limit?: number
}

export interface WorkUnitRepository {
  /** The tenant scope every operation is bound to. */
  readonly scope: WorkUnitRepositoryScope
  /** The work unit identified by `workUnitId`, or `null` when absent. */
  get(workUnitId: string): Promise<WorkUnit | null>
  /** Creates a fresh work unit at revision 1 with the V7 defaults applied. */
  create(input: WorkUnitCreateInput): Promise<WorkUnit>
  /** Patches mutable fields; fails with `REVISION_CONFLICT` on a stale revision. */
  update(workUnitId: string, patch: Partial<WorkUnit>, expectedRevision: number): Promise<WorkUnit>
  /**
   * Applies one lifecycle transition and optionally merges a payload update.
   * Fails with `INVALID_TRANSITION` / `INVALID_STATE` / `REVISION_CONFLICT`.
   */
  transition(
    workUnitId: string,
    nextState: WorkUnitState,
    expectedRevision: number,
    payloadUpdate?: Record<string, unknown>
  ): Promise<WorkUnit>
  /** Tenant-scoped units, ordered by descending priority then eligibility. */
  list(filter?: WorkUnitListFilter): Promise<WorkUnit[]>
}
