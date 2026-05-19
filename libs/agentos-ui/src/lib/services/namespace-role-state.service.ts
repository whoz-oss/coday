import { inject, Injectable } from '@angular/core'
import { toObservable } from '@angular/core/rxjs-interop'
import { NamespacePermissionEndpointsService, NamespaceUserListItemRoleEnum } from '@whoz-oss/agentos-api-client'
import { catchError, map, Observable, of, shareReplay, switchMap } from 'rxjs'
import { UserStateService } from './user-state.service'

/**
 * NamespaceRoleStateService — exposes whether the current user can administrate a given
 * namespace (super-admin OR namespace ADMIN by relation).
 *
 * Decision rule:
 *   - User.isAdmin === true  → admin of every namespace (super-admin short-circuit, no HTTP)
 *   - else fetch listNamespaceUsers(namespaceId), find the current user, check role === 'ADMIN'
 *   - 403/error → default-safe, return false
 *
 * The result is cached per namespaceId via shareReplay so concurrent subscribers (typically
 * the 3-section containers in Integrations / AI Providers / AI Models) share the same lookup.
 */
@Injectable({ providedIn: 'root' })
export class NamespaceRoleStateService {
  private readonly userState = inject(UserStateService)
  private readonly permissions = inject(NamespacePermissionEndpointsService)

  /** Reactive stream of the current user (null until loadMe completes). */
  private readonly currentUser$ = toObservable(this.userState.currentUser)

  /** Per-namespace cached observable so a refetch only happens when no subscriber is left. */
  private readonly cache = new Map<string, Observable<boolean>>()

  isAdminOfNamespace$(namespaceId: string): Observable<boolean> {
    if (!namespaceId) return of(false)
    const cached = this.cache.get(namespaceId)
    if (cached) return cached

    const obs$ = this.currentUser$.pipe(
      switchMap((user) => {
        if (!user) return of(false)
        if (user.isAdmin) return of(true)
        return this.permissions.listNamespaceUsers(namespaceId).pipe(
          map((users) => users.some((u) => u.id === user.id && u.role === NamespaceUserListItemRoleEnum.ADMIN)),
          catchError(() => of(false))
        )
      }),
      shareReplay({ bufferSize: 1, refCount: false })
    )
    this.cache.set(namespaceId, obs$)
    return obs$
  }
}
