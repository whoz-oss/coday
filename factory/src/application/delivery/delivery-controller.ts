/**
 * Trusted Factory control-plane for deliveries.
 *
 * The controller binds a delivery to its controlling execution (namespace,
 * workflow, parent case, dashboard runtime) and its environment (worktree,
 * branch, commits), drives git checkpoints / pushes / pull requests through
 * the injected adapters, journals every outcome and applies the ordered,
 * evidence-gated promotion policy.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-controller.mjs` is a stateless compatibility facade
 * re-exporting from that bundle.
 */

import { hashDeliveryDefinition, validateDeliveryDefinition } from '../../domain/delivery/delivery-definition.js'
import type { DeliveryDefinition, DeliveryDefinitionValidation } from '../../domain/delivery/delivery-definition.js'
import { validateDeliveryPromotionRequest } from '../../domain/delivery/delivery-policy.js'
import type {
  DeliveryEvidenceItem,
  DeliveryExecutionContext,
  HashedDeliveryDefinition,
} from '../../domain/delivery/delivery-policy.js'
import type {
  DeliveryOperationProjection,
  DeliverySnapshot,
  DeliveryStore,
} from '../../adapters/persistence/delivery-store.js'
import type { DeliveryEvidenceStore } from '../../adapters/persistence/delivery-evidence-store.js'
import type {
  DeliveryGitControlPlane,
  DeliveryGitCheckpointResult,
  DeliveryGitPushResult,
} from '../../adapters/delivery/delivery-git-control-plane.js'
import type { DeliveryPullRequestAdapter } from '../../adapters/delivery/delivery-pr-adapter.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const FORBIDDEN = new Set([
  'root',
  'repoRoot',
  'worktreePath',
  'remote',
  'url',
  'owner',
  'repo',
  'command',
  'credentials',
  'token',
])
const rejectUntrusted = (body: unknown): boolean =>
  !body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some((key) => FORBIDDEN.has(key))

/** The server-trusted identity attached to a delivery request. */
export interface DeliveryTrustIdentity {
  namespaceId: string
  caseId: string
  actorId?: string
  resolvedActorId?: string
}

/** The environment surface the controller binds against. */
export interface DeliveryEnvironmentInfo {
  environmentId: string
  parentCaseId: string
  workflowId: string
  worktreePath: string
  branch: string
  baseCommit: string
  createdAt: string
  [key: string]: unknown
}

/** The environment reconciliation surface the controller asserts. */
export interface DeliveryEnvironmentReconciliation {
  status: string
  worktreePath: string
  headCommit: string
  [key: string]: unknown
}

/** Result of the environment controller lookup the controller consumes. */
export interface DeliveryEnvironmentLookup {
  ok: boolean
  data?: {
    environment: DeliveryEnvironmentInfo
    reconciliation?: DeliveryEnvironmentReconciliation | null
  }
}

/** Environment controller surface the delivery controller depends on. */
export interface DeliveryEnvironmentControllerLike {
  get(namespaceId: string, workflowId: string): Promise<DeliveryEnvironmentLookup>
}

/** Workflow instance surface the controller reads and binds through. */
export interface DeliveryWorkflowInstance {
  controllerExecution?: { caseId?: string; runtimeId?: string }
  environmentRef?: { environmentHash?: string }
  deliveryRef?: { deliveryId: string; definitionHash: string }
  [key: string]: unknown
}

/** Workflow snapshot surface the controller reads. */
export interface DeliveryWorkflowSnapshot {
  instance: DeliveryWorkflowInstance
  [key: string]: unknown
}

/** Workflow store surface the controller reads and binds through. */
export interface DeliveryWorkflowStoreLike {
  read(namespaceId: string, workflowId: string): Promise<DeliveryWorkflowSnapshot | null | undefined>
  bindDelivery(
    namespaceId: string,
    workflowId: string,
    ref: { deliveryId: string; definitionHash: string }
  ): Promise<{ ok: boolean; error?: { code: string }; snapshot?: DeliveryWorkflowSnapshot }>
}

/** Trusted configuration surface the controller consumes. */
export interface DeliveryTrustedConfiguration {
  pullRequest?: { owner: string; repo: string; baseBranch: string }
  [key: string]: unknown
}

/** Dependencies of `DeliveryController`. */
export interface DeliveryControllerOptions {
  store: DeliveryStore
  evidenceStore: DeliveryEvidenceStore
  environmentController: DeliveryEnvironmentControllerLike
  workflowStore: DeliveryWorkflowStoreLike
  git: DeliveryGitControlPlane
  pullRequests: DeliveryPullRequestAdapter
  definition: unknown
  trustedConfiguration: DeliveryTrustedConfiguration
}

/** A controller failure with an HTTP status and a machine-coded error. */
export interface DeliveryControllerFailure {
  ok: false
  status: number
  error: { code: string; [key: string]: unknown }
}

/** A successful delivery resolution: snapshot plus environment binding. */
export interface DeliveryResolution {
  ok: true
  snapshot: DeliverySnapshot
  environment: DeliveryEnvironmentInfo
  reconciliation: DeliveryEnvironmentReconciliation
}

/** HTTP-shaped result of a controller operation. */
export type DeliveryControllerResult = { ok: true; status: number; data: unknown } | DeliveryControllerFailure

/** Minimal logger shape used by the request dispatcher. */
export interface DeliveryControllerLogger {
  error(...args: unknown[]): void
}

/** Arguments accepted by the delivery request dispatcher. */
export interface HandleDeliveryRequestInput {
  method: string
  path: string
  readBody: () => Promise<unknown>
  send: (status: number, body: unknown) => void
  identity: () => Promise<DeliveryTrustIdentity | null>
  controller: DeliveryController
  log?: DeliveryControllerLogger
}

export class DeliveryController {
  private readonly store: DeliveryStore
  private readonly evidenceStore: DeliveryEvidenceStore
  private readonly environmentController: DeliveryEnvironmentControllerLike
  private readonly workflowStore: DeliveryWorkflowStoreLike
  private readonly git: DeliveryGitControlPlane
  private readonly pullRequests: DeliveryPullRequestAdapter
  readonly definition: Readonly<HashedDeliveryDefinition>
  private readonly configuration: DeliveryTrustedConfiguration

  constructor({
    store,
    evidenceStore,
    environmentController,
    workflowStore,
    git,
    pullRequests,
    definition,
    trustedConfiguration,
  }: DeliveryControllerOptions) {
    const validated: DeliveryDefinitionValidation = validateDeliveryDefinition(definition)
    if (!validated.ok) throw new Error('INVALID_DELIVERY_DEFINITION')
    this.store = store
    this.evidenceStore = evidenceStore
    this.environmentController = environmentController
    this.workflowStore = workflowStore
    this.git = git
    this.pullRequests = pullRequests
    this.definition = Object.freeze({
      ...validated.definition,
      definitionHash: hashDeliveryDefinition(validated.definition),
    }) as Readonly<HashedDeliveryDefinition>
    this.configuration = trustedConfiguration
  }
  async initialize(): Promise<void> {
    await this.store.initialize()
  }

  async resolve(
    identity: DeliveryTrustIdentity,
    workflowId: string
  ): Promise<DeliveryResolution | DeliveryControllerFailure> {
    if (
      !identity ||
      !UUID.test(identity.namespaceId ?? '') ||
      !UUID.test(identity.caseId ?? '') ||
      !SAFE.test(workflowId ?? '')
    )
      return { ok: false, status: 400, error: { code: 'INVALID_TRUST_CONTEXT' } }
    let workflow = await this.workflowStore.read(identity.namespaceId, workflowId),
      environmentResult = await this.environmentController.get(identity.namespaceId, workflowId)
    if (!workflow?.instance || !environmentResult.ok)
      return { ok: false, status: 409, error: { code: 'DELIVERY_BINDING_UNAVAILABLE' } }
    const environment = environmentResult.data?.environment as DeliveryEnvironmentInfo,
      reconciliation = environmentResult.data?.reconciliation
    if (
      workflow.instance.controllerExecution?.caseId !== identity.caseId ||
      environment.parentCaseId !== identity.caseId ||
      environment.workflowId !== workflowId ||
      reconciliation?.status !== 'owned' ||
      reconciliation.worktreePath !== environment.worktreePath
    )
      return { ok: false, status: 409, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
    const deliveryId = `${workflowId}-delivery`,
      runtimeId = workflow.instance.controllerExecution.runtimeId ?? 'agentos',
      environmentHash = workflow.instance.environmentRef?.environmentHash
    if (!environmentHash) return { ok: false, status: 409, error: { code: 'DELIVERY_BINDING_UNAVAILABLE' } }
    const existing = await this.store.read(identity.namespaceId, deliveryId)
    if (!existing) {
      const created = await this.store.create({
        schemaVersion: '1',
        deliveryId,
        namespaceId: identity.namespaceId,
        workflowId,
        environmentId: environment.environmentId,
        environmentHash,
        parentCaseId: identity.caseId,
        runtimeId,
        worktreePath: environment.worktreePath,
        branch: environment.branch,
        baseCommit: environment.baseCommit,
        headCommit: reconciliation.headCommit,
        definitionType: this.definition.deliveryType,
        definitionVersion: this.definition.version,
        definitionHash: this.definition.definitionHash,
        stage: 'implementation-ready',
        revision: 1,
        evidenceIds: [],
        createdAt: environment.createdAt,
        updatedAt: new Date().toISOString(),
        git: { checkpoint: null, push: null, pullRequest: null },
        artifact: { state: 'pending' },
        release: { state: 'pending' },
        deployment: { state: 'pending' },
        verification: { state: 'pending' },
        blockers: [],
      })
      if (!created.ok) return { ok: false, status: 409, error: created.error }
      const linked = await this.workflowStore.bindDelivery(identity.namespaceId, workflowId, {
        deliveryId,
        definitionHash: this.definition.definitionHash,
      })
      if (!linked.ok) return { ok: false, status: 409, error: linked.error as { code: string } }
      return { ok: true, snapshot: created.snapshot as DeliverySnapshot, environment, reconciliation }
    }
    let deliveryRef = workflow.instance.deliveryRef
    if (!deliveryRef) {
      const linked = await this.workflowStore.bindDelivery(identity.namespaceId, workflowId, {
        deliveryId,
        definitionHash: existing.definitionHash as string,
      })
      if (!linked.ok) return { ok: false, status: 409, error: linked.error as { code: string } }
      workflow = linked.snapshot as DeliveryWorkflowSnapshot
      deliveryRef = workflow.instance.deliveryRef
    } else if (deliveryRef.deliveryId !== existing.deliveryId || deliveryRef.definitionHash !== existing.definitionHash)
      return { ok: false, status: 409, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
    if (
      existing.environmentId !== environment.environmentId ||
      existing.environmentHash !== workflow.instance.environmentRef?.environmentHash ||
      existing.worktreePath !== environment.worktreePath ||
      existing.parentCaseId !== identity.caseId ||
      existing.runtimeId !== runtimeId
    )
      return { ok: false, status: 409, error: { code: 'DELIVERY_SCOPE_MISMATCH' } }
    // Block if a previous Git operation left an indeterminate state.
    const hasIndeterminate = await this.store.hasIndeterminateOperation(identity.namespaceId, deliveryId)
    if (hasIndeterminate) return { ok: false, status: 409, error: { code: 'DELIVERY_INDETERMINATE_OPERATION_PENDING' } }
    if (existing.headCommit !== reconciliation.headCommit)
      return {
        ok: false,
        status: 409,
        error: {
          code: 'DELIVERY_HEAD_RECONCILIATION_REQUIRED',
          expectedHead: existing.headCommit,
          observedHead: reconciliation.headCommit,
        },
      }
    return { ok: true, snapshot: existing, environment, reconciliation }
  }

  async status(identity: DeliveryTrustIdentity, workflowId: string): Promise<DeliveryControllerResult> {
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const projection: DeliveryOperationProjection = await this.store.inspectDeliveryOperations(
      identity.namespaceId,
      resolved.snapshot.deliveryId
    )
    return {
      ok: true,
      status: 200,
      data: {
        ...resolved.snapshot,
        deliveryOperations: projection.operations,
        unresolvedIndeterminate: projection.unresolvedIndeterminate,
        rollbackRequests: projection.rollbackRequests,
      },
    }
  }

  async checkpoint(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryControllerResult> {
    if (
      rejectUntrusted(body) ||
      Object.keys(body as Record<string, unknown>).some(
        (key) => !['expectedHead', 'message', 'claims', 'idempotencyKey'].includes(key)
      )
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const input = body as Record<string, unknown>
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    if (
      input.expectedHead !== resolved.reconciliation.headCommit ||
      resolved.snapshot.headCommit !== input.expectedHead
    )
      return { ok: false, status: 409, error: { code: 'STALE_HEAD' } }
    const binding = {
      worktreePath: resolved.environment.worktreePath,
      branch: resolved.environment.branch,
      baseCommit: resolved.environment.baseCommit,
      expectedHead: input.expectedHead as string,
    }
    let gitResult: DeliveryGitCheckpointResult
    try {
      gitResult = await this.git.checkpoint(binding, { message: input.message as string, claims: input.claims })
    } catch (error) {
      const code = (error as { code?: string })?.code
      const state = ['GIT_COMMIT_INDETERMINATE', 'GIT_STAGE_INDETERMINATE'].includes(code as string)
        ? 'indeterminate'
        : 'failed'
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-checkpoint',
        state,
        idempotencyKey: (input.idempotencyKey as string) ?? `checkpoint:${input.expectedHead}`,
        facts: { code: code ?? 'GIT_FAILED' },
      })
      return { ok: false, status: 409, error: { code: code ?? 'GIT_FAILED' } }
    }
    // Persist the new headCommit and git.checkpoint into the delivery snapshot.
    const newHead = gitResult.commit
    const snapshotPatch = {
      headCommit: newHead,
      updatedAt: new Date().toISOString(),
      'git.checkpoint': {
        commit: newHead,
        previousHead: (gitResult as { previousHead?: string }).previousHead ?? input.expectedHead,
        changed: gitResult.changed,
        diffHash: gitResult.inspection.diffHash,
        timestamp: new Date().toISOString(),
      },
    }
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: 'git-checkpoint',
      idempotencyKey: (input.idempotencyKey as string) ?? `checkpoint:${input.expectedHead}`,
      facts: { commit: newHead, changed: gitResult.changed, diffHash: gitResult.inspection.diffHash },
    })
    return { ok: true, status: gitResult.changed ? 201 : 200, data: gitResult }
  }

  async push(identity: DeliveryTrustIdentity, workflowId: string, body: unknown): Promise<DeliveryControllerResult> {
    if (
      rejectUntrusted(body) ||
      Object.keys(body as Record<string, unknown>).some((key) => !['expectedHead', 'idempotencyKey'].includes(key))
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const input = body as Record<string, unknown>
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const expectedHead = (input.expectedHead as string) ?? resolved.snapshot.headCommit
    let result: DeliveryGitPushResult
    try {
      result = await this.git.push({
        worktreePath: resolved.environment.worktreePath,
        branch: resolved.environment.branch,
        baseCommit: resolved.environment.baseCommit,
        expectedHead,
      })
    } catch (error) {
      // Journal the exception — indeterminate if the push state is uncertain.
      const code = (error as { code?: string })?.code
      const state = code === 'GIT_PUSH_INDETERMINATE' ? 'indeterminate' : 'failed'
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-push',
        state,
        idempotencyKey: (input.idempotencyKey as string) ?? `push:${expectedHead}`,
        facts: { code: code ?? 'GIT_PUSH_FAILED' },
      })
      return { ok: false, status: 409, error: { code: code ?? 'GIT_PUSH_FAILED' } }
    }
    if (!result.ok) {
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-push',
        state: 'failed',
        idempotencyKey: (input.idempotencyKey as string) ?? `push:${expectedHead}`,
        facts: { code: result.error.code },
      })
      return { ok: false, status: 422, error: result.error }
    }
    // Persist push result into snapshot.
    const snapshotPatch = {
      updatedAt: new Date().toISOString(),
      'git.push': {
        headCommit: result.headCommit,
        changed: result.changed,
        remote: this.git.remote ?? null,
        branch: resolved.environment.branch,
        timestamp: new Date().toISOString(),
      },
    }
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: 'git-push',
      idempotencyKey: (input.idempotencyKey as string) ?? `push:${expectedHead}`,
      facts: { headCommit: result.headCommit, changed: result.changed },
    })
    return { ok: true, status: 200, data: result }
  }

  async pullRequest(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    body: unknown
  ): Promise<DeliveryControllerResult> {
    if (
      rejectUntrusted(body) ||
      Object.keys(body as Record<string, unknown>).some((key) => !['title', 'body', 'idempotencyKey'].includes(key))
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const input = body as Record<string, unknown>
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const configured = this.configuration.pullRequest
    if (!configured) return { ok: false, status: 422, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    const context = {
      owner: configured.owner,
      repo: configured.repo,
      baseBranch: configured.baseBranch,
      headBranch: resolved.environment.branch,
      title: input.title,
      body: input.body,
      idempotencyKey: input.idempotencyKey,
    }
    // findExisting before creating to prevent duplication after crash/retry.
    const result = await this.pullRequests.createDraft(context)
    const state = result.ok ? 'succeeded' : 'failed'
    await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
      kind: 'pull-request',
      state,
      idempotencyKey: (input.idempotencyKey as string) ?? `pr:${resolved.environment.branch}`,
      facts: result.ok
        ? {
            id: (result.pullRequest as { id: string }).id,
            url: (result.pullRequest as { url: string }).url,
            reused: result.reused ?? false,
          }
        : { code: result.error.code },
    })
    if (!result.ok) return { ok: false, status: 422, error: result.error }
    const pullRequest = result.pullRequest as { id: string; url: string; draft: boolean; state: string }
    // Persist PR result into snapshot.
    const snapshotPatch = {
      updatedAt: new Date().toISOString(),
      'git.pullRequest': {
        id: pullRequest.id,
        url: pullRequest.url,
        draft: pullRequest.draft,
        state: pullRequest.state,
        reused: result.reused ?? false,
        timestamp: new Date().toISOString(),
      },
    }
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: 'pull-request-persisted',
      idempotencyKey: `pr-persisted:${pullRequest.id}`,
      facts: { id: pullRequest.id },
    })
    return { ok: true, status: result.reused ? 200 : 201, data: pullRequest }
  }

  async promote(identity: DeliveryTrustIdentity, workflowId: string, body: unknown): Promise<DeliveryControllerResult> {
    if (
      rejectUntrusted(body) ||
      Object.keys(body as Record<string, unknown>).some(
        (key) => !['deliveryId', 'expectedRevision', 'requestedStage', 'evidenceIds', 'idempotencyKey'].includes(key)
      )
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const validation = validateDeliveryPromotionRequest(body, resolved.snapshot.deliveryId)
    if (!validation.ok) return { ok: false, status: 400, error: validation.error }
    const evidence = (await this.evidenceStore.list(
      identity.namespaceId,
      resolved.snapshot.deliveryId
    )) as unknown as DeliveryEvidenceItem[]
    // actorId comes from RESOLVED_FACTORY_USER (server-trusted), not from browser headers.
    const actorId = identity.resolvedActorId ?? 'factory-operator'
    const execution: DeliveryExecutionContext = {
      kind: validation.value.requestedStage === 'release-approved' ? 'factory-human' : 'factory-control-plane',
      namespaceId: identity.namespaceId,
      workflowId,
      caseId: identity.caseId,
      runtimeId: 'factory-dashboard',
      actorId,
    }
    const result = await this.store.promote({
      namespaceId: identity.namespaceId,
      request: validation.value,
      definition: this.definition,
      evidence,
      execution,
    })
    return result.ok
      ? { ok: true, status: result.changed ? 201 : 200, data: result.snapshot }
      : { ok: false, status: 409, error: result.error }
  }

  /**
   * Record a Factory-only delivery evidence entry.
   * sourceKind must be one of the trusted internal producers.
   * Agents are not permitted to write delivery evidence.
   */
  async recordEvidence(
    identity: DeliveryTrustIdentity,
    workflowId: string,
    input: unknown,
    sourceKind: string
  ): Promise<DeliveryControllerResult> {
    const ALLOWED_SOURCE_KINDS = new Set(['factory-build', 'factory-human'])
    const forbiddenAuthority = new Set(['deployment-result', 'smoke-result', 'rollback-result'])
    if (
      !ALLOWED_SOURCE_KINDS.has(sourceKind) ||
      forbiddenAuthority.has((input as Record<string, unknown>)?.kind as string)
    )
      return { ok: false, status: 403, error: { code: 'DELIVERY_EVIDENCE_AUTHORITY_FORBIDDEN' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const result = await this.evidenceStore.record(
      identity.namespaceId,
      {
        ...(input as Record<string, unknown>),
        deliveryId: resolved.snapshot.deliveryId,
        workflowId,
        environmentHash: resolved.snapshot.environmentHash,
        caseId: identity.caseId,
        runtimeId: 'factory-dashboard',
        headCommit: resolved.snapshot.headCommit,
      },
      { kind: sourceKind, actorId: identity.resolvedActorId ?? 'factory-operator' }
    )
    return result.ok
      ? { ok: true, status: result.created ? 201 : 200, data: result.evidence }
      : { ok: false, status: 409, error: result.error }
  }
}

/** Dispatches a delivery HTTP request to the controller operation matching the path. */
export async function handleDeliveryRequest({
  method,
  path,
  readBody,
  send,
  identity,
  controller,
  log = console,
}: HandleDeliveryRequestInput): Promise<boolean> {
  const match = path.match(
    /^\/api\/factory\/workflows\/([^/]+)\/delivery(?:\/(checkpoint|push|pull-request|promote|evidence))?$/
  )
  if (!match) return false
  try {
    const trust = await identity()
    if (!trust) {
      send(401, { error: { code: 'TRUST_CONTEXT_UNAVAILABLE' } })
      return true
    }
    const workflowId = decodeURIComponent(match[1] as string),
      action = match[2]
    let result: DeliveryControllerResult
    if (!action && method === 'GET') result = await controller.status(trust, workflowId)
    else if (action === 'checkpoint' && method === 'POST')
      result = await controller.checkpoint(trust, workflowId, await readBody())
    else if (action === 'push' && method === 'POST') result = await controller.push(trust, workflowId, await readBody())
    else if (action === 'pull-request' && method === 'POST')
      result = await controller.pullRequest(trust, workflowId, await readBody())
    else if (action === 'promote' && method === 'POST')
      result = await controller.promote(trust, workflowId, await readBody())
    else if (action === 'evidence' && method === 'POST')
      result = await controller.recordEvidence(trust, workflowId, await readBody(), 'factory-build')
    else result = { ok: false, status: 405, error: { code: 'METHOD_NOT_ALLOWED' } }
    send(result.status ?? (result.ok ? 200 : 409), result.ok ? { data: result.data } : { error: result.error })
    return true
  } catch (error) {
    log.error('Delivery control-plane failure', { code: (error as { code?: string })?.code ?? 'UNEXPECTED' })
    send(500, { error: { code: 'DELIVERY_CONTROL_PLANE_FAILURE' } })
    return true
  }
}
