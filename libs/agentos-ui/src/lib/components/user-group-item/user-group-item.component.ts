import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { UserGroupSearchResult } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * UserGroupItemComponent — presentational card for a single user group.
 *
 * Shows the group name plus deployed-agent and member counts. Edit navigates to the dedicated
 * edit route; delete uses a two-step inline confirmation before emitting upward to the list.
 */
@Component({
  selector: 'agentos-user-group-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './user-group-item.component.html',
  styleUrl: './user-group-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserGroupItemComponent {
  private readonly router = inject(Router)

  readonly userGroup = input.required<UserGroupSearchResult>()
  readonly namespaceId = input.required<string>()
  /** Forwarded to the edit form as a query param so it can navigate back to the embedding context. */
  readonly returnTo = input<string | undefined>(undefined)

  readonly deleteRequested = output<UserGroupSearchResult>()

  protected readonly pendingDelete = signal(false)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit group', icon: 'edit' },
    { key: 'delete', label: 'Delete group', icon: 'delete', variant: 'danger' },
  ]

  protected get agentCount(): number {
    return this.userGroup().agentIds.length
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.router.navigate(['/agentos', this.namespaceId(), 'user-groups', this.userGroup().userGroupId, 'edit'], {
          queryParams: this.returnTo() ? { returnTo: this.returnTo() } : {},
        })
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.userGroup())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
