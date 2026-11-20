import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core'
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, User } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, TableDirective } from '@whoz-oss/design-system'
import { debounceTime, skip } from 'rxjs'
import { UserAdminStateService } from '../../services/user-admin-state.service'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { ListFooterComponent } from '../list-footer/list-footer.component'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * UserListComponent — admin table of all users.
 *
 * Embedded in the admin Users & Groups console (/agentos/admin/users). Responsibilities:
 * - Load users one server page at a time ("Load more" appends) and display them in a table (name, email)
 * - Filter server-side: the text filter (debounced) and the namespace picker both reset the list to page 1
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
  imports: [
    ButtonDirective,
    TableDirective,
    TableFilterComponent,
    NamespaceSelectComponent,
    RowDeleteActionComponent,
    ListFooterComponent,
  ],
  templateUrl: './user-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserListComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly state = inject(UserAdminStateService)

  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Namespace to filter by; null = all namespaces. */
  readonly selectedNamespaceId = input<string | null>(null)

  readonly namespaceFilterChange = output<string | null>()

  protected readonly query = signal('')
  protected readonly deletion = createRowDeletionState()

  constructor() {
    // The namespace input's initial emission is the one initial load.
    toObservable(this.selectedNamespaceId)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.reload())
    toObservable(this.query)
      .pipe(skip(1), debounceTime(300), takeUntilDestroyed())
      .subscribe(() => this.reload())
  }

  private reload(): void {
    this.state.reset(this.selectedNamespaceId(), this.query())
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

  protected confirmDelete(user: User): void {
    if (!user.id) return
    this.deletion.execute(user.id, {
      request$: this.state.deleteUser(user.id).pipe(takeUntilDestroyed(this.destroyRef)),
      errorMessage: `Deleting "${this.displayName(user)}" failed. Try again.`,
    })
  }
}
