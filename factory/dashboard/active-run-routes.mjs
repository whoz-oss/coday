/**
 * Active-run routes — GET/POST/DELETE /api/factory/active-run
 *
 * Manages the active-run marker persisted at <repoRoot>/forge/active-run.json.
 * This file is shared across all users of the same namespace.
 *
 * Returns true when the request was handled (matched route), false otherwise.
 */

import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

/**
 * @param {{ method: string, path: string, url: URL, readBody: () => Promise<object>, send: (status: number, body: unknown) => void, proxy: object, log?: Console }} ctx
 * @returns {Promise<boolean>}
 */
export async function handleActiveRunRequest({ method, path, url, readBody, send, proxy, log = console }) {
  if (path !== '/api/factory/active-run') return false

  // GET /api/factory/active-run?namespaceId=<uuid>
  if (method === 'GET') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(400, { error: 'namespaceId query param is required' }), true
    try {
      const namespace = await proxy.fetchNamespace(namespaceId)
      if (!namespace) return send(404, { error: 'Namespace not found' }), true
      const configPath = namespace.configPath
      if (!configPath) return send(422, { error: 'Namespace has no configPath configured' }), true
      const repoRoot = await proxy.resolveRepoRoot(namespaceId)
      const filePath = join(repoRoot, 'forge', 'active-run.json')
      if (!existsSync(filePath)) return send(200, null), true
      const data = JSON.parse(readFileSync(filePath, 'utf8'))
      return send(200, data), true
    } catch (err) {
      log.error?.('active-run GET error', err)
      return send(500, { error: String(err) }), true
    }
  }

  // POST /api/factory/active-run
  // Body: { namespaceId, caseId, ticketId }
  if (method === 'POST') {
    const body = await readBody()
    const { namespaceId, caseId, ticketId } = body
    if (!namespaceId || !caseId) return send(400, { error: 'namespaceId and caseId are required' }), true
    try {
      const namespace = await proxy.fetchNamespace(namespaceId)
      if (!namespace) return send(404, { error: 'Namespace not found' }), true
      const configPath = namespace.configPath
      if (!configPath) return send(422, { error: 'Namespace has no configPath configured' }), true
      const repoRoot = await proxy.resolveRepoRoot(namespaceId)
      const forgeDir = join(repoRoot, 'forge')
      mkdirSync(forgeDir, { recursive: true })
      const filePath = join(forgeDir, 'active-run.json')
      const data = { caseId, ticketId: ticketId ?? null, launchedAt: new Date().toISOString() }
      writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n', 'utf8')
      return send(201, data), true
    } catch (err) {
      log.error?.('active-run POST error', err)
      return send(500, { error: String(err) }), true
    }
  }

  // DELETE /api/factory/active-run?namespaceId=<uuid>
  if (method === 'DELETE') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(400, { error: 'namespaceId query param is required' }), true
    try {
      const namespace = await proxy.fetchNamespace(namespaceId)
      if (!namespace) return send(404, { error: 'Namespace not found' }), true
      const configPath = namespace.configPath
      if (!configPath) return send(422, { error: 'Namespace has no configPath configured' }), true
      const repoRoot = await proxy.resolveRepoRoot(namespaceId)
      const filePath = join(repoRoot, 'forge', 'active-run.json')
      if (existsSync(filePath)) {
        const { unlinkSync } = await import('node:fs')
        unlinkSync(filePath)
      }
      return send(204, ''), true
    } catch (err) {
      log.error?.('active-run DELETE error', err)
      return send(500, { error: String(err) }), true
    }
  }

  return false
}
