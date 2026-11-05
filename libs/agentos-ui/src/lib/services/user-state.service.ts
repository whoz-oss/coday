import { computed, inject, Injectable, signal } from '@angular/core'
import { toObservable, toSignal } from '@angular/core/rxjs-interop'
import { User, UserControllerService } from '@whoz-oss/agentos-api-client'
import { distinctUntilChanged, from, Observable, of, switchMap, tap } from 'rxjs'
import { gravatarUrl } from './gravatar.utils'

/** Largest avatar rendered in the app, in CSS pixels. */
const AVATAR_SIZE = 40

/**
 * UserStateService — reactive state for the current authenticated user.
 *
 * Follows the two-layer pattern: this service owns state and coordinates
 * calls to UserControllerService (API layer). Components and other services
 * inject this service, never the API service directly.
 *
 * Usage:
 *   - Call loadMe() once on app init or on /me route activation.
 *   - Read currentUser() anywhere to display user info (e.g. header initials).
 *   - Call updateMe() to persist edits and update the signal.
 */
@Injectable({ providedIn: 'root' })
export class UserStateService {
  private readonly userController = inject(UserControllerService)

  readonly currentUser = signal<User | null>(null)

  /**
   * Gravatar URL of the current user, null until known or when it cannot be computed.
   * The image may still fail to load (no Gravatar for this email): `ds-avatar` then shows the initials.
   */
  readonly avatarUrl = toSignal(
    toObservable(computed(() => this.currentUser()?.email ?? null)).pipe(
      distinctUntilChanged(),
      switchMap((email) => (email ? from(gravatarUrl(email, AVATAR_SIZE)) : of(null)))
    ),
    { initialValue: null }
  )

  loadMe(): Observable<User> {
    return this.userController.getMeUser().pipe(tap((user) => this.currentUser.set(user)))
  }

  updateMe(patch: Pick<User, 'firstname' | 'lastname' | 'bio' | 'preferredLanguage'>): Observable<User> {
    const existing = this.currentUser()
    if (!existing) throw new Error('Cannot update: current user not loaded')

    if (!existing.id) throw new Error('Cannot update: current user has no id')
    const updated: User = { ...existing, ...patch }
    return this.userController.updateUser(existing.id, updated).pipe(tap((user) => this.currentUser.set(user)))
  }
}
