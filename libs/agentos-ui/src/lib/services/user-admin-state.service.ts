import { inject, Injectable, signal } from '@angular/core'
import { User, UserControllerService } from '@whoz-oss/agentos-api-client'
import { Observable, tap } from 'rxjs'

/**
 * UserAdminStateService — reactive state for the admin user management view.
 *
 * Follows the two-layer pattern: owns state and coordinates calls to
 * UserControllerService (API layer). Intentionally separate from UserStateService
 * which is scoped to the authenticated current user.
 *
 * Usage:
 *   - Call loadAll() to populate the users signal.
 *   - Call deleteUser() to remove a user and refresh the list.
 *   - Call createUser() / updateUser() to persist changes and refresh.
 */
@Injectable({ providedIn: 'root' })
export class UserAdminStateService {
  private readonly userController = inject(UserControllerService)

  readonly users = signal<User[]>([])
  readonly isLoading = signal(false)

  loadAll(): Observable<User[]> {
    this.isLoading.set(true)
    return this.userController.listAllUser().pipe(
      tap({
        next: (users) => {
          this.users.set(users)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
    )
  }

  createUser(user: Omit<User, 'id'>): Observable<User> {
    return this.userController.createUser(user as User).pipe(
      tap(() => {
        this.loadAll().subscribe()
      })
    )
  }

  updateUser(id: string, patch: Partial<Omit<User, 'id'>>): Observable<User> {
    const existing = this.users().find((u) => u.id === id)
    if (!existing) throw new Error(`Cannot update: user ${id} not found in state`)
    const updated: User = { ...existing, ...patch }
    return this.userController.updateUser(id, updated).pipe(
      tap((saved) => {
        this.users.update((prev) => prev.map((u) => (u.id === saved.id ? saved : u)))
      })
    )
  }

  deleteUser(id: string): Observable<unknown> {
    return this.userController.deleteUser(id).pipe(
      tap(() => {
        this.users.update((prev) => prev.filter((u) => u.id !== id))
      })
    )
  }
}
