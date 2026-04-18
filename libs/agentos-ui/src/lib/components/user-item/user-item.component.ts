import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { User } from '@whoz-oss/agentos-api-client'
import { KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * UserItemComponent — presentational component for a single user card.
 *
 * Displays the user's display name (firstname + lastname or email fallback)
 * and email. Actions (edit, delete) are grouped in a ds-kebab-menu and
 * emitted upward — no direct service injection.
 *
 * Delete uses a native confirm() dialog to prevent accidental deletions.
 */
@Component({
  selector: 'agentos-user-item',
  standalone: true,
  imports: [KebabMenuComponent],
  templateUrl: './user-item.component.html',
  styleUrl: './user-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class UserItemComponent {
  @Input({ required: true }) user!: User

  @Output() editRequested = new EventEmitter<User>()
  @Output() deleteRequested = new EventEmitter<User>()

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'edit', label: 'Edit user', icon: 'edit' },
    { key: 'delete', label: 'Delete user', icon: 'delete', variant: 'danger' },
  ]

  protected get displayName(): string {
    const { firstname, lastname, email } = this.user
    const full = [firstname, lastname].filter(Boolean).join(' ')
    return full || email || '—'
  }

  protected get subtitle(): string {
    return this.user.email ?? this.user.externalId ?? ''
  }

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.editRequested.emit(this.user)
        break
      case 'delete':
        if (confirm(`Delete user "${this.displayName}"?`)) {
          this.deleteRequested.emit(this.user)
        }
        break
    }
  }
}
