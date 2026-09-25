/**
 * Application service for the Forge workflow projection sync.
 *
 * The authoritative BMAD read lives in the BMAD file-reader adapter, the pure
 * transformation in the Forge workflow-adapter domain, and the projection store
 * is injected by the caller.
 */

import { readForgeRunYamlStrict } from '../../adapters/forge/forge-bmad-file-reader.js'
import { adaptForgeRunToWorkflowProjection } from '../../domain/forge-bmad/forge-workflow-adapter.js'

const ATTRIBUTION_FIELDS = new Set(['actorId', 'agentId', 'caseId', 'runId'])
const SAFE_ATTRIBUTION = /^[A-Za-z0-9][A-Za-z0-9._:@-]{0,255}$/

/** Safe trusted Forge ticket identifier. */
export const SAFE_FORGE_TICKET_ID = /^[A-Z][A-Z0-9]+-\d+$/

/** Validate a workflow-sync attribution payload. */
export function sanitizeForgeSyncAttribution(
  body: any = {}
): { ok: true; attribution: Record<string, string> } | { ok: false; error: { code: string } } {
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

/** Read the authoritative BMAD run and publish its workflow projection. */
export async function syncForgeWorkflowProjection({
  repoRoot,
  namespaceId,
  ticketId,
  attribution = {},
  store,
}: {
  repoRoot: string
  namespaceId: string
  ticketId: string
  attribution?: Record<string, string>
  store: { publish: (namespaceId: string, projection: any, attribution: any) => Promise<any> }
}): Promise<Record<string, any>> {
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
