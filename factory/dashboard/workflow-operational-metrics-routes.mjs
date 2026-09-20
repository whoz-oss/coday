import { WorkflowProjectionStoreError, WORKFLOW_STORE_ERROR_CODES } from '../lib/workflow-projection-store.mjs'
import { validateWorkflowNamespaceId } from './workflow-projection-routes.mjs'

const SCOPES = new Set(['self', 'descendants'])

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

/** Read-only HTTP adapter for the Phase 10B operational metrics service. */
export async function handleWorkflowOperationalMetricsRequest({ method, path, url, send, service, clock = { now: () => new Date() }, log = console }) {
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
