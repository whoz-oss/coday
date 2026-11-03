import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { TableDirective } from '@whoz-oss/design-system'
import { catchError, combineLatest, of, switchMap, tap } from 'rxjs'
import { UserGroupStateService } from '../../services/user-group-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * UserGroupListComponent — table of a namespace's user groups, embedded in the admin console.
 *
 * Reactive to the [namespaceId] input: the id is driven by the namespace dropdown of the host.
 *
 * - Renders the groups in a table (name, agents, members) with a local text filter
 * - Create / edit reuse the namespace-scoped UserGroupFormComponent routes; when [returnTo]
 *   is set it is forwarded as a query param so the form returns to the embedding context
 * - Delete uses an inline two-step confirmation
 */
@Component({
  selector: 'agentos-user-group-list',
  imports: [TableDirective, TableFilterComponent, NamespaceSelectComponent],
  templateUrl: './user-group-list.component.html',
  styleUrl: './user-group-list.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserGroupListComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userGroupState = inject(UserGroupStateService)

  /** Namespace whose groups are listed. Reactive: changing it reloads the list. */
  readonly namespaceId = input.required<string>()
  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Forwarded to the create/edit form as a query param so it can navigate back here. */
  readonly returnTo = input<string | undefined>(undefined)

  readonly namespaceChange = output<string>()

  /** Bumped to force a reload after a delete. */
  private readonly refresh = signal(0)

  protected readonly isLoading = signal(true)
  protected readonly pendingDeleteId = signal<string | null>(null)

  protected readonly groups = toSignal(
    combineLatest([toObservable(this.namespaceId), toObservable(this.refresh)]).pipe(
      // catchError on the inner observable so a transient failure (e.g. switching namespace)
      // does not terminate the outer stream and freeze the list in an error state; fall back
      // to an empty list and log so the failure stays diagnosable (#1076).
      switchMap(([namespaceId]) => {
        this.isLoading.set(true)
        return this.userGroupState.listByNamespace(namespaceId).pipe(
          tap(() => this.isLoading.set(false)),
          catchError((err) => {
            console.error('[UserGroupList] Failed to load user groups', err)
            this.isLoading.set(false)
            return of([] as UserGroupSearchResult[])
          })
        )
      })
    ),
    { initialValue: [] as UserGroupSearchResult[] }
  )

  protected readonly groupFilter = createRowFilter(this.groups, (group) => group.name)

  private queryParams(): { returnTo?: string } {
    return this.returnTo() ? { returnTo: this.returnTo() } : {}
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId(), 'user-groups', 'new'], {
      queryParams: this.queryParams(),
    })
  }

  protected edit(group: UserGroupSearchResult): void {
    void this.router.navigate(['/agentos', this.namespaceId(), 'user-groups', group.userGroupId, 'edit'], {
      queryParams: this.queryParams(),
    })
  }

  protected requestDelete(group: UserGroupSearchResult): void {
    this.pendingDeleteId.set(group.userGroupId)
  }

  protected cancelDelete(): void {
    this.pendingDeleteId.set(null)
  }

  protected confirmDelete(group: UserGroupSearchResult): void {
    this.pendingDeleteId.set(null)
    this.userGroupState
      .delete(group.userGroupId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh.update((n) => n + 1))
  }

  protected onNamespaceChange(namespaceId: string | null): void {
    if (namespaceId) this.namespaceChange.emit(namespaceId)
  }
}
