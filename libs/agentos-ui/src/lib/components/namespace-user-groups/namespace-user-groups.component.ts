import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { ActivatedRoute, Router } from '@angular/router'
import { UserGroupListComponent } from '../user-group-list/user-group-list.component'

/**
 * NamespaceUserGroupsComponent — namespace-scoped page for a namespace's user groups.
 *
 * Loaded at /:namespaceId/user-groups (from the namespace ⋮ menu). A thin wrapper around the
 * reusable UserGroupListComponent: it supplies the namespaceId from the route and a back button to
 * the namespace list. The admin console embeds the same list component with a namespace dropdown.
 */
@Component({
  selector: 'agentos-namespace-user-groups',
  imports: [UserGroupListComponent],
  templateUrl: './namespace-user-groups.component.html',
  styleUrl: './namespace-user-groups.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceUserGroupsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }
}
