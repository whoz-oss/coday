import { CanActivateFn } from '@angular/router'

/**
 * agentosReadyGuard — protects child routes until the shell is initialized.
 *
 * TODO: once NamespaceStateService is available, replace the stub below with:
 *
 *   const namespaceState = inject(NamespaceStateService)
 *   return namespaceState.initialized$.pipe(
 *     filter(Boolean),
 *     take(1),
 *   )
 *
 * For now, always returns true (shell is considered immediately ready).
 */
export const agentosReadyGuard: CanActivateFn = () => {
  return true
}
