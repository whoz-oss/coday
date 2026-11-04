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
 */

import * as fs from 'fs'
import { agentosDataDir } from './paths'
import { readWebSetup } from './web-setup'
import { RuntimeProfile } from './state'

export interface ProfileInput {
  auth: boolean
  agentosUrl?: string
  factoryUrl?: string
  /** `AGENTOS_EXTERNAL_USERID`: the identity is imposed from outside (desktop apps, deployments). */
  externalUserId?: string
  configDir: string
}

export interface ProfileDecision {
  profile: RuntimeProfile
  /** Whether the first-run wizard and the /api/setup/* mutations apply. */
  setupEnabled: boolean
  reason: string
}

export function decideProfile(input: ProfileInput, exists: (p: string) => boolean = fs.existsSync): ProfileDecision {
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
