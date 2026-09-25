/**
 * Workstream service — business logic for forge/bmad/workstreams.toml.
 *
 * The repository root is resolved from the namespace configPath via the AgentOS
 * proxy; the HTTP route only validates transport input and maps the result.
 */

import { existsSync, readFileSync, mkdirSync, appendFileSync } from 'node:fs'
import { join } from 'node:path'

const SLUG = /^[a-z0-9]+(?:-[a-z0-9]+)*$/

/** Parse workstream entries from a TOML file. Pure: [] when absent. */
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

async function resolveRepoRoot(proxy, namespaceId) {
  if (!namespaceId) return { ok: false, status: 400, code: 'MISSING_NAMESPACE_ID', message: 'namespaceId query param is required' }
  try {
    const namespace = await proxy.fetchNamespace(namespaceId)
    if (!namespace) return { ok: false, status: 404, code: 'NAMESPACE_NOT_FOUND', message: 'Namespace not found' }
    if (!namespace.configPath) return { ok: false, status: 422, code: 'NAMESPACE_CONFIG_PATH_MISSING', message: 'Namespace has no configPath configured' }
    return { ok: true, repoRoot: await proxy.resolveRepoRoot(namespaceId) }
  } catch (error) {
    return { ok: false, status: 500, code: 'NAMESPACE_RESOLUTION_FAILURE', message: String(error) }
  }
}

/** List workstreams for a namespace. */
export async function listWorkstreams({ proxy, namespaceId }) {
  const resolved = await resolveRepoRoot(proxy, namespaceId)
  if (!resolved.ok) return resolved
  try {
    return { ok: true, data: readWorkstreams(resolved.repoRoot) }
  } catch (error) {
    return { ok: false, status: 500, code: 'WORKSTREAM_READ_FAILURE', message: String(error) }
  }
}

/** Create a workstream entry, enforcing slug/name/status and uniqueness. */
export async function createWorkstream({ proxy, namespaceId, slug, name, status }) {
  if (!namespaceId) return { ok: false, status: 400, code: 'MISSING_NAMESPACE_ID', message: 'namespaceId est requis' }
  if (!slug || !name || !status) return { ok: false, status: 400, code: 'INVALID_WORKSTREAM_REQUEST', message: 'slug, name et status sont requis' }
  if (!SLUG.test(slug)) {
    return { ok: false, status: 400, code: 'INVALID_WORKSTREAM_SLUG', message: 'slug invalide : lettres minuscules, chiffres et tirets uniquement (ex: talent-portal)' }
  }
  const resolved = await resolveRepoRoot(proxy, namespaceId)
  if (!resolved.ok) return resolved
  try {
    const tomlPath = join(resolved.repoRoot, 'forge/bmad/workstreams.toml')
    if (readWorkstreams(resolved.repoRoot).some((ws) => ws.slug === slug)) {
      return { ok: false, status: 409, code: 'WORKSTREAM_ALREADY_EXISTS', message: `Le workstream '${slug}' existe déjà` }
    }
    mkdirSync(join(resolved.repoRoot, 'forge/bmad/workstreams', slug), { recursive: true })
    appendFileSync(tomlPath, `\n[workstreams.${slug}]\nname = "${name}"\nstatus = "${status}"\nroot = "forge/bmad/workstreams/${slug}"\n`, 'utf8')
    return { ok: true, data: { slug, name, status } }
  } catch (error) {
    return { ok: false, status: 500, code: 'WORKSTREAM_WRITE_FAILURE', message: String(error) }
  }
}
