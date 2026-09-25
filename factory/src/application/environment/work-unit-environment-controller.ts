/**
 * Trusted Factory control-plane for work-unit environments.
 *
 * The controller is the only component allowed to decide where a worktree may
 * live and which workflow may own one: repository and destination roots come
 * only from its injected policy, never from the request body. It exposes the
 * HTTP-shaped operations (`initialize`, `provision`, `get`, `reconcile`,
 * `release`) and the request dispatcher `handleWorkUnitEnvironmentRequest`.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`; `factory/lib/work-unit-environment-controller.mjs`
 * is a stateless compatibility facade re-exporting from that bundle.
 */

import type { EnvironmentSnapshot } from '../../adapters/persistence/work-unit-environment-store.js'
import type { WorkUnitEnvironment } from '../../domain/environment/work-unit-environment.js'
import {
  WorkUnitEnvironmentService,
  type InspectResult,
  type WorkUnitEnvironmentGit,
  type WorkUnitEnvironmentProvisionInput,
  type WorkUnitEnvironmentReconciliation,
  type WorkUnitEnvironmentServiceFault,
  type WorkUnitEnvironmentServiceStore,
} from './work-unit-environment-service.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const ALLOWED = new Set(['workflowId', 'workUnitId', 'integrationBranch', 'branch'])

/** Environment binding recorded on a workflow instance. */
export interface WorkflowEnvironmentRef {
  environmentId: string
  environmentHash: string
}

/** Workflow store surface the controller reads and binds through. */
export interface WorkUnitEnvironmentWorkflowStore {
  read(
    namespaceId: string,
    workflowId: string
  ): Promise<
    | {
        instance?: {
          controllerExecution?: { kind?: string; caseId?: string }
          environmentRef?: WorkflowEnvironmentRef
        }
      }
    | null
    | undefined
  >
  bindEnvironment(
    namespaceId: string,
    workflowId: string,
    ref: WorkflowEnvironmentRef
  ): Promise<{ ok: boolean; error?: { code: string } }>
}

/** Policy that resolves the only roots a worktree may use. */
export interface WorkUnitEnvironmentPolicy {
  resolve(
    namespaceId: string,
    body: Record<string, unknown>
  ):
    | Promise<{ repoRoot?: string; worktreePath?: string } | null | undefined>
    | { repoRoot?: string; worktreePath?: string }
    | null
}

/** Store surface the controller needs (read plus initialization). */
export interface WorkUnitEnvironmentControllerStore extends WorkUnitEnvironmentServiceStore {
  initialize(): Promise<void>
}

/** Dependencies of `WorkUnitEnvironmentController`. */
export interface WorkUnitEnvironmentControllerOptions {
  store: WorkUnitEnvironmentControllerStore
  git: WorkUnitEnvironmentGit
  policy: WorkUnitEnvironmentPolicy
  workflowStore?: WorkUnitEnvironmentWorkflowStore
  clock?: () => Date
  idGenerator?: () => string
  fault?: WorkUnitEnvironmentServiceFault
}

/** HTTP-shaped result of a controller operation. */
export type ControllerResult =
  | { ok: true; status: number; data: PublicEnvironment }
  | { ok: false; status: number; error: { code: string } }

/** The public projection of an environment snapshot returned to callers. */
export interface PublicEnvironment {
  revision: number
  environment: WorkUnitEnvironment
  reconciliation: WorkUnitEnvironmentReconciliation | null
  headCommit: string | null
  fileAccess: { status: 'bound' | 'blocked'; code: string | null; rootPath: string }
}

/** Minimal logger shape used by the request dispatcher. */
export interface WorkUnitEnvironmentLogger {
  error(...args: unknown[]): void
}

/** Arguments accepted by the request dispatcher. */
export interface HandleWorkUnitEnvironmentRequestInput {
  method: string
  path: string
  url: URL
  readBody: () => Promise<unknown>
  send: (status: number, body: unknown) => void
  controller: WorkUnitEnvironmentController
  identity: () => Promise<{ namespaceId: string; caseId: string; actorId: string } | null>
  log?: WorkUnitEnvironmentLogger
}

const error = (send: (status: number, body: unknown) => void, status: number, code: string, message: string): void =>
  send(status, { error: { code, message } })

const publicEnvironment = (result: {
  snapshot: EnvironmentSnapshot
  reconciliation?: WorkUnitEnvironmentReconciliation | null
  headCommit?: string | null
}): PublicEnvironment => {
  const bound = result.snapshot.environment.lifecycleState === 'active' && result.reconciliation?.status === 'owned'
  return {
    revision: result.snapshot.revision,
    environment: result.snapshot.environment,
    reconciliation: result.reconciliation ?? null,
    headCommit:
      (result.reconciliation?.status === 'owned' ? result.reconciliation.headCommit : undefined) ??
      result.headCommit ??
      null,
    fileAccess: {
      status: bound ? 'bound' : 'blocked',
      code: bound ? null : 'ENVIRONMENT_NOT_BOUND',
      rootPath: result.snapshot.environment.worktreePath,
    },
  }
}

/** Trusted Factory control-plane. Repository and destination roots come only from its injected policy. */
export class WorkUnitEnvironmentController {
  readonly store: WorkUnitEnvironmentControllerStore
  readonly git: WorkUnitEnvironmentGit
  readonly policy: WorkUnitEnvironmentPolicy
  readonly workflowStore: WorkUnitEnvironmentWorkflowStore | undefined
  readonly service: WorkUnitEnvironmentService

  constructor({ store, git, policy, workflowStore, clock, idGenerator, fault }: WorkUnitEnvironmentControllerOptions) {
    this.store = store
    this.git = git
    this.policy = policy
    this.workflowStore = workflowStore
    this.service = new WorkUnitEnvironmentService({
      store,
      git,
      ...(clock ? { clock } : {}),
      ...(idGenerator ? { idGenerator } : {}),
      ...(fault ? { fault } : {}),
    })
  }

  async initialize(): Promise<void> {
    await this.store.initialize()
  }

  async provision({
    namespaceId,
    caseId,
    createdBy,
    body,
  }: {
    namespaceId: string
    caseId: string
    createdBy: string
    body: unknown
  }): Promise<ControllerResult> {
    if (!UUID.test(namespaceId ?? '') || !UUID.test(caseId ?? '') || !SAFE.test(createdBy ?? ''))
      return { ok: false, status: 400, error: { code: 'INVALID_TRUST_CONTEXT' } }
    const requestBody = (body ?? {}) as Record<string, unknown>
    if (
      !body ||
      typeof body !== 'object' ||
      Array.isArray(body) ||
      Object.keys(requestBody).some((key) => !ALLOWED.has(key)) ||
      !SAFE.test((requestBody.workflowId as string) ?? '') ||
      !SAFE.test((requestBody.workUnitId as string) ?? '')
    )
      return { ok: false, status: 400, error: { code: 'INVALID_ENVIRONMENT_REQUEST' } }
    const roots = await this.policy.resolve(namespaceId, requestBody)
    if (!roots?.repoRoot || !roots?.worktreePath)
      return { ok: false, status: 422, error: { code: 'ENVIRONMENT_POLICY_UNAVAILABLE' } }
    const workflow = await this.workflowStore?.read(namespaceId, requestBody.workflowId as string)
    if (
      !workflow?.instance ||
      workflow.instance.controllerExecution?.kind !== 'agentos' ||
      workflow.instance.controllerExecution.caseId !== caseId
    )
      return { ok: false, status: 409, error: { code: 'WORKFLOW_ENVIRONMENT_CONTEXT_MISMATCH' } }
    const environmentId = `${requestBody.workflowId as string}-${requestBody.workUnitId as string}`
    const result = await this.service.provision({
      ...(requestBody as unknown as WorkUnitEnvironmentProvisionInput),
      environmentId,
      namespaceId,
      repoRoot: roots.repoRoot,
      worktreePath: roots.worktreePath,
      createdBy,
    })
    if (!result.ok)
      return {
        ok: false,
        status: result.error.code === 'ENVIRONMENT_IDENTITY_CONFLICT' ? 409 : 422,
        error: result.error,
      }
    const bound = await this.service.bindParentCase(namespaceId, environmentId, caseId)
    if (!bound.ok) return { ok: false, status: 409, error: bound.error }
    const inspected = await this.service.inspect(namespaceId, environmentId)
    if (!inspected.ok) return { ok: false, status: 409, error: { code: 'ENVIRONMENT_NOT_BOUND' } }
    const linked = await this.workflowStore?.bindEnvironment(namespaceId, requestBody.workflowId as string, {
      environmentId,
      environmentHash: inspected.snapshot.environmentHash,
    })
    if (!linked?.ok) return { ok: false, status: 409, error: linked?.error ?? { code: 'ENVIRONMENT_NOT_BOUND' } }
    return { ok: true, status: result.changed ? 201 : 200, data: publicEnvironment(inspected) }
  }

  async get(namespaceId: string, workflowId: string): Promise<ControllerResult> {
    if (!UUID.test(namespaceId ?? '') || !SAFE.test(workflowId ?? ''))
      return { ok: false, status: 400, error: { code: 'INVALID_LOOKUP' } }
    const workflow = await this.workflowStore?.read(namespaceId, workflowId)
    const ref = workflow?.instance?.environmentRef
    if (!ref) return { ok: false, status: 404, error: { code: 'ENVIRONMENT_NOT_FOUND' } }
    const snapshot = await this.store.read(namespaceId, ref.environmentId)
    if (!snapshot || snapshot.environmentHash !== ref.environmentHash)
      return { ok: false, status: 409, error: { code: 'ENVIRONMENT_BINDING_UNCERTAIN' } }
    const result = await this.service.inspect(namespaceId, ref.environmentId)
    if (!result.ok) return { ok: false, status: 409, error: { code: result.error.code } }
    return { ok: true, status: 200, data: publicEnvironment(result) }
  }

  async reconcile(namespaceId: string, workflowId: string): Promise<ControllerResult> {
    return this.get(namespaceId, workflowId)
  }

  async release(namespaceId: string, workflowId: string, state: string): Promise<ControllerResult> {
    if (!['completed', 'abandoned'].includes(state))
      return { ok: false, status: 400, error: { code: 'INVALID_RELEASE_STATE' } }
    const found = await this.get(namespaceId, workflowId)
    if (!found.ok) return found
    const environmentId = found.data.environment.environmentId
    const transitioned = await this.service.setState(
      namespaceId,
      environmentId,
      state as WorkUnitEnvironment['lifecycleState']
    )
    if (!transitioned.ok) return { ok: false, status: 409, error: transitioned.error }
    return {
      ok: true,
      status: 200,
      data: publicEnvironment({ snapshot: transitioned.snapshot, reconciliation: found.data.reconciliation }),
    }
  }
}

/**
 * Dispatches `/api/factory/workflows/:id/environment/*` requests against the
 * controller. Returns `false` when the path is not an environment route so the
 * caller can fall through to another handler.
 */
export async function handleWorkUnitEnvironmentRequest({
  method,
  path,
  url,
  readBody,
  send,
  controller,
  identity,
  log = console,
}: HandleWorkUnitEnvironmentRequestInput): Promise<boolean> {
  const provision = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/provision$/)
  const reconcile = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/reconcile$/)
  const release = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/release$/)
  const detail = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment$/)
  if (!provision && !reconcile && !release && !detail) return false
  try {
    if (provision && method === 'POST') {
      const trust = await identity()
      if (!trust) {
        error(send, 401, 'TRUST_CONTEXT_UNAVAILABLE', 'Trusted AgentOS execution context is required.')
        return true
      }
      const result = await controller.provision({
        namespaceId: trust.namespaceId,
        caseId: trust.caseId,
        createdBy: trust.actorId,
        body: await readBody(),
      })
      if (result.ok) send(result.status, { data: result.data })
      else error(send, result.status, result.error.code, 'Environment provisioning was rejected.')
      return true
    }
    const trust = await identity()
    if (!trust) {
      error(send, 401, 'TRUST_CONTEXT_UNAVAILABLE', 'Trusted AgentOS execution context is required.')
      return true
    }
    const namespaceId = trust.namespaceId
    const workflowId = decodeURIComponent((reconcile ?? release ?? detail)![1] as string)
    if (reconcile && method === 'POST') {
      const result = await controller.reconcile(namespaceId, workflowId)
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.')
        return true
      }
      if (result.ok) send(200, { data: result.data })
      else error(send, result.status, result.error.code, 'Environment reconciliation failed closed.')
      return true
    }
    if (release && method === 'POST') {
      const current = await controller.get(namespaceId, workflowId)
      if (current.ok && current.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.')
        return true
      }
      const body = current.ok ? ((await readBody()) as { state: string }) : null
      const result = current.ok ? await controller.release(namespaceId, workflowId, body!.state) : current
      if (result.ok) send(200, { data: result.data })
      else error(send, result.status, result.error.code, 'Environment release was rejected.')
      return true
    }
    if (detail && method === 'GET') {
      const result = await controller.get(namespaceId, workflowId)
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId) {
        error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.')
        return true
      }
      if (result.ok) send(200, { data: result.data })
      else error(send, result.status, result.error.code, 'Environment is unavailable.')
      return true
    }
    error(send, 405, 'METHOD_NOT_ALLOWED', 'Method not allowed.')
    return true
  } catch (cause) {
    log.error('Work unit environment failure', { code: (cause as { code?: string } | null)?.code ?? 'UNEXPECTED' })
    error(send, 500, 'ENVIRONMENT_STORAGE_FAILURE', 'Environment control-plane is unavailable.')
    return true
  }
}
