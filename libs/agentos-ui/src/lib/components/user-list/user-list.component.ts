import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  OnInit,
  output,
  signal,
} from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { MemberItem, Namespace, NamespaceMembershipControllerService, User } from '@whoz-oss/agentos-api-client'
import { TableDirective } from '@whoz-oss/design-system'
import { catchError, map, of, switchMap } from 'rxjs'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * UserListComponent — admin table of all users.
 *
 * Embedded in the admin Users & Groups console (/agentos/admin/users). Responsibilities:
 * - Load and display all users in a table (name, email) with a local text filter
 * - Navigate to the create form (/agentos/admin/users/new)
 * - Navigate to the edit form (/agentos/admin/users/:userId/edit)
 * - Delete with an inline two-step confirmation
 *
 * A [namespaces] list adds a namespace picker to the toolbar and the list is filtered to that
 * namespace's members. The host owns the page chrome (title, tabs).
 *
 * Create and edit logic live in UserFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-user-list',
  imports: [TableDirective, TableFilterComponent, NamespaceSelectComponent],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userAdminState = inject(UserAdminStateService)
  private readonly namespaceMembership = inject(NamespaceMembershipControllerService)

  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Namespace to filter by; null = all namespaces. */
  readonly selectedNamespaceId = input<string | null>(null)

  readonly namespaceFilterChange = output<string | null>()

  protected readonly isLoading = this.userAdminState.isLoading
  protected readonly pendingDeleteId = signal<string | null>(null)

  /** Ids of the users belonging to the selected namespace, or null when unfiltered. */
  private readonly namespaceUserIds = toSignal(
    toObservable(this.selectedNamespaceId).pipe(
      switchMap((namespaceId) =>
        namespaceId
          ? this.namespaceMembership.getMembersNamespaceMembership(namespaceId).pipe(
              map((members: MemberItem[]) => new Set(members.map((m) => m.id))),
              // catchError on the inner observable so a failed load does not terminate the
              // outer stream (which would freeze the signal in an error state for the session);
              // fall back to unfiltered and log so the failure stays diagnosable (#1076).
              catchError((err) => {
                console.error('[UserList] Failed to load namespace users', err)
                return of(null)
              })
            )
          : of(null)
      )
    ),
    { initialValue: null as Set<string> | null }
  )

  /** Users of the selected namespace (or all users when unfiltered). */
  protected readonly users = computed<User[]>(() => {
    const filterIds = this.namespaceUserIds()
    return this.userAdminState.users().filter((u) => filterIds === null || filterIds.has(u.id ?? ''))
  })

  protected readonly rowFilter = createRowFilter(this.users, (user) =>
    [this.displayName(user), user.email, user.externalId].filter(Boolean).join(' ')
  )

  ngOnInit(): void {
    this.userAdminState.loadAll().pipe(takeUntilDestroyed(this.destroyRef)).subscribe()
  }

  protected displayName(user: User): string {
    return [user.firstname, user.lastname].filter(Boolean).join(' ') || user.email || '—'
  }

  protected onNamespaceChange(namespaceId: string | null): void {
    this.namespaceFilterChange.emit(namespaceId)
  }

  protected navigateToCreate(): void {
    void this.router.navigate(['/agentos/admin/users/new'])
  }

  protected navigateToEdit(user: User): void {
    void this.router.navigate(['/agentos/admin/users', user.id ?? '', 'edit'])
  }

  protected requestDelete(user: User): void {
    this.pendingDeleteId.set(user.id ?? null)
  }

  protected cancelDelete(): void {
    this.pendingDeleteId.set(null)
  }

  protected confirmDelete(user: User): void {
    this.pendingDeleteId.set(null)
    this.userAdminState
      .deleteUser(user.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe()
  }
}
