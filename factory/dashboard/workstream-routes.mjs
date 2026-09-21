/**
 * Workstream routes — GET/POST /api/factory/workstreams
 *
 * Reads and writes workstream definitions from <repoRoot>/forge/bmad/workstreams.toml.
 * The repo root is resolved from the namespace configPath via the AgentOS proxy.
 *
 * Returns true when the request was handled, false otherwise.
 */

import { existsSync, readFileSync, mkdirSync, appendFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * Parse workstream entries from a TOML file.
 *
 * @param {string} repoRoot  Absolute path to the repository root.
 * @returns {Array<{ slug: string, name: string, status: string }>}
 */
export function readWorkstreams(repoRoot) {
  const tomlPath = join(repoRoot, 'forge/bmad/workstreams.toml')
  if (!existsSync(tomlPath)) return []

  const content = readFileSync(tomlPath, 'utf8')
  const workstreams = []

  // Parse [workstreams.<slug>] sections
  const sectionRegex = /^\[workstreams\.([a-z0-9]+(?:-[a-z0-9]+)*)\]$/gm
  let match
  while ((match = sectionRegex.exec(content)) !== null) {
    const slug = match[1]
    const sectionStart = match.index + match[0].length
    const nextSection = /^\[/m.exec(content.slice(sectionStart))
    const sectionContent = nextSection
      ? content.slice(sectionStart, sectionStart + nextSection.index)
      : content.slice(sectionStart)

    const nameMatch = /^name\s*=\s*"([^"]+)"/m.exec(sectionContent)
    const statusMatch = /^status\s*=\s*"([^"]+)"/m.exec(sectionContent)

    if (nameMatch && statusMatch) {
      workstreams.push({ slug, name: nameMatch[1], status: statusMatch[1] })
    }
  }

  return workstreams
}

/**
 * @param {{ method: string, path: string, url: URL, readBody: () => Promise<object>, send: (status: number, body: unknown) => void, proxy: object, log?: Console }} ctx
 * @returns {Promise<boolean>}
 */
export async function handleWorkstreamRequest({ method, path, url, readBody, send, proxy, log = console }) {
  if (path !== '/api/factory/workstreams') return false

  // GET /api/factory/workstreams?namespaceId=<uuid>
  //
  // Resolves the repoRoot from the namespace configPath, then reads
  // forge/bmad/workstreams.toml. Returns [] when the file does not exist.
  if (method === 'GET') {
    const namespaceId = url.searchParams.get('namespaceId')
    if (!namespaceId) return send(400, { error: 'namespaceId query param is required' }), true
    try {
      const namespace = await proxy.fetchNamespace(namespaceId)
      if (!namespace) return send(404, { error: 'Namespace not found' }), true
      const configPath = namespace.configPath
      if (!configPath) return send(422, { error: 'Namespace has no configPath configured' }), true
      const repoRoot = await proxy.resolveRepoRoot(namespaceId)
      return send(200, readWorkstreams(repoRoot)), true
    } catch (err) {
      log.error?.('workstreams GET error', err)
      return send(500, { error: String(err) }), true
    }
  }

  // POST /api/factory/workstreams
  //
  // Creates a new workstream entry: validates slug + name + status, appends to
  // forge/bmad/workstreams.toml, and creates the workstream directory.
  if (method === 'POST') {
    const body = await readBody()
    const { namespaceId, slug, name, status } = body

    if (!namespaceId) return send(400, { error: 'namespaceId est requis' }), true
    if (!slug || !name || !status) return send(400, { error: 'slug, name et status sont requis' }), true

    const slugRegex = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
    if (!slugRegex.test(slug)) {
      return send(400, { error: 'slug invalide : lettres minuscules, chiffres et tirets uniquement (ex: talent-portal)' }), true
    }

    try {
      const namespace = await proxy.fetchNamespace(namespaceId)
      if (!namespace) return send(404, { error: 'Namespace not found' }), true
      const configPath = namespace.configPath
      if (!configPath) return send(422, { error: 'Namespace has no configPath configured' }), true
      const repoRoot = await proxy.resolveRepoRoot(namespaceId)
      const tomlPath = join(repoRoot, 'forge/bmad/workstreams.toml')

      const existing = readWorkstreams(repoRoot)
      if (existing.some((ws) => ws.slug === slug)) {
        return send(409, { error: `Le workstream '${slug}' existe déjà` }), true
      }

      const wsDir = join(repoRoot, 'forge/bmad/workstreams', slug)
      mkdirSync(wsDir, { recursive: true })

      const tomlSection = `\n[workstreams.${slug}]\nname = "${name}"\nstatus = "${status}"\nroot = "forge/bmad/workstreams/${slug}"\n`
      appendFileSync(tomlPath, tomlSection, 'utf8')

      return send(201, { slug, name, status }), true
    } catch (err) {
      log.error?.('workstreams POST error', err)
      return send(500, { error: String(err) }), true
    }
  }

  return false
}
