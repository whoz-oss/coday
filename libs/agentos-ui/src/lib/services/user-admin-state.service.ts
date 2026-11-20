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
 * The list is cursor-paged and filtered server-side:
 *   - reset() sets the filters and loads page 1, which replaces the rows (the previous ones stay
 *     displayed until it arrives); loadMore() appends the next page (or retries a failed one).
 *   - Every response is checked against a request sequence number: a response of a superseded
 *     request (a reset happened meanwhile) is dropped.
 * The single-user edit flow (getUser / updateUser) never touches the list state.
 */
@Injectable({ providedIn: 'root' })
export class UserAdminStateService {
  private readonly userController = inject(UserControllerService)

  readonly users = signal<User[]>([])
  readonly totalCount = signal(0)
  readonly nextCursor = signal<string | null>(null)
  /** Page 1 is loading (skeleton). */
  readonly isLoading = signal(false)
  readonly isLoadingMore = signal(false)
  readonly loadError = signal(false)
  /** A namespace or text filter is applied to the list. */
  readonly isFiltered = signal(false)

  private namespaceId: string | null = null
  private query = ''
  private requestSeq = 0

  /** Loads page 1 for the given filters (blank query = no filter); no further page can be requested meanwhile. */
  reset(namespaceId: string | null, query: string): void {
    this.namespaceId = namespaceId
    this.query = query.trim()
    this.isFiltered.set(namespaceId !== null || this.query !== '')
    this.nextCursor.set(null)
    this.fetch(null)
  }

  /** Loads the next page; with no cursor and a pending error, retries page 1. No-op while loading. */
  loadMore(): void {
    if (this.isLoading() || this.isLoadingMore()) return
    const next = this.nextCursor()
    if (next === null && !this.loadError()) return
    this.fetch(next)
  }

  getUser(id: string): Observable<User> {
    return this.userController.getByIdUser(id)
  }

  createUser(user: Omit<User, 'id'>): Observable<User> {
    return this.userController.createUser(user as User)
  }

  updateUser(id: string, user: User): Observable<User> {
    return this.userController.updateUser(id, user)
  }

  deleteUser(id: string): Observable<unknown> {
    return this.userController.deleteUser(id).pipe(
      tap(() => {
        this.users.update((prev) => prev.filter((u) => u.id !== id))
        this.totalCount.update((total) => Math.max(0, total - 1))
      })
    )
  }

  private fetch(next: string | null): void {
    const seq = ++this.requestSeq
    this.isLoading.set(next === null)
    this.isLoadingMore.set(next !== null)
    this.loadError.set(false)
    const filters = { namespaceId: this.namespaceId ?? undefined, query: this.query || undefined }
    this.userController.listUser({ filters, next: next ?? undefined }).subscribe({
      next: (page) => {
        if (seq !== this.requestSeq) return
        this.users.update((prev) => (next === null ? page.data : [...prev, ...page.data]))
        this.totalCount.set(page.metadata.totalCount)
        this.nextCursor.set(page.metadata.next ?? null)
        this.endLoading()
      },
      error: (err) => {
        if (seq !== this.requestSeq) return
        console.error('[UserAdminState] Failed to load users', err)
        if (next === null) {
          this.users.set([])
          this.totalCount.set(0)
        }
        this.loadError.set(true)
        this.endLoading()
      },
    })
  }

  private endLoading(): void {
    this.isLoading.set(false)
    this.isLoadingMore.set(false)
  }
}
