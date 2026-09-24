import { hashDeliveryDefinition, validateDeliveryDefinition } from './delivery-definition.mjs'
import { validateDeliveryPromotionRequest } from './delivery-policy.mjs'

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
const rejectUntrusted = (body) =>
  !body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some((key) => FORBIDDEN.has(key))

export class DeliveryController {
  constructor({
    store,
    evidenceStore,
    environmentController,
    workflowStore,
    git,
    pullRequests,
    definition,
    trustedConfiguration,
  }) {
    const validated = validateDeliveryDefinition(definition)
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
    })
    this.configuration = trustedConfiguration
  }
  async initialize() {
    await this.store.initialize()
  }

  async resolve(identity, workflowId) {
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
    const environment = environmentResult.data.environment,
      reconciliation = environmentResult.data.reconciliation
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
      if (!linked.ok) return { ok: false, status: 409, error: linked.error }
      return { ok: true, snapshot: created.snapshot, environment, reconciliation }
    }
    let deliveryRef = workflow.instance.deliveryRef
    if (!deliveryRef) {
      const linked = await this.workflowStore.bindDelivery(identity.namespaceId, workflowId, {
        deliveryId,
        definitionHash: existing.definitionHash,
      })
      if (!linked.ok) return { ok: false, status: 409, error: linked.error }
      workflow = linked.snapshot
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

  async status(identity, workflowId) {
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const projection = await this.store.inspectDeliveryOperations(identity.namespaceId, resolved.snapshot.deliveryId)
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

  async checkpoint(identity, workflowId, body) {
    if (
      rejectUntrusted(body) ||
      Object.keys(body).some((key) => !['expectedHead', 'message', 'claims', 'idempotencyKey'].includes(key))
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    if (body.expectedHead !== resolved.reconciliation.headCommit || resolved.snapshot.headCommit !== body.expectedHead)
      return { ok: false, status: 409, error: { code: 'STALE_HEAD' } }
    const binding = {
      worktreePath: resolved.environment.worktreePath,
      branch: resolved.environment.branch,
      baseCommit: resolved.environment.baseCommit,
      expectedHead: body.expectedHead,
    }
    let gitResult
    try {
      gitResult = await this.git.checkpoint(binding, { message: body.message, claims: body.claims })
    } catch (error) {
      const state = ['GIT_COMMIT_INDETERMINATE', 'GIT_STAGE_INDETERMINATE'].includes(error.code)
        ? 'indeterminate'
        : 'failed'
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-checkpoint',
        state,
        idempotencyKey: body.idempotencyKey ?? `checkpoint:${body.expectedHead}`,
        facts: { code: error.code ?? 'GIT_FAILED' },
      })
      return { ok: false, status: 409, error: { code: error.code ?? 'GIT_FAILED' } }
    }
    // Persist the new headCommit and git.checkpoint into the delivery snapshot.
    const newHead = gitResult.commit
    const snapshotPatch = {
      headCommit: newHead,
      updatedAt: new Date().toISOString(),
      'git.checkpoint': {
        commit: newHead,
        previousHead: gitResult.previousHead ?? body.expectedHead,
        changed: gitResult.changed,
        diffHash: gitResult.inspection.diffHash,
        timestamp: new Date().toISOString(),
      },
    }
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: 'git-checkpoint',
      idempotencyKey: body.idempotencyKey ?? `checkpoint:${body.expectedHead}`,
      facts: { commit: newHead, changed: gitResult.changed, diffHash: gitResult.inspection.diffHash },
    })
    return { ok: true, status: gitResult.changed ? 201 : 200, data: gitResult }
  }

  async push(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some((key) => !['expectedHead', 'idempotencyKey'].includes(key)))
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const expectedHead = body.expectedHead ?? resolved.snapshot.headCommit
    let result
    try {
      result = await this.git.push({
        worktreePath: resolved.environment.worktreePath,
        branch: resolved.environment.branch,
        baseCommit: resolved.environment.baseCommit,
        expectedHead,
      })
    } catch (error) {
      // Journal the exception — indeterminate if the push state is uncertain.
      const state = error.code === 'GIT_PUSH_INDETERMINATE' ? 'indeterminate' : 'failed'
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-push',
        state,
        idempotencyKey: body.idempotencyKey ?? `push:${expectedHead}`,
        facts: { code: error.code ?? 'GIT_PUSH_FAILED' },
      })
      return { ok: false, status: 409, error: { code: error.code ?? 'GIT_PUSH_FAILED' } }
    }
    if (!result.ok) {
      await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
        kind: 'git-push',
        state: 'failed',
        idempotencyKey: body.idempotencyKey ?? `push:${expectedHead}`,
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
      idempotencyKey: body.idempotencyKey ?? `push:${expectedHead}`,
      facts: { headCommit: result.headCommit, changed: result.changed },
    })
    return { ok: true, status: 200, data: result }
  }

  async pullRequest(identity, workflowId, body) {
    if (rejectUntrusted(body) || Object.keys(body).some((key) => !['title', 'body', 'idempotencyKey'].includes(key)))
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const configured = this.configuration.pullRequest
    if (!configured) return { ok: false, status: 422, error: { code: 'PULL_REQUEST_NOT_CONFIGURED' } }
    const context = {
      owner: configured.owner,
      repo: configured.repo,
      baseBranch: configured.baseBranch,
      headBranch: resolved.environment.branch,
      title: body.title,
      body: body.body,
      idempotencyKey: body.idempotencyKey,
    }
    // findExisting before creating to prevent duplication after crash/retry.
    const result = await this.pullRequests.createDraft(context)
    const state = result.ok ? 'succeeded' : 'failed'
    await this.store.recordOperation(identity.namespaceId, resolved.snapshot.deliveryId, {
      kind: 'pull-request',
      state,
      idempotencyKey: body.idempotencyKey ?? `pr:${resolved.environment.branch}`,
      facts: result.ok
        ? { id: result.pullRequest.id, url: result.pullRequest.url, reused: result.reused ?? false }
        : { code: result.error.code },
    })
    if (!result.ok) return { ok: false, status: 422, error: result.error }
    // Persist PR result into snapshot.
    const snapshotPatch = {
      updatedAt: new Date().toISOString(),
      'git.pullRequest': {
        id: result.pullRequest.id,
        url: result.pullRequest.url,
        draft: result.pullRequest.draft,
        state: result.pullRequest.state,
        reused: result.reused ?? false,
        timestamp: new Date().toISOString(),
      },
    }
    await this.store.updateSnapshot(identity.namespaceId, resolved.snapshot.deliveryId, snapshotPatch, {
      kind: 'pull-request-persisted',
      idempotencyKey: `pr-persisted:${result.pullRequest.id}`,
      facts: { id: result.pullRequest.id },
    })
    return { ok: true, status: result.reused ? 200 : 201, data: result.pullRequest }
  }

  async promote(identity, workflowId, body) {
    if (
      rejectUntrusted(body) ||
      Object.keys(body).some(
        (key) => !['deliveryId', 'expectedRevision', 'requestedStage', 'evidenceIds', 'idempotencyKey'].includes(key)
      )
    )
      return { ok: false, status: 400, error: { code: 'UNTRUSTED_DELIVERY_INPUT' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const validation = validateDeliveryPromotionRequest(body, resolved.snapshot.deliveryId)
    if (!validation.ok) return { ok: false, status: 400, error: validation.error }
    const evidence = await this.evidenceStore.list(identity.namespaceId, resolved.snapshot.deliveryId)
    // actorId comes from RESOLVED_FACTORY_USER (server-trusted), not from browser headers.
    const actorId = identity.resolvedActorId ?? 'factory-operator'
    const execution = {
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
  async recordEvidence(identity, workflowId, input, sourceKind) {
    const ALLOWED_SOURCE_KINDS = new Set(['factory-build', 'factory-human'])
    const forbiddenAuthority = new Set(['deployment-result', 'smoke-result', 'rollback-result'])
    if (!ALLOWED_SOURCE_KINDS.has(sourceKind) || forbiddenAuthority.has(input?.kind))
      return { ok: false, status: 403, error: { code: 'DELIVERY_EVIDENCE_AUTHORITY_FORBIDDEN' } }
    const resolved = await this.resolve(identity, workflowId)
    if (!resolved.ok) return resolved
    const result = await this.evidenceStore.record(
      identity.namespaceId,
      {
        ...input,
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

export async function handleDeliveryRequest({ method, path, readBody, send, identity, controller, log = console }) {
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
    const workflowId = decodeURIComponent(match[1]),
      action = match[2]
    let result
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
    log.error('Delivery control-plane failure', { code: error?.code ?? 'UNEXPECTED' })
    send(500, { error: { code: 'DELIVERY_CONTROL_PLANE_FAILURE' } })
    return true
  }
}
