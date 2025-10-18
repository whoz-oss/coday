import { inject } from '@angular/core'
import { ActivatedRouteSnapshot, CanActivateFn } from '@angular/router'
import { lastValueFrom, take } from 'rxjs'
import { ThreadStateService } from '../services/thread-state.service'

/**
 * Route guard that ensures the thread state is synchronized with route parameters.
 *
 * This guard is essential for deep links: when a user navigates directly to
 * /project/:projectName/thread/:threadId the ThreadStateService needs to be informed
 * of the selected thread.
 *
 * Usage: Add to routes that require a selected thread:
 * ```
 * {
 *   path: 'project/:projectName/thread/:threadId',
 *   component: MainAppComponent,
 *   canActivate: [projectStateGuard, threadStateGuard]
 * }
 * ```
 */
export const threadStateGuard: CanActivateFn = async (route: ActivatedRouteSnapshot) => {
  const threadState = inject(ThreadStateService)
  const threadId = route.params['threadId']

  // If no thread ID in route, allow navigation (will be handled by component)
  if (!threadId) {
    console.log('[THREAD GUARD] no threadId in route')
    return false
  }

  if (threadState.getSelectedThreadId() === threadId) {
    return true
  }

  threadState.selectThread(threadId)
  const currentThread = await lastValueFrom(threadState.selectedThread$.pipe(take(1)))
  const validity = currentThread?.id === threadId

  if (!validity) {
    console.log('[THREAD GUARD] current thread id not matching the route')
  }

  return validity
}
