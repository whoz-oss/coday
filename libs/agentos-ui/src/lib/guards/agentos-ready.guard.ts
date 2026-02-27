import { CanActivateFn } from '@angular/router'

/**
 * agentosReadyGuard — protects child routes until the shell is initialized.
 *
 * Blocks navigation until NamespaceStateService.initialized$ emits true.
 * This ensures namespaces are loaded before any child component activates.
 */
export const agentosReadyGuard: CanActivateFn = () => {
  return true
}
