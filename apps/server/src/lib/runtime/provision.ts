/**
 * Fast, local-only provisioning shared by the Nx `provision` task and the in-process orchestrator:
 * decide the profile, generate secrets, inspect Java, choose the ports, write state.json.
 *
 * It deliberately does NOT download the JARs: the server (and its first-run wizard) must come up
 * immediately, and the wizard shows the download progress. The JAR sets are fetched by the
 * `agentos` / `factory` tasks (see orchestrator.ts) while the wizard is on screen.
 */

import { getAgentosVersion, getFactoryVersion } from '../version'
import { pickPorts, PreferredPorts } from './ports'
import { inspectJava } from './java'
import { borrowsRuntime, decideProfile, parseForcedProfile, ProfileDecision, ProfileInput } from './profile'
import { ensureSecrets } from './secrets'
import { initialState, RuntimeState, StatePatch, updateState, readState } from './state'

export interface ProvisionInput extends ProfileInput {
  orchestrator: RuntimeState['orchestrator']
  /** Port the Express server will listen on (already free). */
  expressPort: number
  /** Preferred ports of the services (see `parsePreferredPorts`); the packaged defaults when absent. */
  preferredPorts?: PreferredPorts
  log?: (message: string) => void
}

export interface ProvisionResult {
  decision: ProfileDecision
  state: RuntimeState
}

export async function provision(input: ProvisionInput): Promise<ProvisionResult> {
  const log = input.log ?? (() => undefined)
  const decision = decideProfile(input)
  log(`Profile: ${decision.profile} (${decision.reason})`)

  const existing = readState(input.configDir)
  if (existing && borrowsRuntime(input, existing.provisioned, input.orchestrator)) {
    // External AgentOS on an already provisioned configDir: another instance owns the runtime (ports, progress).
    // Only an explicit CODAY_PROFILE is written, the rest is left exactly as it is.
    log('External AgentOS on a provisioned configDir: runtime state (ports, downloads) left untouched')
    const state = parseForcedProfile(input.forcedProfile)
      ? updateState(input.configDir, { profile: decision.profile })
      : existing
    return { decision, state }
  }

  if (decision.profile === 'laptop') {
    const { created } = ensureSecrets(input.configDir, log)
    if (created) log('Generated install secrets (runtime/secrets.json, mode 0600)')
  }

  const java = inspectJava()
  if (java.state !== 'ok') log(`Java: ${java.state} - ${java.hint}`)
  else log(`Java: ${java.version}`)

  const ports = await pickPorts(input.expressPort, undefined, input.preferredPorts)
  const patch: StatePatch = {
    orchestrator: input.orchestrator,
    profile: decision.profile,
    ports: {
      express: ports.express,
      agentos: ports.agentos,
      agentosBolt: ports.agentosBolt,
      factory: ports.factory,
    },
    versions: { agentos: getAgentosVersion(), factory: getFactoryVersion() },
    java,
    provisioned: true,
    // A new orchestration run: the previous run's JVM is gone.
    agentosStarted: false,
  }
  const fallback = initialState(input.orchestrator, decision.profile)
  const state = updateState(input.configDir, patch, existing ? undefined : fallback)
  // The previous run's per-service downloads are stale: this run re-checks (and re-reports) them.
  const cleaned = updateState(input.configDir, {
    downloads: { agentos: { state: 'idle' }, factory: { state: 'idle' } },
  })
  log(`Ports: express ${ports.express}, agentos ${ports.agentos} (bolt ${ports.agentosBolt}), factory ${ports.factory}`)
  return { decision, state: { ...state, downloads: cleaned.downloads, updatedAt: cleaned.updatedAt } }
}
