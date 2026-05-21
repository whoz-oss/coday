import { catchError, Observable, of, shareReplay, switchMap } from 'rxjs'

/**
 * Multicast, refresh-driven, default-safe loader for a single resource view.
 *
 * Use it to expose a stream that:
 *   - re-emits whenever `refresh$` fires (typical: a `BehaviorSubject<void>` triggered by
 *     `create`/`update`/`delete` of a state service);
 *   - falls back to `fallback` if `loader()` errors (e.g. 5xx, 403) so a transient failure
 *     doesn't blank the consumer's view;
 *   - shares one HTTP fan-out across concurrent subscribers via `shareReplay`.
 *
 * @example
 *   readonly userGlobal$ = multicastRefreshable(this.refresh$, () => this.loadX('global'), [])
 */
export function multicastRefreshable<T>(
  refresh$: Observable<unknown>,
  loader: () => Observable<T>,
  fallback: T
): Observable<T> {
  return refresh$.pipe(
    switchMap(() => loader().pipe(catchError(() => of(fallback)))),
    shareReplay({ bufferSize: 1, refCount: true })
  )
}
