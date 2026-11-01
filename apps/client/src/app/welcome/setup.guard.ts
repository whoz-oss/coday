import { inject } from '@angular/core'
import { CanActivateFn, Router } from '@angular/router'
import { SetupStateService } from './setup-state.service'
import { SetupStatus } from './setup.models'

/** True when this server wants the first-run wizard to be shown. */
export const wizardRequired = (status: SetupStatus | null): boolean =>
  !!status && status.mode === 'laptop' && status.setupEnabled && !status.setupDone

/**
 * Global guard of the application routes: while the first-run setup is not done, everything redirects to
 * `/welcome`.
 *
 * It never redirects when:
 *  - the server is not in laptop/managed-first-run mode (`--auth` deployments, external AgentOS, existing
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

/** `/welcome` itself: once the setup is done (or not applicable) there is nothing to welcome. */
export const welcomeGuard: CanActivateFn = async () => {
  const state = inject(SetupStateService)
  const router = inject(Router)
  const status = await state.refreshStatus()
  if (status?.setupDone) setupKnownDone = true
  // A failed read keeps the wizard reachable: it shows the error instead of a blank page.
  return status && !wizardRequired(status) ? router.parseUrl('/home') : true
}
