import { inject, Injectable, signal } from '@angular/core'
import { User, UserControllerService } from '@whoz-oss/agentos-api-client'
import { Observable, tap } from 'rxjs'

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

  loadMe(): Observable<User> {
    return this.userController.getMe().pipe(tap((user) => this.currentUser.set(user)))
  }

  updateMe(patch: Pick<User, 'firstname' | 'lastname' | 'bio'>): Observable<User> {
    const existing = this.currentUser()
    if (!existing) throw new Error('Cannot update: current user not loaded')

    if (!existing.id) throw new Error('Cannot update: current user has no id')
    const updated: User = { ...existing, ...patch }
    return this.userController.update(existing.id, updated).pipe(tap((user) => this.currentUser.set(user)))
  }
}
