import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output } from '@angular/core'
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, User } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { debounceTime, Subject, tap } from 'rxjs'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { ListFooterComponent } from '../list-footer/list-footer.component'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { UserItemComponent } from '../user-item/user-item.component'

/**
 * UserListComponent — admin view for listing and managing all users.
 *
 * Loaded at /agentos/admin/users. Responsibilities:
 * - Load users one server page at a time ("Load more" appends) and display them via ds-entity-list
 * - Navigate to the create form (/agentos/admin/users/new)
 * - Navigate to the edit form (/agentos/admin/users/:userId/edit)
 * - Delete with confirmation (delegated to UserItemComponent)
 *
 * When embedded in the admin Users & Groups console, a [namespaces] list adds a namespace picker to
 * the toolbar and the list is filtered server-side to that namespace's members; the search box is server-side too; the back button and title can be
 * hidden so the host owns the chrome.
 *
 * Create and edit logic live in UserFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-user-list',
  imports: [EntityListComponent, UserItemComponent, IconButtonComponent, NamespaceSelectComponent, ListFooterComponent],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly state = inject(UserAdminStateService)

  /** Toolbar back button (hidden when the host owns navigation). */
  readonly showBackButton = input<boolean>(true)
  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Namespace to filter by; null = all namespaces. */
  readonly selectedNamespaceId = input<string | null>(null)
  /** Hide the list title (the tab already labels the section). */
  readonly hideTitle = input<boolean>(false)

  readonly namespaceFilterChange = output<string | null>()

  protected readonly search$ = new Subject<string>()
  private query = ''

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly userItems = computed<EntityListItem[]>(() =>
    this.state.users().map(
      (u): EntityListItem => ({
        id: u.id ?? '',
        name: [u.firstname, u.lastname].filter(Boolean).join(' ') || u.email || '—',
        description: u.email,
      })
    )
  )

  protected readonly emptyMessage = computed(() =>
    this.state.loadError() ? '' : this.state.isFiltered() ? 'No users match the current filter.' : 'No users found.'
  )

  /** Full user objects indexed by id — used to resolve itemTemplate events. */
  private get usersById(): Map<string, User> {
    return new Map(this.state.users().map((u) => [u.id ?? '', u]))
  }

  constructor() {
    // The namespace input's initial emission is the one initial load.
    toObservable(this.selectedNamespaceId)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.reload())
    this.search$
      .pipe(
        tap((query) => (this.query = query)),
        debounceTime(300),
        takeUntilDestroyed()
      )
      .subscribe(() => this.reload())
  }

  private reload(): void {
    this.state.reset(this.selectedNamespaceId(), this.query)
  }

  protected onNamespaceChange(namespaceId: string | null): void {
    this.namespaceFilterChange.emit(namespaceId)
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected navigateToCreate(): void {
    this.router.navigate(['/agentos/admin/users/new'])
  }

  protected navigateToEdit(user: User): void {
    this.router.navigate(['/agentos/admin/users', user.id ?? '', 'edit'])
  }

  protected deleteUser(user: User): void {
    this.state
      .deleteUser(user.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }

  protected resolveUser(id: string): User | null {
    return this.usersById.get(id) ?? null
  }
}
