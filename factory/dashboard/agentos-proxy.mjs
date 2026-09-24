/**
 * AgentOS proxy — thin HTTP client for AgentOS API calls.
 *
 * All functions are pure: they receive their configuration at construction time
 * and never read from module-level globals or process.env directly.
 *
 * Usage:
 *   import { createAgentOsProxy } from './agentos-proxy.mjs'
 *   const proxy = createAgentOsProxy({ agentosUrl: AGENTOS_URL, resolvedFactoryUser: RESOLVED_FACTORY_USER })
 */

import { dirname, join } from 'node:path'

/**
 * @param {{ agentosUrl: string, resolvedFactoryUser?: string }} config
 */
export function createAgentOsProxy({ agentosUrl, resolvedFactoryUser }) {
  function agentosHeaders() {
    const headers = {}
    if (resolvedFactoryUser) headers['X-External-User-Id'] = resolvedFactoryUser
    return headers
  }

  /**
   * List agent configs for a namespace.
   * @param {string} namespaceId
   */
  async function fetchAgents(namespaceId) {
    const url = `${agentosUrl}/api/agent-configs/by-parentId/${namespaceId}`
    const res = await fetch(url, { headers: agentosHeaders() })
    if (!res.ok) throw new Error(`AgentOS ${res.status}`)
    return res.json()
  }

  /**
   * Fetch a namespace record. Returns null when 404.
   * @param {string} namespaceId
   */
  async function fetchNamespace(namespaceId) {
    const url = `${agentosUrl}/api/namespaces/${encodeURIComponent(namespaceId)}`
    const res = await fetch(url, { headers: agentosHeaders() })
    if (res.status === 404) return null
    if (!res.ok) throw new Error(`AgentOS ${res.status}`)
    return res.json()
  }

  /**
   * Fetch all events for a case.
   *
   * POURQUOI UN PROXY D’ÉVÉNEMENTS (décision O-B, 2026-08-22)
   * ---------------------------------------------------------
   * Le registre ne contient aucun texte produit par un LLM — c’est l’invariant 2.
   * Le récit vit dans AgentOS, pas ici. Plutôt que de dupliquer ce texte dans
   * les JSONL, le dashboard va le chercher à la source au moment de l’affichage.
   *
   * @param {string} caseId
   */
  async function fetchCaseEvents(caseId) {
    const url = `${agentosUrl}/api/case-events/by-parentId/${caseId}`
    const res = await fetch(url, { headers: agentosHeaders() })
    if (!res.ok) throw new Error(`AgentOS ${res.status}`)
    return res.json()
  }

  /**
   * Resolve the repo root from the namespace configPath.
   * Returns null when the namespace is not found or has no configPath.
   * @param {string} namespaceId
   */
  async function resolveRepoRoot(namespaceId) {
    const namespace = await fetchNamespace(namespaceId)
    if (!namespace?.configPath) return null
    return dirname(namespace.configPath.replace(/\/+$/, ''))
  }

  /**
   * Resolve the Forge run store root from the namespace configPath.
   * runStoreRoot = dirname(configPath) + '/forge/factory-runs'
   * Returns null when the namespace is not found or has no configPath.
   * @param {string} namespaceId
   */
  async function resolveRunStoreRoot(namespaceId) {
    const repoRoot = await resolveRepoRoot(namespaceId)
    return repoRoot ? join(repoRoot, 'forge', 'factory-runs') : null
  }

  return { fetchAgents, fetchNamespace, fetchCaseEvents, resolveRepoRoot, resolveRunStoreRoot }
}
