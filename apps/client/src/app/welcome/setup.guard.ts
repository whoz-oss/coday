import { inject } from '@angular/core'
import { CanActivateFn, Router } from '@angular/router'
import { SetupStateService } from './setup-state.service'
import { SetupStatus } from './setup.models'

/** True when this server wants the first-run wizard to be shown. */
export const wizardRequired = (status: SetupStatus | null): boolean =>
  !!status && status.mode === 'unmanaged' && status.setupEnabled && !status.setupDone

/**
 * Global guard of the application routes: while the first-run setup is not done, everything redirects to
 * `/welcome`.
 *
 * It never redirects when:
 *  - the server is not in unmanaged first-run mode (`--auth` deployments, external AgentOS, existing
 *    installs): `setupEnabled` is false there,
 *  - the setup API cannot be read (older server, network hiccup): the app must stay usable.
 *
 * Once the setup is known to be done the answer is cached for the session (no request per navigation).
 */
let setupKnownDone = false

export const resetSetupGuardCache = (): void => {
  setupKnownDone = false
}

export const setupGuard: CanActivateFn = async () => {
  if (setupKnownDone) return true
  const state = inject(SetupStateService)
  const router = inject(Router)
  const status = await state.refreshStatus()
  if (status?.setupDone) setupKnownDone = true
  return wizardRequired(status) ? router.parseUrl('/welcome') : true
}

/**
 * `/welcome` itself, reachable in every mode: the wizard on a first run, otherwise a loading page (download, Java,
 * AgentOS, Factory) that hands over to the app by itself. The browser opens on it as soon as Express listens, so it must
 * also work for the modes without a wizard (`--auth`, external AgentOS, existing install). It only records that the
 * setup is known to be done; a failed read keeps the page reachable and shows the error instead of a blank page.
 */
export const welcomeGuard: CanActivateFn = async () => {
  const status = await inject(SetupStateService).refreshStatus()
  if (status?.setupDone) setupKnownDone = true
  return true
}
