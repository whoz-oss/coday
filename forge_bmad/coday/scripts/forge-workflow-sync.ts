#!/usr/bin/env ts-node
/**
 * Synchronizes one authoritative Forge BMAD run YAML into the generic
 * WorkflowProjection store. This best-effort integration must never fail the
 * agent workflow when the local Factory dashboard is unavailable.
 *
 * Usage: FACTORY_SERVER_URL=http://localhost:3141 NAMESPACE_ID=<uuid> \
 *   ts-node forge-workflow-sync.ts WZ-123
 */

const FACTORY_SERVER_URL = process.env.FACTORY_SERVER_URL ?? 'http://localhost:3141'
const NAMESPACE_ID = process.env.NAMESPACE_ID ?? process.env.FACTORY_NAMESPACE_ID
const ticketId = process.argv[2]

if (!NAMESPACE_ID) {
  console.error('[forge-workflow-sync] NAMESPACE_ID or FACTORY_NAMESPACE_ID is required')
  process.exit(1)
}
if (!ticketId) {
  console.error('[forge-workflow-sync] TICKET_ID is required')
  process.exit(1)
}

async function sync(): Promise<void> {
  const url = `${FACTORY_SERVER_URL}/api/factory/forge/projections/${encodeURIComponent(ticketId)}/sync?namespaceId=${encodeURIComponent(NAMESPACE_ID!)}`
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    const response = (await res.json()) as { data?: { changed?: boolean }; error?: { code?: string } | string }
    if (!res.ok) {
      const error = typeof response.error === 'string' ? response.error : response.error?.code
      console.error(`[forge-workflow-sync] Server error ${res.status}: ${error ?? '?'}`)
      return // best effort: never fail the agent workflow on publication failure
    }
    console.log(`[forge-workflow-sync] ${ticketId}: ${response.data?.changed === true ? 'published' : 'unchanged'}`)
  } catch (err) {
    console.warn(`[forge-workflow-sync] Factory server unreachable (${String(err)}) — skipping synchronization`)
  }
}

await sync()
