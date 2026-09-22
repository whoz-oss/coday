import { readForgeRunYamlStrict } from './forge-bmad-reader.mjs'
import { adaptForgeRunToWorkflowProjection } from './forge-workflow-adapter.mjs'

const ATTRIBUTION_FIELDS = new Set(['actorId', 'agentId', 'caseId', 'runId'])
const SAFE_ATTRIBUTION = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/
export const SAFE_FORGE_TICKET_ID = /^[A-Z][A-Z0-9]+-\d+$/

export function sanitizeForgeSyncAttribution(body = {}) {
  if (
    !body ||
    typeof body !== 'object' ||
    Array.isArray(body) ||
    Object.keys(body).some((key) => !ATTRIBUTION_FIELDS.has(key))
  )
    return { ok: false, error: { code: 'INVALID_ATTRIBUTION' } }
  for (const value of Object.values(body))
    if (typeof value !== 'string' || !SAFE_ATTRIBUTION.test(value))
      return { ok: false, error: { code: 'INVALID_ATTRIBUTION' } }
  return { ok: true, attribution: { ...body } }
}

export async function syncForgeWorkflowProjection({ repoRoot, namespaceId, ticketId, attribution = {}, store }) {
  if (typeof repoRoot !== 'string' || !SAFE_FORGE_TICKET_ID.test(ticketId ?? ''))
    return { ok: false, error: { code: 'INVALID_SYNC_TARGET' } }
  const authoritative = readForgeRunYamlStrict(repoRoot, ticketId)
  if (!authoritative.ok) return authoritative
  const run = authoritative.run
  const adapted = adaptForgeRunToWorkflowProjection(run)
  if (!adapted.ok) return adapted
  const published = await store.publish(namespaceId, adapted.projection, attribution)
  if (!published.ok) return { ok: false, error: published.error }
  return {
    ok: true,
    changed: published.changed,
    workflowId: adapted.projection.workflowId,
    revision: published.snapshot.revision,
    projectionHash: published.snapshot.projectionHash,
  }
}
