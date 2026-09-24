function sendError(send, status, code, message) { return send(status, { error: { code, message } }) }

export async function handleWorkflowDefinitionRequest({ method, path, send, registry, log = console }) {
  const collection = path === '/api/factory/workflow-definitions'
  const detail = path.match(/^\/api\/factory\/workflow-definitions\/([^/]+)\/([^/]+)$/)
  if (!collection && !detail) return false
  if (method !== 'GET') { sendError(send, 405, 'METHOD_NOT_ALLOWED', 'Workflow definitions are read-only.'); return true }
  try {
    if (collection) { send(200, { data: { items: await registry.list() } }); return true }
    const workflowType = decodeURIComponent(detail[1]), version = decodeURIComponent(detail[2])
    const definition = await registry.get(workflowType, version)
    if (!definition) sendError(send, 404, 'WORKFLOW_DEFINITION_NOT_FOUND', 'Workflow definition was not found.')
    else send(200, { data: definition })
  } catch (error) {
    log.error?.('Workflow definition registry failure', { code: error.code ?? 'WORKFLOW_DEFINITION_REGISTRY_FAILURE' })
    sendError(send, 500, 'WORKFLOW_DEFINITION_REGISTRY_FAILURE', 'Workflow definition registry is unavailable.')
  }
  return true
}
