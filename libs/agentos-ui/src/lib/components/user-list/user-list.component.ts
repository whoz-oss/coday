import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, OnInit, output } from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, NamespacePermissionEndpointsService, User } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { map, of, switchMap } from 'rxjs'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { UserItemComponent } from '../user-item/user-item.component'

/**
 * UserListComponent — admin view for listing and managing all users.
 *
 * Loaded at /agentos/admin/users. Responsibilities:
 * - Load and display all users via ds-entity-list
 * - Navigate to the create form (/agentos/admin/users/new)
 * - Navigate to the edit form (/agentos/admin/users/:userId/edit)
 * - Delete with confirmation (delegated to UserItemComponent)
 *
 * When embedded in the admin Users & Groups console, a [namespaces] list adds a namespace picker to
 * the toolbar and the list is filtered to that namespace's members; the back button and title can be
 * hidden so the host owns the chrome.
 *
 * Create and edit logic live in UserFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-user-list',
  imports: [EntityListComponent, UserItemComponent, IconButtonComponent, NamespaceSelectComponent],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userAdminState = inject(UserAdminStateService)
  private readonly namespacePermissions = inject(NamespacePermissionEndpointsService)

  /** Toolbar back button (hidden when the host owns navigation). */
  readonly showBackButton = input<boolean>(true)
  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Namespace to filter by; null = all namespaces. */
  readonly selectedNamespaceId = input<string | null>(null)
  /** Hide the list title (the tab already labels the section). */
  readonly hideTitle = input<boolean>(false)

  readonly namespaceFilterChange = output<string | null>()

  protected readonly isLoading = this.userAdminState.isLoading

  /** Ids of the users belonging to the selected namespace, or null when unfiltered. */
  private readonly namespaceUserIds = toSignal(
    toObservable(this.selectedNamespaceId).pipe(
      switchMap((namespaceId) =>
        namespaceId
          ? this.namespacePermissions
              .listNamespaceUsers(namespaceId)
              .pipe(map((users) => new Set(users.map((user) => user.id))))
          : of(null)
      )
    ),
    { initialValue: null as Set<string> | null }
  )

  /** Mapped to EntityListItem[] for ds-entity-list, filtered to the selected namespace when set. */
  protected readonly userItems = computed<EntityListItem[]>(() => {
    const filterIds = this.namespaceUserIds()
    return this.userAdminState
      .users()
      .filter((u) => filterIds === null || filterIds.has(u.id ?? ''))
      .map(
        (u): EntityListItem => ({
          id: u.id ?? '',
          name: [u.firstname, u.lastname].filter(Boolean).join(' ') || u.email || '—',
          description: u.email,
        })
      )
  })

  /** Full user objects indexed by id — used to resolve itemTemplate events. */
  private get usersById(): Map<string, User> {
    return new Map(this.userAdminState.users().map((u) => [u.id ?? '', u]))
  }

  ngOnInit(): void {
    this.userAdminState.loadAll().pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
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
    this.userAdminState
      .deleteUser(user.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }

  protected resolveUser(id: string): User | null {
    return this.usersById.get(id) ?? null
  }
}
