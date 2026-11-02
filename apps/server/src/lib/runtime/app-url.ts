/**
 * The URL a human should open and WHEN: shared by the browser auto-open and by the `status` pane, so they can never
 * disagree.
 *
 * `/welcome` while the first-run wizard is pending (laptop profile and `setupDone` not set, the same condition the
 * client uses to redirect), the app root otherwise. Undefined until provisioning chose the Express port.
 */

import { RuntimeState } from './state'
import { WebSetup } from './web-setup'

/** True while the first-run wizard is what the user must see (laptop profile, setup not done). */
export const isFirstRun = (state: Pick<RuntimeState, 'profile'> | undefined, setup: WebSetup): boolean =>
  state?.profile === 'laptop' && !setup.setupDone

export function appUrl(
  state: Pick<RuntimeState, 'profile' | 'ports'> | undefined,
  setup: WebSetup
): string | undefined {
  const port = state?.ports.express
  if (!state || !port) return undefined
  return `http://localhost:${port}${isFirstRun(state, setup) ? '/welcome' : '/'}`
}

/**
 * - `now`: the wizard only talks to `/api/setup/*`, which Express answers without AgentOS.
 * - `when-backend-ready`: the app itself (`/`) calls AgentOS straight away; its route guard shows a blank page until
 *   AgentOS answers (up to 90 s), so opening before that is pointless.
 */
export type OpenTiming = 'now' | 'when-backend-ready'

export const openTiming = (state: Pick<RuntimeState, 'profile'> | undefined, setup: WebSetup): OpenTiming =>
  isFirstRun(state, setup) ? 'now' : 'when-backend-ready'
