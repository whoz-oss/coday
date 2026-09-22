import { WorkUnitEnvironmentService } from './work-unit-environment-service.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/
const ALLOWED = new Set(['workflowId', 'workUnitId', 'integrationBranch', 'branch'])
const error = (send, status, code, message) => send(status, { error: { code, message } })
const publicEnvironment = (result) => ({
  revision: result.snapshot.revision,
  environment: result.snapshot.environment,
  reconciliation: result.reconciliation ?? null,
  headCommit: result.reconciliation?.headCommit ?? result.headCommit ?? null,
  fileAccess: {
    status:
      result.snapshot.environment.lifecycleState === 'active' && result.reconciliation?.status === 'owned'
        ? 'bound'
        : 'blocked',
    code:
      result.snapshot.environment.lifecycleState === 'active' && result.reconciliation?.status === 'owned'
        ? null
        : 'ENVIRONMENT_NOT_BOUND',
    rootPath: result.snapshot.environment.worktreePath,
  },
})

/** Trusted Factory control-plane. Repository and destination roots come only from its injected policy. */
export class WorkUnitEnvironmentController {
  constructor({ store, git, policy, workflowStore, clock = () => new Date(), idGenerator, fault }) {
    this.store = store
    this.git = git
    this.policy = policy
    this.workflowStore = workflowStore
    this.service = new WorkUnitEnvironmentService({ store, git, clock, idGenerator, fault })
  }
  async initialize() {
    await this.store.initialize()
  }
  async provision({ namespaceId, caseId, createdBy, body }) {
    if (!UUID.test(namespaceId ?? '') || !UUID.test(caseId ?? '') || !SAFE.test(createdBy ?? ''))
      return { ok: false, status: 400, error: { code: 'INVALID_TRUST_CONTEXT' } }
    if (
      !body ||
      typeof body !== 'object' ||
      Array.isArray(body) ||
      Object.keys(body).some((key) => !ALLOWED.has(key)) ||
      !SAFE.test(body.workflowId ?? '') ||
      !SAFE.test(body.workUnitId ?? '')
    )
      return { ok: false, status: 400, error: { code: 'INVALID_ENVIRONMENT_REQUEST' } }
    const roots = await this.policy.resolve(namespaceId, body)
    if (!roots?.repoRoot || !roots?.worktreePath)
      return { ok: false, status: 422, error: { code: 'ENVIRONMENT_POLICY_UNAVAILABLE' } }
    const workflow = await this.workflowStore?.read(namespaceId, body.workflowId)
    if (
      !workflow?.instance ||
      workflow.instance.controllerExecution?.kind !== 'agentos' ||
      workflow.instance.controllerExecution.caseId !== caseId
    )
      return { ok: false, status: 409, error: { code: 'WORKFLOW_ENVIRONMENT_CONTEXT_MISMATCH' } }
    const environmentId = `${body.workflowId}-${body.workUnitId}`
    const result = await this.service.provision({
      ...body,
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
    const linked = await this.workflowStore.bindEnvironment(namespaceId, body.workflowId, {
      environmentId,
      environmentHash: inspected.snapshot.environmentHash,
    })
    if (!linked.ok) return { ok: false, status: 409, error: linked.error }
    return { ok: true, status: result.changed ? 201 : 200, data: publicEnvironment(inspected) }
  }
  async get(namespaceId, workflowId) {
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
  async reconcile(namespaceId, workflowId) {
    return this.get(namespaceId, workflowId)
  }
  async release(namespaceId, workflowId, state) {
    if (!['completed', 'abandoned'].includes(state))
      return { ok: false, status: 400, error: { code: 'INVALID_RELEASE_STATE' } }
    const found = await this.get(namespaceId, workflowId)
    if (!found.ok) return found
    const environmentId = found.data.environment.environmentId
    const transitioned = await this.service.setState(namespaceId, environmentId, state)
    if (!transitioned.ok) return { ok: false, status: 409, error: transitioned.error }
    return {
      ok: true,
      status: 200,
      data: publicEnvironment({ snapshot: transitioned.snapshot, reconciliation: found.data.reconciliation }),
    }
  }
}

export async function handleWorkUnitEnvironmentRequest({
  method,
  path,
  url,
  readBody,
  send,
  controller,
  identity,
  log = console,
}) {
  const provision = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/provision$/)
  const reconcile = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/reconcile$/)
  const release = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment\/release$/)
  const detail = path.match(/^\/api\/factory\/workflows\/([^/]+)\/environment$/)
  if (!provision && !reconcile && !release && !detail) return false
  try {
    if (provision && method === 'POST') {
      const trust = await identity()
      if (!trust)
        return (error(send, 401, 'TRUST_CONTEXT_UNAVAILABLE', 'Trusted AgentOS execution context is required.'), true)
      const result = await controller.provision({
        namespaceId: trust.namespaceId,
        caseId: trust.caseId,
        createdBy: trust.actorId,
        body: await readBody(),
      })
      return (
        result.ok
          ? send(result.status, { data: result.data })
          : error(send, result.status, result.error.code, 'Environment provisioning was rejected.'),
        true
      )
    }
    const trust = await identity()
    if (!trust)
      return (error(send, 401, 'TRUST_CONTEXT_UNAVAILABLE', 'Trusted AgentOS execution context is required.'), true)
    const namespaceId = trust.namespaceId,
      workflowId = decodeURIComponent((reconcile ?? release ?? detail)[1])
    if (reconcile && method === 'POST') {
      const result = await controller.reconcile(namespaceId, workflowId)
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId)
        return (error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.'), true)
      return (
        result.ok
          ? send(200, { data: result.data })
          : error(send, result.status, result.error.code, 'Environment reconciliation failed closed.'),
        true
      )
    }
    if (release && method === 'POST') {
      const current = await controller.get(namespaceId, workflowId)
      if (current.ok && current.data.environment.parentCaseId !== trust.caseId)
        return (error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.'), true)
      const result = current.ok ? await controller.release(namespaceId, workflowId, (await readBody()).state) : current
      return (
        result.ok
          ? send(200, { data: result.data })
          : error(send, result.status, result.error.code, 'Environment release was rejected.'),
        true
      )
    }
    if (detail && method === 'GET') {
      const result = await controller.get(namespaceId, workflowId)
      if (result.ok && result.data.environment.parentCaseId !== trust.caseId)
        return (error(send, 409, 'ENVIRONMENT_NOT_BOUND', 'Environment is not bound to the controlling case.'), true)
      return (
        result.ok
          ? send(200, { data: result.data })
          : error(send, result.status, result.error.code, 'Environment is unavailable.'),
        true
      )
    }
    return (error(send, 405, 'METHOD_NOT_ALLOWED', 'Method not allowed.'), true)
  } catch (cause) {
    log.error('Work unit environment failure', { code: cause?.code ?? 'UNEXPECTED' })
    return (error(send, 500, 'ENVIRONMENT_STORAGE_FAILURE', 'Environment control-plane is unavailable.'), true)
  }
}
