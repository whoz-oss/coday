import { firstValueFrom, Observable, of, timeout } from 'rxjs'
import { catchError } from 'rxjs/operators'

/** How long app bootstrap may wait for `/me`: a proxy to an AgentOS that is still booting can hang. */
export const CURRENT_USER_TIMEOUT_MS = 5_000

/**
 * Best-effort load of the current user at app startup. Never rejects and never waits longer than
 * `timeoutMs`: APP_INITIALIZER blocks the bootstrap, so a hanging request here is a blank app.
 * Components that need the user load it again themselves when the signal is still empty.
 */
export async function loadCurrentUser(
  userState: { loadMe(): Observable<unknown> },
  timeoutMs = CURRENT_USER_TIMEOUT_MS
): Promise<void> {
  await firstValueFrom(
    userState.loadMe().pipe(
      timeout(timeoutMs),
      catchError((err) => {
        console.warn('Failed to load current user', err)
        return of(null)
      })
    ),
    { defaultValue: null }
  )
}
