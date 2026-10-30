import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective } from '@whoz-oss/design-system'
import { catchError, Observable, of, tap } from 'rxjs'
import { UserGroupStateService } from '../../services/user-group-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/** Namespace-scoped user-group settings page. */
@Component({
  selector: 'agentos-namespace-user-groups',
  imports: [SectionHeaderComponent, TableDirective, TableFilterComponent],
  templateUrl: './namespace-user-groups.component.html',
  styleUrl: './namespace-user-groups.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceUserGroupsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly userGroupState = inject(UserGroupStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string
  protected readonly isLoading = signal(true)
  protected readonly pendingDeleteId = signal<string | null>(null)
  protected readonly groups = signal<UserGroupSearchResult[]>([])
  protected readonly groupFilter = createRowFilter(this.groups, (group) => group.name)

  constructor() {
    this.refresh()
  }

  private loadGroups(): Observable<UserGroupSearchResult[]> {
    this.isLoading.set(true)
    return this.userGroupState.listByNamespace(this.namespaceId).pipe(
      tap(() => this.isLoading.set(false)),
      catchError((error) => {
        console.error('[NamespaceUserGroups] Failed to load user groups', error)
        this.isLoading.set(false)
        return of([] as UserGroupSearchResult[])
      })
    )
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'user-groups', 'new'])
  }

  protected edit(group: UserGroupSearchResult): void {
    this.router.navigate(['/agentos', this.namespaceId, 'user-groups', group.userGroupId, 'edit'])
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
      .subscribe(() => this.refresh())
  }

  private refresh(): void {
    this.loadGroups()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((groups) => this.groups.set(groups))
  }
}
