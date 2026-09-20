import {
  WORKFLOW_STORE_ERROR_CODES,
  WorkflowProjectionStoreError,
} from '../lib/workflow-projection-store.mjs'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const EXECUTION_FIELDS = new Set(['namespaceId', 'runtimeId', 'kind', 'actorId', 'agentId', 'caseId', 'threadId'])
const ATTRIBUTION_FIELDS = ['actorId', 'agentId', 'caseId', 'threadId']
const SAFE_ATTRIBUTION = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/
const LIFECYCLE_FIELDS = new Set(['actorId'])
const LIFECYCLE_EVENTS = Object.freeze({ remove: 'workflow-projection-removed', restore: 'workflow-projection-restored', purge: 'workflow-projection-purged' })

function errorResponse(send, status, code, message) {
  return send(status, { error: { code, message } })
}

export function validateWorkflowNamespaceId(namespaceId) {
  return typeof namespaceId === 'string' && UUID.test(namespaceId)
}

/**
 * Strictly validate the first HTTP trust boundary for execution attribution.
 * Unknown fields and prose-like attribution are rejected, never silently persisted.
 */
export function sanitizeWorkflowExecution(execution) {
  if (!execution || typeof execution !== 'object' || Array.isArray(execution)) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'not_object' }
  }
  if (Object.keys(execution).some((field) => !EXECUTION_FIELDS.has(field))) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'unknown_field' }
  }
  if (!validateWorkflowNamespaceId(execution.namespaceId)) {
    return { ok: false, code: 'INVALID_NAMESPACE_ID', reason: 'namespace_id' }
  }
  if (typeof execution.runtimeId !== 'string' || !SAFE_ATTRIBUTION.test(execution.runtimeId)) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'runtime_id' }
  }
  if (!['agentos', 'coday-express'].includes(execution.kind)) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'kind' }
  }
  if (typeof execution.agentId !== 'string' || !SAFE_ATTRIBUTION.test(execution.agentId)) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'agent_id' }
  }
  const hasCase = execution.caseId !== undefined
  const hasThread = execution.threadId !== undefined
  if ((execution.kind === 'agentos' && (!hasCase || hasThread)) ||
      (execution.kind === 'coday-express' && (!hasThread || hasCase))) {
    return { ok: false, code: 'INVALID_EXECUTION', reason: 'controller_identity' }
  }
  const attribution = {}
  for (const field of ATTRIBUTION_FIELDS) {
    const value = execution[field]
    if (value === undefined) continue
    if (typeof value !== 'string' || !SAFE_ATTRIBUTION.test(value)) return { ok: false, code: 'INVALID_EXECUTION', reason: `${field}_format` }
    attribution[field] = value
  }
  return { ok: true, namespaceId: execution.namespaceId, controllerExecution: {
    runtimeId: execution.runtimeId, kind: execution.kind, agentId: execution.agentId,
    ...attribution,
  } }
}

function sanitizeLifecycleActor(body) {
  if (body === undefined || body === null) return { ok: true, attribution: {} }
  if (typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some((field) => !LIFECYCLE_FIELDS.has(field))) return { ok: false }
  if (body.actorId !== undefined && (typeof body.actorId !== 'string' || !SAFE_ATTRIBUTION.test(body.actorId))) return { ok: false }
  return { ok: true, attribution: body.actorId === undefined ? {} : { actorId: body.actorId } }
}

function lifecycleStatus(result) {
  if (result.error?.code === WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND) return 404
  if (result.error?.code === WORKFLOW_STORE_ERROR_CODES.INVALID_LIFECYCLE_TRANSITION || result.error?.code === WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED) return 409
  return 400
}

async function publicSnapshot(snapshot) {
  return {
    workflowId: snapshot.projection.workflowId,
    revision: snapshot.revision,
    projectionHash: snapshot.projectionHash,
    ...(snapshot.governanceMode ? { governanceMode: snapshot.governanceMode, definitionVersion: snapshot.definitionVersion, definitionHash: snapshot.definitionHash, relations: snapshot.instance?.relations ?? { rootWorkflowId: snapshot.projection.workflowId }, instance: snapshot.instance } : { relations: { rootWorkflowId: snapshot.projection.workflowId } }),
    ...(snapshot.controllerExecution ? { controllerExecution: snapshot.controllerExecution } : {}),
    projection: snapshot.projection,
  }
}

function logStorageFailure(log, context, error) {
  log.error('Workflow projection storage failure', {
    ...context,
    code: error?.code ?? 'UNEXPECTED_STORAGE_FAILURE',
  })
}

/**
 * Handle generic workflow projection API requests.
 * Returns true when the path belongs to this API, otherwise false.
 */
export async function handleWorkflowProjectionRequest({ method, path, url, readBody, send, store, definitionRegistry, notifier, openStream, log = console }) {
  const collection = path === '/api/factory/workflows'
  const stream = path === '/api/factory/workflows/stream'
  const projectionMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)\/projection$/)
  const startMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)\/start$/)
  const timingMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)\/timing$/)
  const restoreMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)\/restore$/)
  const purgeMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)\/purge$/)
  const detailMatch = path.match(/^\/api\/factory\/workflows\/([^/]+)$/)
  if (!collection && !stream && !projectionMatch && !startMatch && !timingMatch && !restoreMatch && !purgeMatch && !detailMatch) return false

  if (method === 'GET' && stream) {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!validateWorkflowNamespaceId(namespaceId)) {
      errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.')
      return true
    }
    openStream(namespaceId)
    return true
  }

  if (method === 'POST' && startMatch) {
    const workflowId = decodeURIComponent(startMatch[1])
    const body = await readBody()
    if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some((field) => !['workflow', 'execution'].includes(field))) { errorResponse(send, 400, 'INVALID_REQUEST', 'Request body must contain workflow and execution.'); return true }
    const execution = sanitizeWorkflowExecution(body.execution)
    if (!execution.ok) { errorResponse(send, 400, execution.code, 'Execution attribution is invalid.'); return true }
    const command = body.workflow
    if (!command || typeof command !== 'object' || Array.isArray(command) || Object.keys(command).some((field) => !['workflowId','workflowType','title','relations'].includes(field)) || command.workflowId !== workflowId || typeof command.workflowType !== 'string' || typeof command.title !== 'string' || !command.title.trim()) { errorResponse(send, 400, 'INVALID_START_REQUEST', 'workflowId, workflowType and title are required; only optional relations are accepted.'); return true }
    try {
      const definition = await definitionRegistry.resolveUnique(command.workflowType)
      const result = await store.start(execution.namespaceId, command, definition, execution.controllerExecution)
      if (!result.ok) { const status = ['WORKFLOW_REMOVED','WORKFLOW_ALREADY_EXISTS','WORKFLOW_IDENTITY_CONFLICT','WORKFLOW_RELATION_CYCLE'].includes(result.error.code) ? 409 : result.error.code === 'PARENT_WORKFLOW_NOT_FOUND' ? 404 : 400; errorResponse(send, status, result.error.code, result.error.code === WORKFLOW_STORE_ERROR_CODES.WORKFLOW_ALREADY_EXISTS ? 'Workflow already exists; use get_workflow and resume without converting it.' : result.error.code === 'PARENT_WORKFLOW_NOT_FOUND' ? 'Parent workflow was not found in this namespace.' : 'Workflow instance cannot be created.'); return true }
      if (result.created) notifier?.publish(execution.namespaceId, { workflowId, namespaceId: execution.namespaceId, revision: 1 })
      send(result.created ? 201 : 200, { data: { namespaceId: execution.namespaceId, created: result.created, idempotent: result.idempotent, ...await publicSnapshot(result.snapshot) } })
    } catch (error) {
      if (error?.code === 'WORKFLOW_DEFINITION_NOT_FOUND' || error?.code === 'WORKFLOW_DEFINITION_AMBIGUOUS') errorResponse(send, 409, error.code, error.code === 'WORKFLOW_DEFINITION_AMBIGUOUS' ? 'Workflow definition selection is ambiguous.' : 'Workflow definition was not found.')
      else { logStorageFailure(log, { operation: 'start', namespaceId: execution.namespaceId, workflowId }, error); errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow instance storage is unavailable.') }
    }
    return true
  }

  if (method === 'PUT' && projectionMatch) {
    const workflowId = decodeURIComponent(projectionMatch[1])
    const body = await readBody()
    if (!body || typeof body !== 'object' || Array.isArray(body) ||
        Object.keys(body).some((field) => !['projection', 'execution'].includes(field))) {
      errorResponse(send, 400, 'INVALID_REQUEST', 'Request body must contain projection and execution.')
      return true
    }
    const execution = sanitizeWorkflowExecution(body.execution)
    if (!execution.ok) {
      log.warn?.('Workflow execution attribution rejected', { code: execution.code, reason: execution.reason })
      errorResponse(send, 400, execution.code, 'Execution attribution is invalid.')
      return true
    }
    if (!body.projection || body.projection.workflowId !== workflowId) {
      errorResponse(send, 400, 'WORKFLOW_ID_MISMATCH', 'Path workflowId must equal projection.workflowId.')
      return true
    }
    try {
      const result = await store.publish(execution.namespaceId, body.projection, execution.controllerExecution)
      if (!result.ok) {
        if (result.error?.code === WORKFLOW_STORE_ERROR_CODES.REVISION_CONFLICT) {
          errorResponse(send, 409, 'REVISION_CONFLICT', 'The expected revision is stale.')
        } else if (result.error?.code === WORKFLOW_STORE_ERROR_CODES.WORKFLOW_REMOVED) {
          errorResponse(send, 409, 'WORKFLOW_REMOVED', 'Workflow projection has been removed.')
        } else if (result.error?.code === 'GOVERNED_WORKFLOW_REQUIRES_TRANSITION') {
          errorResponse(send, 409, result.error.code, 'Governed workflow instances can only change through transition requests.')
        } else {
          errorResponse(send, 400, result.error?.code ?? 'INVALID_PROJECTION', 'Workflow projection is invalid.')
        }
        return true
      }
      if (result.changed) {
        notifier?.publish(execution.namespaceId, { workflowId, namespaceId: execution.namespaceId, revision: result.snapshot.revision })
      }
      send(result.changed && result.snapshot.revision === 1 ? 201 : 200, {
        data: { namespaceId: execution.namespaceId, changed: result.changed, ...await publicSnapshot(result.snapshot, definitionRegistry) },
      })
    } catch (error) {
      logStorageFailure(log, { operation: 'publish', namespaceId: execution.namespaceId, workflowId }, error)
      errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow projection storage is unavailable.')
    }
    return true
  }

  if (method === 'GET' && timingMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    const workflowId = decodeURIComponent(timingMatch[1])
    if (!validateWorkflowNamespaceId(namespaceId)) { errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.'); return true }
    try {
      const timing = await store.timing(namespaceId, workflowId, new Date())
      if (!timing) errorResponse(send, 404, 'WORKFLOW_NOT_FOUND', 'Workflow projection was not found.')
      else send(200, { data: { namespaceId, workflowId, timing } })
    } catch (error) {
      const invalidId = error instanceof WorkflowProjectionStoreError && error.code === WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE && error.details?.path === 'workflowId'
      if (invalidId) errorResponse(send, 400, 'INVALID_WORKFLOW_ID', 'workflowId is invalid.')
      else { logStorageFailure(log, { operation: 'timing', namespaceId, workflowId }, error); errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow projection storage is unavailable.') }
    }
    return true
  }

  if (method === 'GET' && collection) {
    const namespaceId = url.searchParams.get('namespaceId')
    const state = url.searchParams.get('state')
    if (!validateWorkflowNamespaceId(namespaceId)) {
      errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.')
      return true
    }
    if (!['active', 'removed'].includes(state)) {
      errorResponse(send, 400, 'UNSUPPORTED_STATE', 'state must be active or removed.')
      return true
    }
    try {
      const items = await Promise.all((await (state === 'removed' ? store.listRemoved(namespaceId) : store.list(namespaceId))).map((snapshot) => publicSnapshot(snapshot, definitionRegistry)))
      send(200, { data: { namespaceId, state, items } })
    } catch (error) {
      logStorageFailure(log, { operation: 'list', namespaceId }, error)
      errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow projection storage is unavailable.')
    }
    return true
  }

  const lifecycleMatch = restoreMatch ?? purgeMatch ?? (method === 'DELETE' ? detailMatch : null)
  if (lifecycleMatch && ((restoreMatch && method === 'POST') || (purgeMatch && method === 'DELETE') || (!restoreMatch && !purgeMatch && method === 'DELETE'))) {
    const namespaceId = url.searchParams.get('namespaceId')
    const workflowId = decodeURIComponent(lifecycleMatch[1])
    if (!validateWorkflowNamespaceId(namespaceId)) { errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.'); return true }
    const actor = sanitizeLifecycleActor(await readBody())
    if (!actor.ok) { errorResponse(send, 400, 'INVALID_ACTOR_ATTRIBUTION', 'Actor attribution is invalid.'); return true }
    const operation = restoreMatch ? 'restore' : purgeMatch ? 'purge' : 'remove'
    try {
      const result = await store[operation](namespaceId, workflowId, actor.attribution)
      if (!result.ok) { errorResponse(send, lifecycleStatus(result), result.error.code, result.error.code === WORKFLOW_STORE_ERROR_CODES.WORKFLOW_NOT_FOUND ? 'Workflow lifecycle state was not found.' : 'Workflow lifecycle transition is invalid.'); return true }
      const payload = { workflowId, namespaceId }
      if (operation === 'restore') payload.revision = result.snapshot.revision
      notifier?.publish(namespaceId, payload, LIFECYCLE_EVENTS[operation])
      send(200, { data: { ...payload, state: operation === 'restore' ? 'active' : operation === 'remove' ? 'removed' : 'purged' } })
    } catch (error) { logStorageFailure(log, { operation, namespaceId, workflowId }, error); errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow projection storage is unavailable.') }
    return true
  }

  if (method === 'GET' && detailMatch) {
    const namespaceId = url.searchParams.get('namespaceId')
    const workflowId = decodeURIComponent(detailMatch[1])
    if (!validateWorkflowNamespaceId(namespaceId)) {
      errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.')
      return true
    }
    try {
      const lookup = typeof store.lookup === 'function'
        ? await store.lookup(namespaceId, workflowId)
        : { state: 'existing', workflowId, snapshot: await store.read(namespaceId, workflowId) }
      if (lookup.state === 'existing' && lookup.snapshot) {
        send(200, { data: { namespaceId, state: 'existing', ...await publicSnapshot(lookup.snapshot, definitionRegistry) } })
      } else {
        send(200, { data: { namespaceId, workflowId, state: lookup.state === 'existing' ? 'absent' : lookup.state } })
      }
    } catch (error) {
      const invalidId = error instanceof WorkflowProjectionStoreError && error.code === WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE && error.details?.path === 'workflowId'
      if (invalidId) errorResponse(send, 400, 'INVALID_WORKFLOW_ID', 'workflowId is invalid.')
      else {
        logStorageFailure(log, { operation: 'read', namespaceId, workflowId }, error)
        errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow projection storage is unavailable.')
      }
    }
    return true
  }

  errorResponse(send, 404, 'ROUTE_NOT_FOUND', 'Workflow projection route was not found.')
  return true
}
