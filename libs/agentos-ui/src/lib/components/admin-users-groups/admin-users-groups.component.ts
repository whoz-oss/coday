import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { NamespaceControllerService, NamespaceListItem, NamespaceListItemRoleEnum } from '@whoz-oss/agentos-api-client'
import { UserGroupListComponent } from '../user-group-list/user-group-list.component'
import { UserListComponent } from '../user-list/user-list.component'

type AdminTab = 'users' | 'groups'

/**
 * AdminUsersGroupsComponent — combined super-admin console for users and user groups.
 *
 * Loaded at /agentos/admin/users. A segmented toggle switches between:
 * - Users: the platform user list, filterable by namespace (namespace picker in the toolbar)
 * - Groups: a namespace's user groups (namespace picker in the toolbar)
 *
 * The active tab and the Groups namespace are seeded from `?tab` / `?ns` query params so the group
 * create/edit form (which navigates away and back via `returnTo`) can restore this context.
 */
@Component({
  selector: 'agentos-admin-users-groups',
  imports: [UserListComponent, UserGroupListComponent],
  templateUrl: './admin-users-groups.component.html',
  styleUrl: './admin-users-groups.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AdminUsersGroupsComponent {
  private readonly router = inject(Router)
  private readonly route = inject(ActivatedRoute)
  private readonly destroyRef = inject(DestroyRef)
  private readonly namespaceController = inject(NamespaceControllerService)

  protected readonly activeTab = signal<AdminTab>(
    this.route.snapshot.queryParamMap.get('tab') === 'groups' ? 'groups' : 'users'
  )

  /** Namespaces the user administers (super-admins get all; others only their ADMIN namespaces). */
  protected readonly namespaces = signal<NamespaceListItem[]>([])
  /** Users tab filter; null = all namespaces. */
  protected readonly usersNamespaceFilter = signal<string | null>(null)
  /** Groups tab namespace (always a concrete namespace once loaded). */
  protected readonly groupsNamespaceId = signal<string | null>(null)

  /** Return URL handed to the group form so it comes back to this tab + namespace after save. */
  protected readonly returnTo = computed(() => {
    const namespaceId = this.groupsNamespaceId()
    return namespaceId ? `/agentos/admin/users?tab=groups&ns=${namespaceId}` : '/agentos/admin/users?tab=groups'
  })

  constructor() {
    const requestedNamespaceId = this.route.snapshot.queryParamMap.get('ns')
    this.namespaceController
      .listAllNamespace()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((namespaces) => {
        // Only namespaces the user administers: super-admins get every namespace (role SUPER-ADMIN),
        // regular users only those where their role is ADMIN (MEMBER-only namespaces are excluded).
        const adminNamespaces = namespaces.filter((ns) => ns.role !== NamespaceListItemRoleEnum.MEMBER)
        this.namespaces.set(adminNamespaces)
        const preselected =
          requestedNamespaceId && adminNamespaces.some((ns) => ns.id === requestedNamespaceId)
            ? requestedNamespaceId
            : (adminNamespaces[0]?.id ?? null)
        this.groupsNamespaceId.set(preselected)
      })
  }

  protected setTab(tab: AdminTab): void {
    this.activeTab.set(tab)
  }

  protected onUsersNamespaceChange(namespaceId: string | null): void {
    this.usersNamespaceFilter.set(namespaceId)
  }

  protected onGroupsNamespaceChange(namespaceId: string): void {
    this.groupsNamespaceId.set(namespaceId)
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }
}
