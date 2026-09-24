import { WorkflowProjectionStoreError, WORKFLOW_STORE_ERROR_CODES } from '../lib/workflow-projection-store.mjs'
import { validateWorkflowNamespaceId } from './workflow-projection-routes.mjs'
import { NAMESPACE_METRICS_SCOPES, parseNamespaceMetricsLimit } from '../lib/factory-operational-metrics-namespace-projector.mjs'

const SCOPES = new Set(['self', 'descendants'])
const NAMESPACE_SCOPES = new Set(NAMESPACE_METRICS_SCOPES)

function errorResponse(send, status, code, message) {
  return send(status, { error: { code, message } })
}

function parseObservedAt(value, clock) {
  const candidate = value ?? clock.now()
  if (!(candidate instanceof Date) && typeof candidate !== 'string') return null
  if (typeof candidate === 'string' && !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(candidate)) return null
  const date = candidate instanceof Date ? candidate : new Date(candidate)
  return Number.isFinite(date.getTime()) ? date.toISOString() : null
}

/**
 * Handle GET /api/factory/namespaces/:namespaceId/metrics
 * Phase 10D — namespace/group/root rollup.
 */
async function handleNamespaceOperationalMetricsRequest({ method, path, url, send, service, clock, log }) {
  const match = path.match(/^\/api\/factory\/namespaces\/([^/]+)\/metrics$/)
  if (!match) return false
  if (method !== 'GET') {
    errorResponse(send, 404, 'ROUTE_NOT_FOUND', 'Namespace metrics route was not found.')
    return true
  }

  const namespaceId = decodeURIComponent(match[1])
  if (!validateWorkflowNamespaceId(namespaceId)) {
    errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId path segment is required.')
    return true
  }

  const scope = url.searchParams.get('scope') ?? 'namespace'
  if (!NAMESPACE_SCOPES.has(scope)) {
    errorResponse(send, 400, 'INVALID_NAMESPACE_METRICS_SCOPE', `scope must be one of: ${NAMESPACE_METRICS_SCOPES.join(', ')}.`)
    return true
  }

  const groupId = url.searchParams.get('groupId') ?? undefined
  const rootWorkflowId = url.searchParams.get('rootWorkflowId') ?? undefined

  // Validate mutual exclusivity: group and root selectors must not both be present.
  if (groupId !== undefined && rootWorkflowId !== undefined) {
    errorResponse(send, 400, 'AMBIGUOUS_SELECTOR', 'groupId and rootWorkflowId are mutually exclusive; provide at most one.')
    return true
  }
  if (scope === 'namespace' && (groupId !== undefined || rootWorkflowId !== undefined)) {
    errorResponse(send, 400, 'UNEXPECTED_SELECTOR', 'scope=namespace does not accept groupId or rootWorkflowId.')
    return true
  }
  if (scope === 'group' && rootWorkflowId !== undefined) {
    errorResponse(send, 400, 'UNEXPECTED_SELECTOR', 'scope=group does not accept rootWorkflowId.')
    return true
  }
  if (scope === 'root' && groupId !== undefined) {
    errorResponse(send, 400, 'UNEXPECTED_SELECTOR', 'scope=root does not accept groupId.')
    return true
  }
  if (scope === 'group' && groupId === undefined) {
    errorResponse(send, 400, 'MISSING_GROUP_ID', 'groupId is required when scope=group.')
    return true
  }
  if (scope === 'root' && rootWorkflowId === undefined) {
    errorResponse(send, 400, 'MISSING_ROOT_WORKFLOW_ID', 'rootWorkflowId is required when scope=root.')
    return true
  }

  const limitResult = parseNamespaceMetricsLimit(url.searchParams.get('limit'))
  if (!limitResult.ok) {
    errorResponse(send, 400, limitResult.code, limitResult.message)
    return true
  }

  const observedAt = parseObservedAt(url.searchParams.get('observedAt'), clock)
  if (!observedAt) {
    errorResponse(send, 400, 'INVALID_OBSERVED_AT', 'observedAt must be a valid ISO instant.')
    return true
  }

  try {
    const result = await service.projectNamespace({
      namespaceId,
      scope,
      groupId,
      rootWorkflowId,
      limit: limitResult.limit,
      observedAt,
    })

    if (!result.ok) {
      const status = result.code === 'ROOT_WORKFLOW_NOT_FOUND' ? 404 :
        ['INVALID_GROUP_ID', 'INVALID_ROOT_WORKFLOW_ID', 'MISSING_GROUP_ID', 'MISSING_ROOT_WORKFLOW_ID', 'AMBIGUOUS_SELECTOR', 'UNEXPECTED_SELECTOR'].includes(result.code) ? 400 : 422
      errorResponse(send, status, result.code, result.message)
      return true
    }

    send(200, { data: result.data })
  } catch (error) {
    if (error instanceof TypeError) {
      errorResponse(send, 422, 'METRICS_DATA_INCOMPLETE', 'Namespace operational metrics cannot be projected from incomplete or corrupt Factory data.')
    } else {
      log.error('Namespace operational metrics storage failure', { operation: 'namespace-metrics', namespaceId, code: error?.code ?? 'UNEXPECTED_STORAGE_FAILURE' })
      errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Namespace operational metrics storage is unavailable.')
    }
  }
  return true
}

/** Read-only HTTP adapter for the Phase 10B operational metrics service. */
export async function handleWorkflowOperationalMetricsRequest({ method, path, url, send, service, clock = { now: () => new Date() }, log = console }) {
  // Namespace rollup route takes priority over the per-workflow route.
  if (path.startsWith('/api/factory/namespaces/')) {
    return handleNamespaceOperationalMetricsRequest({ method, path, url, send, service, clock, log })
  }

  const match = path.match(/^\/api\/factory\/workflows\/([^/]+)\/metrics$/)
  if (!match) return false
  if (method !== 'GET') {
    errorResponse(send, 404, 'ROUTE_NOT_FOUND', 'Workflow metrics route was not found.')
    return true
  }

  const namespaceId = url.searchParams.get('namespaceId')
  if (!validateWorkflowNamespaceId(namespaceId)) {
    errorResponse(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.')
    return true
  }
  const workflowId = decodeURIComponent(match[1])
  const scope = url.searchParams.get('scope') ?? 'self'
  if (!SCOPES.has(scope)) {
    errorResponse(send, 400, 'INVALID_METRICS_SCOPE', 'scope must be self or descendants.')
    return true
  }
  const observedAt = parseObservedAt(url.searchParams.get('observedAt'), clock)
  if (!observedAt) {
    errorResponse(send, 400, 'INVALID_OBSERVED_AT', 'observedAt must be a valid ISO instant.')
    return true
  }

  try {
    const root = await service.workflowStore.read(namespaceId, workflowId)
    if (!root) {
      errorResponse(send, 404, 'WORKFLOW_NOT_FOUND', 'Workflow projection was not found.')
      return true
    }
    const metrics = await service.project({ namespaceId, workflowId, scope, observedAt })
    send(200, { data: metrics })
  } catch (error) {
    const invalidId = error instanceof WorkflowProjectionStoreError &&
      error.code === WORKFLOW_STORE_ERROR_CODES.CORRUPT_STORAGE && error.details?.path === 'workflowId'
    if (invalidId) errorResponse(send, 400, 'INVALID_WORKFLOW_ID', 'workflowId is invalid.')
    else if (error instanceof TypeError) errorResponse(send, 422, 'METRICS_DATA_INCOMPLETE', 'Operational metrics cannot be projected from incomplete or corrupt Factory data.')
    else {
      log.error('Workflow operational metrics storage failure', { operation: 'metrics', namespaceId, workflowId, code: error?.code ?? 'UNEXPECTED_STORAGE_FAILURE' })
      errorResponse(send, 500, 'WORKFLOW_STORAGE_FAILURE', 'Workflow operational metrics storage is unavailable.')
    }
    return true
  }
  return true
}
