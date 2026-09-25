/**
 * Preflight inspections performed before driving a worker.
 *
 * Every check is fail-closed: when in doubt, the Factory refuses to start
 * rather than assuming. The failure `reason` is human-actionable and includes
 * the inventory needed to relaunch correctly.
 */

import { realpathSync } from 'node:fs'

import type {
  WorkspaceIntegration,
  WorkerConfig,
  WorkerInspectionResult,
  WorkspaceInspectionResult,
} from '../../ports/agent-runtime-gateway.js'
import type { AgentConfigDTO, IntegrationConfigDTO } from './agentos-dtos.js'

/** Integration keys resolved by the service, never by a WorkspaceIntegration. */
const RESERVED_INTEGRATIONS = new Set(['QUERY_USER', 'CASE_FILE_EXCHANGE', 'NAMESPACE_FILE_EXCHANGE', 'FACTORY'])

/** Dependencies of the capability inspector. */
export interface AgentOsCapabilityInspectorDeps {
  listAgentConfigs(namespaceId: string): Promise<AgentConfigDTO[]>
  listIntegrationConfigs(namespaceId: string): Promise<IntegrationConfigDTO[]>
  realpath?: (path: string) => string
}

/** Inspector surface consumed by the runtime adapter. */
export interface AgentOsCapabilityInspector {
  inspectWorker(namespaceId: string, workerName: string): Promise<WorkerInspectionResult>
  preflightWorkspace(namespaceId: string, agent: WorkerConfig, repoRoot: string): Promise<WorkspaceInspectionResult>
  preflightWritableWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspaceInspectionResult>
  preflightReadOnlyWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspaceInspectionResult>
}

/** Normalizes an absolute path for comparison: strips the trailing separator. */
export function normalizeRoot(p: string): string {
  return p.replace(/\/+$/, '')
}

/**
 * Renders the namespace agent inventory, marking those usable as a phase role
 * (enabled and without `subAgents`).
 */
export function formatAgentInventory(agents: WorkerConfig[]): string {
  if (agents.length === 0) {
    return 'Aucun agent dans ce namespace.'
  }

  const lines = agents
    .slice()
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((a) => {
      const subs = Array.isArray(a.subAgents) ? a.subAgents : []
      const usable = a.enabled !== false && subs.length === 0
      const marker = usable ? '  \u2713' : '  \u2717'
      const notes: string[] = []
      if (a.enabled === false) notes.push('désactivé')
      if (subs.length > 0) notes.push(`subAgents=[${subs.join(', ')}]`)
      const suffix = notes.length > 0 ? `  (${notes.join(', ')})` : ''
      return `${marker} ${a.name}${suffix}`
    })

  return [
    'Agents du namespace (\u2713 = utilisable comme r\u00f4le de phase) :',
    ...lines,
    '',
    'Relancer avec FACTORY_AGENT=<nom>.',
  ].join('\n')
}

export function createAgentOsCapabilityInspector(deps: AgentOsCapabilityInspectorDeps): AgentOsCapabilityInspector {
  const realpath = deps.realpath ?? realpathSync

  async function inspectWorker(namespaceId: string, workerName: string): Promise<WorkerInspectionResult> {
    let agents: AgentConfigDTO[]
    try {
      agents = await deps.listAgentConfigs(namespaceId)
    } catch (err) {
      return { ok: false, reason: `Impossible de lister les agents : ${err}`, worker: null }
    }

    const agent = agents.find((a) => a.name === workerName)
    if (!agent) {
      return {
        ok: false,
        reason:
          `Agent "${workerName}" introuvable dans le namespace. ` +
          `Une @mention non résolue bascule silencieusement sur l'agent par défaut côté ` +
          `AgentOS — le run aurait fait travailler quelqu'un d'autre sans le dire.\n\n` +
          formatAgentInventory(agents),
        worker: null,
      }
    }

    if (agent.enabled === false) {
      return {
        ok: false,
        reason:
          `Agent "${workerName}" est désactivé. Une @mention qui ne résout pas bascule ` +
          `silencieusement sur l'agent par défaut.\n\n` +
          formatAgentInventory(agents),
        worker: agent,
      }
    }

    if (Array.isArray(agent.subAgents) && agent.subAgents.length > 0) {
      return {
        ok: false,
        reason:
          `Agent "${workerName}" déclare subAgents=[${agent.subAgents.join(', ')}]. ` +
          `Un rôle de phase ne délègue pas : DelegationTool parallélise sans condition, ` +
          `ce qui rendrait l'ordonnancement de l'orchestrateur inopérant.\n\n` +
          formatAgentInventory(agents),
        worker: agent,
      }
    }

    return { ok: true, reason: null, worker: agent }
  }

  async function preflightWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspaceInspectionResult> {
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(['submit_step_result'])) {
      return { ok: false, reason: 'FACTORY must grant exactly submit_step_result.', rootPath: null, integration: null }
    }

    const declared = Object.keys(agent.integrations ?? {}).filter((k) => !RESERVED_INTEGRATIONS.has(k))
    if (declared.length === 0) {
      return {
        ok: false,
        reason:
          `Agent "${agent.name}" ne déclare aucune intégration hors clés réservées : ` +
          `il n'a aucun outil d'écriture et ne pourra rien modifier.`,
        rootPath: null,
        integration: null,
      }
    }

    let configs: IntegrationConfigDTO[]
    try {
      configs = await deps.listIntegrationConfigs(namespaceId)
    } catch (err) {
      return { ok: false, reason: `Impossible de lister les intégrations : ${err}`, rootPath: null, integration: null }
    }

    const byName = new Map(configs.map((c) => [c.name, c]))

    // Only FILE_ACCESS integrations can break colocalization. Integrations
    // without a rootPath (AI, MEMORY, ANGULAR_MCP, CHROME_DEVTOOLS, …) are
    // ignored here even when absent from the REST API.
    const unverifiable = declared.filter((name) => {
      if (byName.has(name)) return false
      const fromAgent = (agent.integrations ?? {})[name]
      if (fromAgent === null || Array.isArray(fromAgent) || typeof fromAgent !== 'object') return false
      return true
    })
    if (unverifiable.length > 0) {
      return {
        ok: false,
        reason:
          `Intégration(s) déclarée(s) mais absente(s) de l'API : ${unverifiable.join(', ')}.\n` +
          `Elles sont probablement chargées depuis le disque ` +
          `({configPath}/integrations/), où leur rootPath n'est pas vérifiable par REST.\n` +
          `L'orchestrateur refuse de partir sans pouvoir garantir que l'agent écrit dans ` +
          `l'arbre qu'il va compiler.`,
        rootPath: null,
        integration: null,
      }
    }

    const fileAccess = declared
      .map((name) => byName.get(name))
      .filter((c): c is IntegrationConfigDTO => c != null && c.integrationType === 'FILE_ACCESS')

    if (fileAccess.length === 0) {
      return {
        ok: false,
        reason: `Agent "${agent.name}" n'a aucune intégration FILE_ACCESS : il ne peut rien écrire.`,
        rootPath: null,
        integration: null,
      }
    }

    const expected = normalizeRoot(repoRoot)

    for (const cfg of fileAccess) {
      const rootPath = cfg.parameters?.rootPath
      if (!rootPath) {
        return {
          ok: false,
          reason: `L'intégration "${cfg.name}" n'a pas de rootPath.`,
          rootPath: null,
          integration: null,
        }
      }

      if (normalizeRoot(rootPath) !== expected) {
        return {
          ok: false,
          reason:
            `Colocalisation rompue sur l'intégration "${cfg.name}".\n` +
            `  rootPath de l'agent   : ${rootPath}\n` +
            `  racine de l'orchestrateur : ${repoRoot}\n` +
            `L'agent écrirait dans un arbre et l'oracle en compilerait un autre : le verdict ` +
            `porterait sur un travail invisible.`,
          rootPath,
          integration: cfg as WorkspaceIntegration,
        }
      }

      if (cfg.parameters?.readOnly === true) {
        return {
          ok: false,
          reason: `L'intégration "${cfg.name}" est en readOnly : l'agent ne peut rien écrire.`,
          rootPath,
          integration: cfg as WorkspaceIntegration,
        }
      }
    }

    const first = fileAccess[0]
    const firstRoot = first?.parameters?.rootPath
    return {
      ok: true,
      reason: null,
      rootPath: firstRoot ? normalizeRoot(firstRoot) : null,
      integration: (first as WorkspaceIntegration | undefined) ?? null,
    }
  }

  async function preflightWritableWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspaceInspectionResult> {
    if (!Array.isArray(agent.integrations?.QUERY_USER) || agent.integrations.QUERY_USER.length !== 0) {
      return {
        ok: false,
        reason: 'QUERY_USER must be explicitly disabled with an empty allowlist.',
        rootPath: null,
        integration: null,
      }
    }
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(['submit_step_result'])) {
      return { ok: false, reason: 'FACTORY must grant exactly submit_step_result.', rootPath: null, integration: null }
    }
    const names = Object.keys(agent.integrations ?? {}).filter((name) => !RESERVED_INTEGRATIONS.has(name))
    if (names.length !== 1) {
      return {
        ok: false,
        reason: `Editor must declare exactly one non-reserved integration; found: ${names.join(', ') || '(none)'}.`,
        rootPath: null,
        integration: null,
      }
    }

    let configs: IntegrationConfigDTO[]
    try {
      configs = await deps.listIntegrationConfigs(namespaceId)
    } catch (error) {
      return { ok: false, reason: `Unable to list integrations: ${error}`, rootPath: null, integration: null }
    }

    const target = names[0]
    const integration = configs.find((config) => config.name === target)
    let actual: string | null = null
    const declaredRoot = integration?.parameters?.rootPath
    if (declaredRoot) {
      try {
        actual = realpath(declaredRoot)
      } catch {
        actual = null
      }
    }

    if (
      !integration ||
      integration.integrationType !== 'FILE_ACCESS' ||
      !actual ||
      normalizeRoot(actual) !== normalizeRoot(repoRoot) ||
      integration.parameters?.readOnly !== false
    ) {
      return {
        ok: false,
        reason: 'FILE_ACCESS must use canonical repoRoot with readOnly:false.',
        rootPath: declaredRoot ?? null,
        integration: null,
      }
    }

    return { ok: true, reason: null, rootPath: normalizeRoot(actual), integration: integration as WorkspaceIntegration }
  }

  async function preflightReadOnlyWorkspace(
    namespaceId: string,
    agent: WorkerConfig,
    repoRoot: string
  ): Promise<WorkspaceInspectionResult> {
    const declared = Object.keys(agent.integrations ?? {})
    if (!Array.isArray(agent.integrations?.QUERY_USER) || agent.integrations.QUERY_USER.length !== 0) {
      return {
        ok: false,
        reason: 'QUERY_USER must be explicitly disabled with an empty allowlist for automated analysis.',
        rootPath: null,
        integration: null,
      }
    }
    if (JSON.stringify(agent.integrations?.FACTORY) !== JSON.stringify(['submit_step_result'])) {
      return { ok: false, reason: 'FACTORY must grant exactly submit_step_result.', rootPath: null, integration: null }
    }
    const nonReserved = declared.filter((name) => !RESERVED_INTEGRATIONS.has(name))
    if (nonReserved.length !== 1) {
      return {
        ok: false,
        reason: `Read-only analyst must declare exactly one non-reserved integration; found: ${nonReserved.join(', ') || '(none)'}.`,
        rootPath: null,
        integration: null,
      }
    }

    let configs: IntegrationConfigDTO[]
    try {
      configs = await deps.listIntegrationConfigs(namespaceId)
    } catch (error) {
      return { ok: false, reason: `Unable to list integrations: ${error}`, rootPath: null, integration: null }
    }

    const target = nonReserved[0]
    const integration = configs.find((config) => config.name === target)
    if (!integration || integration.integrationType !== 'FILE_ACCESS') {
      return {
        ok: false,
        reason: `Read-only analyst integration ${target} must resolve to FILE_ACCESS.`,
        rootPath: null,
        integration: null,
      }
    }

    const rootPath = integration.parameters?.rootPath
    let canonicalRoot: string | null = null
    if (rootPath) {
      try {
        canonicalRoot = realpath(rootPath)
      } catch {
        canonicalRoot = null
      }
    }

    if (
      !canonicalRoot ||
      normalizeRoot(canonicalRoot) !== normalizeRoot(repoRoot) ||
      integration.parameters?.readOnly !== true
    ) {
      return {
        ok: false,
        reason: 'FILE_ACCESS must use the canonical target repoRoot with readOnly:true.',
        rootPath: rootPath ?? null,
        integration: null,
      }
    }

    return {
      ok: true,
      reason: null,
      rootPath: normalizeRoot(canonicalRoot),
      integration: integration as WorkspaceIntegration,
    }
  }

  return { inspectWorker, preflightWorkspace, preflightWritableWorkspace, preflightReadOnlyWorkspace }
}
