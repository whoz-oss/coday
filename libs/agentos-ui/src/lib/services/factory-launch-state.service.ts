import { inject, Injectable, signal } from '@angular/core'
import {
  AgentConfig,
  AgentConfigControllerService,
  IntegrationConfig,
  IntegrationConfigControllerService,
} from '@whoz-oss/agentos-api-client'
import { EMPTY, switchMap } from 'rxjs'
import { FactoryApiService, FactoryLaunchRequest, FactoryWorkflow } from './factory-api.service'
import { UserStateService } from './user-state.service'

export type LaunchStatus = 'idle' | 'launching' | 'success' | 'error'

export interface LaunchResult {
  pid: number
  runId: string | null
}

/** Reserved integration keys that are never backed by an IntegrationConfig row. */
const RESERVED_INTEGRATION_KEYS = new Set(['QUERY_USER', 'CASE_FILE_EXCHANGE', 'NAMESPACE_FILE_EXCHANGE'])

/**
 * Extract the FILE_ACCESS rootPath for a single agent from a pre-loaded
 * integration config map.
 *
 * Returns `{ ok: true, rootPath }` when exactly one FILE_ACCESS integration
 * with a non-empty rootPath is found among the agent's declared (non-reserved)
 * integration keys.
 *
 * Returns `{ ok: false, reason }` for any failure:
 *   - no non-reserved integrations declared (agent cannot write)
 *   - no FILE_ACCESS integration visible in the API (filesystem-loaded)
 *   - FILE_ACCESS integration present but rootPath is absent
 */
export function extractAgentRoot(
  agent: AgentConfig,
  byName: Map<string, IntegrationConfig>
): { ok: true; rootPath: string } | { ok: false; reason: string } {
  const declared = Object.keys(agent.integrations ?? {}).filter((k) => !RESERVED_INTEGRATION_KEYS.has(k))

  if (declared.length === 0) {
    return {
      ok: false,
      reason: `Agent "${agent.name}" declares no non-reserved integrations — it cannot write files.`,
    }
  }

  const fileAccessConfigs = declared
    .map((name) => byName.get(name))
    .filter((c): c is IntegrationConfig => c != null && c.integrationType === 'FILE_ACCESS')

  if (fileAccessConfigs.length === 0) {
    return {
      ok: false,
      reason:
        `Agent "${agent.name}" has no FILE_ACCESS integration visible via the API — ` +
        `it may use a filesystem integration (configPath/integrations/). ` +
        `Enter the target repository root manually.`,
    }
  }

  const rootPath: string | undefined = fileAccessConfigs[0]?.parameters?.rootPath
  if (!rootPath) {
    return {
      ok: false,
      reason: `Integration "${fileAccessConfigs[0]?.name ?? 'unknown'}" (agent "${agent.name}") has no rootPath configured.`,
    }
  }

  return { ok: true, rootPath }
}

/**
 * FactoryLaunchStateService — manages the launch form state.
 *
 * ## Agent lookup (Defect 1 fix)
 * Calls POST /api/agent-configs/search with the current user's real external ID
 * (never an empty string). Mirrors AgentStateService.listEffective().
 * If the user is not yet loaded, calls loadMe() first.
 *
 * ## FACTORY_ROOT derivation (Defect 2 fix)
 *
 * fix-loop / agentos-smoke (single agent):
 *   deriveRootForAgent() — fetches namespace integrations once, extracts the
 *   agent's FILE_ACCESS rootPath, sets derivedRoot.
 *
 * us-loop (two agents — analyst + editor):
 *   deriveTwoAgentRoots() — fetches namespace integrations once, extracts each
 *   agent's FILE_ACCESS rootPath, then requires both roots to be equal.
 *   A mismatch between analyst root and editor root is a hard error.
 *   Because the us-loop server-side preflight checks the EDITOR for colocation,
 *   the derived canonical root is the editor's root (used as FACTORY_ROOT).
 *   The analyst root must equal the editor root — if it does not, launch is
 *   blocked with a message naming both roles and their conflicting paths.
 *
 *   Because server-side defaults (factory-analyst / factory-editor) cannot be
 *   verified from the UI, the form requires EXPLICIT selection of both roles
 *   before derivation runs and before submit is enabled.
 */
@Injectable({ providedIn: 'root' })
export class FactoryLaunchStateService {
  private readonly api = inject(FactoryApiService)
  private readonly agentConfigController = inject(AgentConfigControllerService)
  private readonly integrationConfigController = inject(IntegrationConfigControllerService)
  private readonly userState = inject(UserStateService)

  readonly agents = signal<AgentConfig[]>([])
  readonly agentsLoading = signal(false)
  readonly agentsError = signal<string | null>(null)

  /**
   * Canonical root derived from the selected agent(s)' FILE_ACCESS integrations.
   * For us-loop this is the editor's root (after verifying analyst root matches).
   */
  readonly derivedRoot = signal<string | null>(null)
  readonly rootLoading = signal(false)
  readonly rootError = signal<string | null>(null)

  readonly launchStatus = signal<LaunchStatus>('idle')
  readonly launchError = signal<string | null>(null)
  readonly launchResult = signal<LaunchResult | null>(null)

  /**
   * Namespace for which agents were last successfully loaded.
   * Used to discard stale responses when the namespace changes.
   */
  private loadingForNamespace: string | null = null

  // ---------------------------------------------------------------------------
  // Agent loading
  // ---------------------------------------------------------------------------

  /**
   * Load agents for the given namespace via AgentOS (POST /api/agent-configs/search).
   *
   * Passes the current user's external ID (required by the backend @NotBlank
   * validation). If the user is not yet loaded, loadMe() is called first.
   * Falls back to manual-entry mode if AgentOS is unreachable.
   *
   * Stale guard: responses for a superseded namespace are silently discarded.
   */
  loadAgents(namespaceId: string): void {
    this.agents.set([])
    this.agentsLoading.set(true)
    this.agentsError.set(null)
    this.loadingForNamespace = namespaceId

    const doSearch = (externalId: string) => {
      if (this.loadingForNamespace !== namespaceId) return

      this.agentConfigController.searchAgentConfig({ namespaceId, userExternalId: externalId }).subscribe({
        next: (agents) => {
          if (this.loadingForNamespace !== namespaceId) return
          this.agents.set(agents)
          this.agentsLoading.set(false)
        },
        error: () => {
          if (this.loadingForNamespace !== namespaceId) return
          this.agentsLoading.set(false)
          this.agentsError.set('AgentOS unreachable — enter agent name manually.')
        },
      })
    }

    const user = this.userState.currentUser()
    if (user) {
      doSearch(user.externalId ?? user.email ?? '')
      return
    }

    this.userState
      .loadMe()
      .pipe(
        switchMap((loadedUser) => {
          doSearch(loadedUser.externalId ?? loadedUser.email ?? '')
          return EMPTY
        })
      )
      .subscribe({
        error: () => {
          if (this.loadingForNamespace !== namespaceId) return
          this.agentsLoading.set(false)
          this.agentsError.set('AgentOS unreachable — enter agent name manually.')
        },
      })
  }

  // ---------------------------------------------------------------------------
  // Root derivation — single agent (fix-loop / agentos-smoke)
  // ---------------------------------------------------------------------------

  /**
   * Derive FACTORY_ROOT from a single agent's FILE_ACCESS integration.
   * Used for fix-loop and agentos-smoke.
   */
  deriveRootForAgent(namespaceId: string, agent: AgentConfig): void {
    this.derivedRoot.set(null)
    this.rootError.set(null)
    this.rootLoading.set(true)

    this.integrationConfigController.listIntegrationConfig(namespaceId).subscribe({
      next: (configs) => {
        const byName = new Map(configs.map((c) => [c.name, c]))
        const result = extractAgentRoot(agent, byName)
        if (result.ok) {
          this.derivedRoot.set(result.rootPath)
        } else {
          this.rootError.set(result.reason)
        }
        this.rootLoading.set(false)
      },
      error: () => {
        this.rootLoading.set(false)
        this.rootError.set('Could not load integrations — enter the target repository root manually.')
      },
    })
  }

  // ---------------------------------------------------------------------------
  // Root derivation — two agents (us-loop)
  // ---------------------------------------------------------------------------

  /**
   * Derive FACTORY_ROOT for us-loop by checking BOTH analyst and editor agents.
   *
   * Both agents must have a FILE_ACCESS integration with a rootPath, and those
   * rootPaths must be equal. Any deviation is a hard error that blocks launch.
   *
   * The canonical root (set on derivedRoot) is the editor's rootPath, because
   * the server-side preflightWorkspace checks the editor for colocation.
   *
   * Fetches namespace integrations only once (shared between both checks).
   */
  deriveTwoAgentRoots(namespaceId: string, analyst: AgentConfig, editor: AgentConfig): void {
    this.derivedRoot.set(null)
    this.rootError.set(null)
    this.rootLoading.set(true)

    this.integrationConfigController.listIntegrationConfig(namespaceId).subscribe({
      next: (configs) => {
        const byName = new Map(configs.map((c) => [c.name, c]))

        const analystResult = extractAgentRoot(analyst, byName)
        const editorResult = extractAgentRoot(editor, byName)

        if (!analystResult.ok) {
          this.rootError.set(`Analyst (${analyst.name}): ${analystResult.reason}`)
          this.rootLoading.set(false)
          return
        }

        if (!editorResult.ok) {
          this.rootError.set(`Editor (${editor.name}): ${editorResult.reason}`)
          this.rootLoading.set(false)
          return
        }

        const analystRoot = analystResult.rootPath
        const editorRoot = editorResult.rootPath

        if (normalizeRoot(analystRoot) !== normalizeRoot(editorRoot)) {
          this.rootError.set(
            `Root mismatch between analyst and editor: ` +
              `"${analyst.name}" → ${analystRoot}, ` +
              `"${editor.name}" → ${editorRoot}. ` +
              `Both agents must operate on the same repository.`
          )
          this.rootLoading.set(false)
          return
        }

        // Both roots match: use the editor's root as the canonical FACTORY_ROOT,
        // consistent with what preflightWorkspace validates server-side.
        this.derivedRoot.set(editorRoot)
        this.rootLoading.set(false)
      },
      error: () => {
        this.rootLoading.set(false)
        this.rootError.set('Could not load integrations — enter the target repository root manually.')
      },
    })
  }

  /** Clear derived root state (e.g. when agent selection changes). */
  clearDerivedRoot(): void {
    this.derivedRoot.set(null)
    this.rootError.set(null)
    this.rootLoading.set(false)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Agents usable as a phase role: enabled and no subAgents. */
  usableAgents(): AgentConfig[] {
    return this.agents().filter((a) => a.enabled !== false && !a.subAgents?.length)
  }

  // ---------------------------------------------------------------------------
  // Launch
  // ---------------------------------------------------------------------------

  launch(request: FactoryLaunchRequest): void {
    if (this.launchStatus() === 'launching') return
    this.launchStatus.set('launching')
    this.launchError.set(null)
    this.launchResult.set(null)

    this.api.launchRun(request).subscribe({
      next: (response) => {
        this.launchResult.set({ pid: response.pid, runId: response.runId })
        this.launchStatus.set('success')
      },
      error: (err: { error?: { error?: string }; status?: number }) => {
        const message =
          err?.error?.error ??
          (err?.status === 400 ? 'Invalid launch request.' : 'Factory server unreachable. Is it running?')
        this.launchError.set(message)
        this.launchStatus.set('error')
      },
    })
  }

  reset(): void {
    this.launchStatus.set('idle')
    this.launchError.set(null)
    this.launchResult.set(null)
    this.agents.set([])
    this.agentsLoading.set(false)
    this.agentsError.set(null)
    this.derivedRoot.set(null)
    this.rootError.set(null)
    this.rootLoading.set(false)
    this.loadingForNamespace = null
  }

  // ---------------------------------------------------------------------------
  // Static workflow classifiers
  // ---------------------------------------------------------------------------

  static isSingleAgent(workflow: FactoryWorkflow): boolean {
    return workflow === 'fix-loop' || workflow === 'agentos-smoke'
  }

  static isNoAgent(workflow: FactoryWorkflow): boolean {
    return workflow === 'backend-oracle-check'
  }

  static isTwoAgent(workflow: FactoryWorkflow): boolean {
    return workflow === 'us-loop'
  }
}

/** Strip trailing slashes for comparison, matching preflightWorkspace.normalizeRoot. */
function normalizeRoot(p: string): string {
  return p.replace(/\/+$/, '')
}
