import { validateWorkflowNamespaceId } from './workflow-projection-routes.mjs'
import { SAFE_FORGE_TICKET_ID, sanitizeForgeSyncAttribution, syncForgeWorkflowProjection } from '../lib/forge-workflow-sync.mjs'
import { sendError as error } from './http-utils.mjs'

/** Focused HTTP adapter; repoRoot is supplied only by the trusted namespace resolver. */
export async function handleForgeWorkflowProjectionRequest({ method, path, url, readBody, send, resolveRepoRoot, store, notifier, sync = syncForgeWorkflowProjection, log = console }) {
  const match = path.match(/^\/api\/factory\/forge\/projections\/([^/]+)\/sync$/)
  if (!match) return false
  if (method !== 'POST') { error(send, 405, 'METHOD_NOT_ALLOWED', 'Only POST is supported.'); return true }
  const namespaceId = url.searchParams.get('namespaceId')
  if (!validateWorkflowNamespaceId(namespaceId)) { error(send, 400, 'INVALID_NAMESPACE_ID', 'A valid namespaceId query parameter is required.'); return true }
  let ticketId
  try { ticketId = decodeURIComponent(match[1]) } catch { error(send, 400, 'INVALID_TICKET_ID', 'ticketId is invalid.'); return true }
  if (!SAFE_FORGE_TICKET_ID.test(ticketId)) { error(send, 400, 'INVALID_TICKET_ID', 'ticketId is invalid.'); return true }
  const attribution = sanitizeForgeSyncAttribution(await readBody())
  if (!attribution.ok) { error(send, 400, attribution.error.code, 'Machine attribution is invalid.'); return true }
  try {
    const repoRoot = await resolveRepoRoot(namespaceId)
    if (!repoRoot) { error(send, 422, 'NAMESPACE_REPO_UNAVAILABLE', 'Namespace is not found or has no repository root.'); return true }
    const result = await sync({ repoRoot, namespaceId, ticketId, attribution: attribution.attribution, store })
    if (!result.ok) {
      const status = result.error.code === 'FORGE_RUN_NOT_FOUND' ? 404 : result.error.code === 'WORKFLOW_REMOVED' ? 409 : 422
      error(send, status, result.error.code, status === 404 ? 'Forge run was not found.' : 'Forge run could not be synchronized.')
      return true
    }
    if (result.changed) notifier?.publish(namespaceId, { workflowId: result.workflowId, namespaceId, revision: result.revision })
    send(result.changed && result.revision === 1 ? 201 : 200, { data: { namespaceId, ticketId, ...result } })
  } catch (cause) {
    log.error('Forge workflow synchronization failure', { namespaceId, ticketId, code: cause?.code ?? 'UNEXPECTED_SYNC_FAILURE' })
    error(send, 500, 'FORGE_SYNC_STORAGE_FAILURE', 'Forge workflow synchronization is unavailable.')
  }
  return true
}
