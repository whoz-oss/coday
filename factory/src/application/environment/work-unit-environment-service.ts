/**
 * Work-unit environment provisioning application service.
 *
 * Owns the state machine that turns a validated descriptor into a provisioned
 * Git worktree, binds the controlling case, and reconciles the recorded intent
 * against the Git repository. The service never touches the filesystem or Git
 * directly: it drives an injected store and an injected Git worktree port, so
 * the same orchestration can run against the real adapter or a test harness.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/work-unit-environment-service.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 */

import { randomUUID } from 'node:crypto'
import type { EnvironmentSnapshot, StoreWriteResult } from '../../adapters/persistence/work-unit-environment-store.js'
import type {
  ValidationFailure,
  WorkUnitEnvironment,
  WorkUnitEnvironmentState,
} from '../../domain/environment/work-unit-environment.js'

/** What the Git port reports back once a worktree is ready for a descriptor. */
export interface WorkUnitEnvironmentFacts {
  repoRoot: string
  integrationBranch: string
  worktreePath: string
  baseCommit: string | null
  headCommit: string
}

/** Result of reconciling recorded intent against a Git repository. */
export type WorkUnitEnvironmentReconciliation =
  | { status: 'owned'; headCommit?: string; baseCommit?: string | null }
  | { status: 'uncertain' }
  | { status: 'absent' }

/** Store surface the service depends on. */
export interface WorkUnitEnvironmentServiceStore {
  read(namespaceId: string, environmentId: string): Promise<EnvironmentSnapshot | null>
  reserve(environment: unknown): Promise<StoreWriteResult | ValidationFailure>
  transition(
    namespaceId: string,
    environmentId: string,
    next: WorkUnitEnvironment,
    options?: { expectedRevision?: number; errorCode?: string }
  ): Promise<StoreWriteResult | ValidationFailure>
  list(namespaceId: string, options?: { states?: readonly WorkUnitEnvironmentState[] }): Promise<EnvironmentSnapshot[]>
}

/** Git worktree port the service drives. */
export interface WorkUnitEnvironmentGit {
  provisionWorktree(
    input: WorkUnitEnvironmentProvisionInput,
    onReady: (inspected: WorkUnitEnvironmentFacts) => Promise<void> | void
  ): Promise<WorkUnitEnvironmentFacts>
  reconcile(environment: WorkUnitEnvironment): Promise<WorkUnitEnvironmentReconciliation>
  removeWorktree(environment: WorkUnitEnvironment): Promise<unknown>
}

/** Input accepted by the service's `provision` operation. */
export interface WorkUnitEnvironmentProvisionInput {
  environmentId?: string
  workflowId?: string
  workUnitId: string
  namespaceId: string
  repoRoot: string
  integrationBranch: string
  branch: string
  worktreePath: string
  createdBy: string
  businessRef?: string
  businessType?: string
}

/** Injected fault seam, called between provisioning steps. */
export type WorkUnitEnvironmentServiceFault = (seam: string, details?: Record<string, unknown>) => Promise<void> | void

/** Dependencies of `WorkUnitEnvironmentService`. */
export interface WorkUnitEnvironmentServiceOptions {
  store: WorkUnitEnvironmentServiceStore
  git: WorkUnitEnvironmentGit
  clock?: () => Date
  idGenerator?: () => string
  fault?: WorkUnitEnvironmentServiceFault
}

/** A service failure carrying only a machine code. */
export interface ServiceFailure {
  ok: false
  error: { code: string }
}

/** Result of a provisioning attempt. */
export type ProvisionResult =
  | { ok: true; changed: boolean; snapshot: EnvironmentSnapshot; headCommit: string | null }
  | ServiceFailure

/** Result of a mutation that either wrote a snapshot or reported a failure. */
export type ServiceSnapshotResult = { ok: true; changed: boolean; snapshot: EnvironmentSnapshot } | ServiceFailure

/** Result of inspecting an environment against Git. */
export type InspectResult =
  | { ok: true; snapshot: EnvironmentSnapshot; reconciliation: WorkUnitEnvironmentReconciliation }
  | {
      ok: false
      error: { code: string }
      snapshot?: EnvironmentSnapshot
      reconciliation?: WorkUnitEnvironmentReconciliation
    }

const machine = (e: unknown): string => {
  const code = (e as { code?: unknown } | null | undefined)?.code
  return typeof code === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(code) ? code : 'GIT_FAILED'
}

const sameIdentity = (
  environment: WorkUnitEnvironment,
  input: WorkUnitEnvironmentProvisionInput,
  id: string
): boolean =>
  environment.environmentId === id &&
  environment.workflowId === (input.workflowId ?? environment.workflowId) &&
  environment.workUnitId === input.workUnitId &&
  environment.namespaceId === input.namespaceId &&
  environment.repoRoot === input.repoRoot &&
  environment.integrationBranch === input.integrationBranch &&
  environment.branch === input.branch &&
  environment.worktreePath === input.worktreePath &&
  environment.createdBy === input.createdBy

/** Orchestrates work-unit environment provisioning, binding and teardown. */
export class WorkUnitEnvironmentService {
  readonly store: WorkUnitEnvironmentServiceStore
  readonly git: WorkUnitEnvironmentGit
  readonly clock: () => Date
  readonly idGenerator: () => string
  readonly fault: WorkUnitEnvironmentServiceFault
  private readonly locks: Map<string, Promise<unknown>>

  constructor({
    store,
    git,
    clock = () => new Date(),
    idGenerator = () => randomUUID(),
    fault = async () => {},
  }: WorkUnitEnvironmentServiceOptions) {
    this.store = store
    this.git = git
    this.clock = clock
    this.idGenerator = idGenerator
    this.fault = fault
    this.locks = new Map()
  }

  private _locked<T>(ns: string, id: string, fn: () => Promise<T>): Promise<T> {
    const k = `${ns}\0${id}`
    const p = this.locks.get(k) ?? Promise.resolve()
    const o = p.then(fn)
    const t = o.catch(() => {})
    this.locks.set(k, t)
    return o.finally(() => {
      if (this.locks.get(k) === t) this.locks.delete(k)
    })
  }

  async provision(input: WorkUnitEnvironmentProvisionInput): Promise<ProvisionResult> {
    const id = input.environmentId ?? this.idGenerator()
    return this._locked(input.namespaceId, id, () => this._provision(input, id))
  }

  private async _provision(input: WorkUnitEnvironmentProvisionInput, id: string): Promise<ProvisionResult> {
    let current = await this.store.read(input.namespaceId, id)
    if (current) {
      const e = current.environment
      if (!sameIdentity(e, input, id)) return { ok: false, error: { code: 'ENVIRONMENT_IDENTITY_CONFLICT' } }
      if (e.lifecycleState !== 'provisioning')
        return e.lifecycleState === 'error'
          ? { ok: false, error: { code: 'ENVIRONMENT_ERROR' } }
          : { ok: false, error: { code: 'INVALID_PROVISION' } }
      const reconciliation = await this.git.reconcile(e)
      if (reconciliation.status === 'owned') {
        if (!e.baseCommit) return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' } }
        return { ok: true, changed: false, snapshot: current, headCommit: reconciliation.headCommit ?? null }
      }
      if (reconciliation.status === 'uncertain') return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' } }
    }
    let reserved = current
    try {
      const facts = await this.git.provisionWorktree(input, async (inspected) => {
        if (!reserved) {
          const descriptor: WorkUnitEnvironment = {
            schemaVersion: '1',
            environmentId: id,
            workUnitId: input.workUnitId,
            ...(input.workflowId ? { workflowId: input.workflowId } : {}),
            namespaceId: input.namespaceId,
            ...(input.businessRef ? { businessRef: input.businessRef } : {}),
            ...(input.businessType ? { businessType: input.businessType } : {}),
            repoRoot: inspected.repoRoot,
            integrationBranch: inspected.integrationBranch,
            branch: input.branch,
            worktreePath: inspected.worktreePath,
            baseCommit: inspected.baseCommit,
            createdAt: this.clock().toISOString(),
            createdBy: input.createdBy,
            lifecycleState: 'provisioning',
          }
          const r = await this.store.reserve(descriptor)
          if (!r.ok) throw Object.assign(new Error('STORE_REJECTED'), { code: 'STORE_REJECTED' })
          reserved = r.snapshot
        }
      })
      await this.fault('after-git-add', { namespaceId: input.namespaceId, environmentId: id })
      current = await this.store.read(input.namespaceId, id)
      if (current!.environment.baseCommit !== facts.baseCommit)
        return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' } }
      return { ok: true, changed: false, snapshot: current!, headCommit: facts.headCommit }
    } catch (error) {
      current = await this.store.read(input.namespaceId, id)
      if (current?.environment.lifecycleState === 'provisioning') {
        let reconciliation: WorkUnitEnvironmentReconciliation
        try {
          reconciliation = await this.git.reconcile(current.environment)
        } catch {
          return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' } }
        }
        if (reconciliation.status === 'owned') return { ok: false, error: { code: 'POST_ADD_RECOVERY_REQUIRED' } }
        if (reconciliation.status === 'uncertain') return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' } }
        await this.store.transition(
          input.namespaceId,
          id,
          { ...current.environment, lifecycleState: 'error' },
          { expectedRevision: current.revision, errorCode: machine(error) }
        )
      }
      throw error
    }
  }

  async bindParentCase(ns: string, id: string, caseId: string): Promise<ServiceSnapshotResult> {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id)
      if (c?.environment.lifecycleState === 'active' && c.environment.parentCaseId === caseId)
        return { ok: true, changed: false, snapshot: c }
      if (!c || c.environment.lifecycleState !== 'provisioning' || !c.environment.baseCommit)
        return { ok: false, error: { code: 'INVALID_BIND' } }
      const existing = await this.store.list(ns, { states: ['active'] })
      if (
        existing.some(
          (snapshot) => snapshot.environment.environmentId !== id && snapshot.environment.parentCaseId === caseId
        )
      )
        return { ok: false, error: { code: 'WRITER_ALREADY_ACTIVE' } }
      if (
        existing.some(
          (snapshot) =>
            snapshot.environment.environmentId !== id &&
            snapshot.environment.worktreePath === c.environment.worktreePath
        )
      )
        return { ok: false, error: { code: 'WORKTREE_ALREADY_ACTIVE' } }
      return this.store.transition(
        ns,
        id,
        { ...c.environment, parentCaseId: caseId, lifecycleState: 'active' },
        { expectedRevision: c.revision }
      )
    })
  }

  async inspect(ns: string, id: string): Promise<InspectResult> {
    return this._locked(ns, id, async () => {
      const snapshot = await this.store.read(ns, id)
      if (!snapshot) return { ok: false, error: { code: 'NOT_FOUND' } }
      if (snapshot.environment.lifecycleState === 'removed')
        return { ok: true, snapshot, reconciliation: { status: 'absent' } }
      const reconciliation = await this.git.reconcile(snapshot.environment)
      if (reconciliation.status !== 'owned')
        return { ok: false, error: { code: 'OWNERSHIP_UNCERTAIN' }, snapshot, reconciliation }
      return { ok: true, snapshot, reconciliation }
    })
  }

  async setState(ns: string, id: string, state: WorkUnitEnvironmentState): Promise<ServiceSnapshotResult> {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id)
      return c
        ? this.store.transition(ns, id, { ...c.environment, lifecycleState: state }, { expectedRevision: c.revision })
        : { ok: false, error: { code: 'NOT_FOUND' } }
    })
  }

  async remove(ns: string, id: string): Promise<ServiceSnapshotResult> {
    return this._locked(ns, id, async () => {
      const c = await this.store.read(ns, id)
      if (!c) return { ok: false, error: { code: 'NOT_FOUND' } }
      if (c.environment.lifecycleState === 'removed') return { ok: true, changed: false, snapshot: c }
      if (!['completed', 'abandoned', 'error'].includes(c.environment.lifecycleState))
        return { ok: false, error: { code: 'INVALID_REMOVE' } }
      await this.git.removeWorktree(c.environment)
      return this.store.transition(
        ns,
        id,
        { ...c.environment, lifecycleState: 'removed' },
        { expectedRevision: c.revision }
      )
    })
  }

  async listRecoveryCandidates(ns: string): Promise<EnvironmentSnapshot[]> {
    return this.store.list(ns, { states: ['provisioning', 'error'] })
  }
}
