import { inject } from '@angular/core'
import { ActivatedRouteSnapshot, CanActivateFn } from '@angular/router'
import { combineLatest, filter, lastValueFrom, take } from 'rxjs'
import { ThreadStateService } from '../services/thread-state.service'
import { map, tap } from 'rxjs/operators'

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

  console.log('[THREAD GUARD] Checking thread:', threadId)
  console.log('[THREAD GUARD] Current selected thread:', threadState.getSelectedThreadId())

  if (threadState.getSelectedThreadId() === threadId) {
    console.log('[THREAD GUARD] Thread already selected, allowing navigation')
    return true
  }

  console.log('[THREAD GUARD] Selecting thread:', threadId)
  threadState.selectThread(threadId)
  
  try {
    const currentThreadId = await lastValueFrom(
      combineLatest([threadState.isLoading$, threadState.selectedThread$]).pipe(
        tap(([isLoading, thread]) => console.log(`🐼 [${isLoading ? 'loading' : 'loaded'}]`, thread?.id)),
        filter(([isLoading, threadDetails]) => !isLoading && !!threadDetails),
        take(1),
        map(([_, thread]) => thread?.id)
      )
    )
    const validity = currentThreadId === threadId

    if (!validity) {
      console.error('[THREAD GUARD] Thread ID mismatch - route:', threadId, 'loaded:', currentThreadId)
    } else {
      console.log('[THREAD GUARD] Thread validated, allowing navigation')
    }

    return validity
  } catch (error) {
    console.error('[THREAD GUARD] Error loading thread:', error)
    return false
  }
}
