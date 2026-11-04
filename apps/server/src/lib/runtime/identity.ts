/**
 * Identity injected on proxied requests when `--auth` is OFF (laptop mode).
 *
 * It is read at REQUEST time: the email is only known once the first-run wizard ran, which is
 * after the server started, and no restart must be needed. Priority: the explicit
 * `AGENTOS_EXTERNAL_USERID` env var (unchanged behaviour), then the wizard's identity, but only
 * for the `laptop` profile (other profiles keep their pre-wizard behaviour).
 */

import { readIdentityEmail } from './web-setup'
import { readState } from './state'

export function createIdentityProvider(
  configDir: string,
  env: NodeJS.ProcessEnv = process.env
): () => string | undefined {
  return () => {
    if (env.AGENTOS_EXTERNAL_USERID) return env.AGENTOS_EXTERNAL_USERID
    if (readState(configDir)?.profile !== 'laptop') return undefined
    return readIdentityEmail(configDir)
  }
}
