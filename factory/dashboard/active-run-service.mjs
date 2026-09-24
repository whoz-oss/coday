/**
 * Active-run service — business logic for the shared active-run marker.
 *
 * The marker is persisted at <repoRoot>/forge/active-run.json and is shared by
 * every user of a namespace. This module owns that file; the HTTP route only
 * maps the result to a response.
 */

import { existsSync, readFileSync, writeFileSync, mkdirSync, unlinkSync } from 'node:fs'
import { join } from 'node:path'

/** Shared namespace resolution; returns a discriminated error result. */
async function resolveRepoContext(proxy, namespaceId) {
  if (!namespaceId) return { ok: false, status: 400, code: 'MISSING_NAMESPACE_ID', message: 'namespaceId query param is required' }
  try {
    const namespace = await proxy.fetchNamespace(namespaceId)
    if (!namespace) return { ok: false, status: 404, code: 'NAMESPACE_NOT_FOUND', message: 'Namespace not found' }
    if (!namespace.configPath) return { ok: false, status: 422, code: 'NAMESPACE_CONFIG_PATH_MISSING', message: 'Namespace has no configPath configured' }
    const repoRoot = await proxy.resolveRepoRoot(namespaceId)
    return { ok: true, repoRoot, filePath: join(repoRoot, 'forge', 'active-run.json') }
  } catch (error) {
    return { ok: false, status: 500, code: 'NAMESPACE_RESOLUTION_FAILURE', message: String(error) }
  }
}

/** Read the marker. `data` is null when the file does not exist. */
export async function readActiveRun({ proxy, namespaceId }) {
  const context = await resolveRepoContext(proxy, namespaceId)
  if (!context.ok) return context
  try {
    if (!existsSync(context.filePath)) return { ok: true, data: null }
    return { ok: true, data: JSON.parse(readFileSync(context.filePath, 'utf8')) }
  } catch (error) {
    return { ok: false, status: 500, code: 'ACTIVE_RUN_READ_FAILURE', message: String(error) }
  }
}

/** Write the marker. Missing caseId is a transport validation error. */
export async function writeActiveRun({ proxy, namespaceId, caseId, ticketId }) {
  if (!namespaceId || !caseId) return { ok: false, status: 400, code: 'INVALID_ACTIVE_RUN_REQUEST', message: 'namespaceId and caseId are required' }
  const context = await resolveRepoContext(proxy, namespaceId)
  if (!context.ok) return context
  try {
    mkdirSync(join(context.repoRoot, 'forge'), { recursive: true })
    const data = { caseId, ticketId: ticketId ?? null, launchedAt: new Date().toISOString() }
    writeFileSync(context.filePath, JSON.stringify(data, null, 2) + '\n', 'utf8')
    return { ok: true, data }
  } catch (error) {
    return { ok: false, status: 500, code: 'ACTIVE_RUN_WRITE_FAILURE', message: String(error) }
  }
}

/** Remove the marker. Idempotent: clearing an absent marker succeeds. */
export async function clearActiveRun({ proxy, namespaceId }) {
  const context = await resolveRepoContext(proxy, namespaceId)
  if (!context.ok) return context
  try {
    if (existsSync(context.filePath)) unlinkSync(context.filePath)
    return { ok: true, data: null }
  } catch (error) {
    return { ok: false, status: 500, code: 'ACTIVE_RUN_CLEAR_FAILURE', message: String(error) }
  }
}
