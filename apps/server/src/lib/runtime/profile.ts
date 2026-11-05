/**
 * Which security profile the managed services run with.
 *
 *  - `laptop`: the first-run experience. AgentOS and Factory run in `auth` mode, bound to loopback,
 *    with generated secrets; the identity is the email typed in the wizard.
 *  - `managed`: the behaviour that existed before the wizard (local security mode, plaintext, default
 *    binding). Kept for what must not change under users' feet:
 *      * `--auth` deployments (identity comes from the reverse proxy, no wizard),
 *      * external AgentOS (`AGENTOS_URL`), which Coday does not manage,
 *      * installs that already hold AgentOS data but never ran the wizard: switching them to `auth`
 *        would lock their existing (OS-username) user out.
 *
 * `CODAY_PROFILE=laptop|managed` forces the profile, whatever the rules below would say. Typical use: a dev server
 * (`AGENTOS_URL`) sharing the configDir of a running `coday-web`, which must keep the `laptop` profile so that the
 * identity of the wizard keeps being forwarded to its AgentOS.
 */

import * as fs from 'fs'
import { agentosDataDir } from './paths'
import { readWebSetup } from './web-setup'
import { RuntimeProfile, RuntimeState } from './state'

export interface ProfileInput {
  auth: boolean
  agentosUrl?: string
  factoryUrl?: string
  /** `AGENTOS_EXTERNAL_USERID`: the identity is imposed from outside (desktop apps, deployments). */
  externalUserId?: string
  /** `CODAY_PROFILE`: forces the profile. Anything but `laptop` / `managed` is ignored. */
  forcedProfile?: string
  configDir: string
}

export interface ProfileDecision {
  profile: RuntimeProfile
  /** Whether the first-run wizard and the /api/setup/* mutations apply. */
  setupEnabled: boolean
  reason: string
}

export function parseForcedProfile(value: string | undefined): RuntimeProfile | undefined {
  const normalized = value?.trim().toLowerCase()
  return normalized === 'laptop' || normalized === 'managed' ? normalized : undefined
}

/**
 * The runtime (state.json: ports, profile, download progress) belongs to the process that started AgentOS. A process
 * that only points at an external AgentOS, on a configDir that was already provisioned, borrows it: it must not
 * rewrite ports nor reset the progress, which another running instance reads at every request.
 *
 * Only the in-process orchestrator borrows: it receives its Express port directly. The Nx `server` task reads its
 * Express port from state.json, which its own `provision` task must therefore always write.
 */
export const borrowsRuntime = (
  input: ProfileInput,
  provisioned: boolean,
  orchestrator: RuntimeState['orchestrator']
): boolean => !!input.agentosUrl && provisioned && orchestrator === 'in-process'

export function decideProfile(input: ProfileInput, exists: (p: string) => boolean = fs.existsSync): ProfileDecision {
  const forced = parseForcedProfile(input.forcedProfile)
  if (forced === 'laptop') {
    // The wizard needs a runtime of our own: not with an external AgentOS, nor behind `--auth`.
    return { profile: 'laptop', setupEnabled: !input.agentosUrl && !input.auth, reason: 'forced by CODAY_PROFILE' }
  }
  if (forced === 'managed') return { profile: 'managed', setupEnabled: false, reason: 'forced by CODAY_PROFILE' }
  if (input.agentosUrl) return { profile: 'managed', setupEnabled: false, reason: 'external AgentOS (AGENTOS_URL)' }
  if (input.auth) return { profile: 'managed', setupEnabled: false, reason: '--auth deployment' }
  if (input.externalUserId) {
    return { profile: 'managed', setupEnabled: false, reason: 'identity imposed by AGENTOS_EXTERNAL_USERID' }
  }
  const hasIdentity = !!readWebSetup(input.configDir).identity
  if (!hasIdentity && exists(agentosDataDir(input.configDir))) {
    return { profile: 'managed', setupEnabled: false, reason: 'existing install without first-run setup' }
  }
  return { profile: 'laptop', setupEnabled: true, reason: 'first-run / laptop mode' }
}
