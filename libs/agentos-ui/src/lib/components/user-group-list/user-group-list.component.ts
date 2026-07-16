import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core'
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Namespace, UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { combineLatest, switchMap } from 'rxjs'
import { UserGroupStateService } from '../../services/user-group-state.service'
import { NamespaceSelectComponent } from '../namespace-select/namespace-select.component'
import { UserGroupItemComponent } from '../user-group-item/user-group-item.component'

/**
 * UserGroupListComponent — reusable list of a namespace's user groups.
 *
 * Reactive to the [namespaceId] input so it can be embedded both on the namespace-scoped page
 * (fixed id from the route) and in the admin console (id driven by a namespace dropdown).
 *
 * - Renders the groups via ds-entity-list + UserGroupItemComponent
 * - Create / edit / delete reuse the namespace-scoped UserGroupFormComponent routes; when [returnTo]
 *   is set it is forwarded as a query param so the form returns to the embedding context
 * - An optional [backLabel] renders a toolbar-start back button that emits (backRequested)
 */
@Component({
  selector: 'agentos-user-group-list',
  imports: [EntityListComponent, UserGroupItemComponent, NamespaceSelectComponent],
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
  /** When set, a "← {backLabel}" button is shown in the toolbar and emits (backRequested). */
  readonly backLabel = input<string | undefined>(undefined)
  /** When provided (admin console), a namespace `<select>` is shown in the toolbar. */
  readonly namespaces = input<Namespace[] | undefined>(undefined)
  /** Hide the list title (the tab already labels the section). */
  readonly hideTitle = input<boolean>(false)
  /** Forwarded to the create/edit form as a query param so it can navigate back here. */
  readonly returnTo = input<string | undefined>(undefined)

  readonly backRequested = output<void>()
  readonly namespaceChange = output<string>()

  /** Bumped to force a reload after a delete. */
  private readonly refresh = signal(0)

  private readonly groups = toSignal(
    combineLatest([toObservable(this.namespaceId), toObservable(this.refresh)]).pipe(
      switchMap(([namespaceId]) => this.userGroupState.listByNamespace(namespaceId))
    ),
    { initialValue: [] as UserGroupSearchResult[] }
  )

  protected readonly groupItems = computed<EntityListItem[]>(() =>
    this.groups().map((group) => ({ id: group.userGroupId, name: group.name }))
  )

  private readonly groupsById = computed(() => new Map(this.groups().map((group) => [group.userGroupId, group])))

  protected resolveGroup(id: string): UserGroupSearchResult | null {
    return this.groupsById().get(id) ?? null
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId(), 'user-groups', 'new'], {
      queryParams: this.returnTo() ? { returnTo: this.returnTo() } : {},
    })
  }

  protected deleteGroup(group: UserGroupSearchResult): void {
    this.userGroupState
      .delete(group.userGroupId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh.update((n) => n + 1))
  }

  protected onBack(): void {
    this.backRequested.emit()
  }

  protected onNamespaceChange(namespaceId: string | null): void {
    if (namespaceId) this.namespaceChange.emit(namespaceId)
  }
}
