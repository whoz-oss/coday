import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { AuthSetting } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'
import { AuthSettingScope } from '../../services/auth-setting-config-state.service'

interface ScopeBadge {
  label: string
  ariaLabel: string
  variant: 'neutral' | 'info' | 'warning'
}

/**
 * Badge labels are kept short (NS / USER × NS / USER GLOBAL) so the row stays scannable.
 * The `ariaLabel` carries the human-readable French translation for screen readers — the
 * `×` glyph in the visible label would otherwise be announced as "multiplication sign".
 *
 * Same convention as AiProviderItemComponent (story 6.6).
 */
const SCOPE_BADGES: Readonly<Record<AuthSettingScope, ScopeBadge>> = Object.freeze({
  namespace: { label: 'NS', ariaLabel: 'Configuration partagée du namespace', variant: 'neutral' },
  userOnNs: { label: 'USER × NS', ariaLabel: 'Configuration utilisateur sur ce namespace', variant: 'info' },
  userGlobal: { label: 'USER GLOBAL', ariaLabel: 'Configuration utilisateur globale', variant: 'warning' },
})

/**
 * AuthSettingItemComponent — presentational row for one auth setting (Issue #1095, Phase 7).
 *
 * Displays the setting name, authType, and a scope badge (NS / USER × NS / USER GLOBAL).
 * Edit and delete are dispatched upward via outputs — the container picks the right
 * controller based on `scope`.
 *
 * A "Duplicate" button is exposed on every card regardless of source scope. Clicking it
 * navigates the user to a new-form pre-filled with this setting's data; the container
 * decides the default destination scope and the user can pick any destination via the
 * radio in the form. Credential data is intentionally NOT carried into the duplicate
 * (NFR-SEC-1: credentials never migrate across resources).
 *
 * Read-only mode hides edit/delete (used for users without write permission on the NS).
 *
 * The `data` values are never displayed on this row — credentials are only visible in the
 * form (and even there, masked unless the user types a new value). NFR-SEC-1.
 */
@Component({
  selector: 'agentos-auth-setting-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './auth-setting-item.component.html',
  styleUrl: './auth-setting-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthSettingItemComponent {
  readonly config = input.required<AuthSetting>()
  readonly scope = input.required<AuthSettingScope>()
  readonly readOnly = input<boolean>(false)
  /** Whether to display the scope badge. Set to false when the scope is already
   * conveyed by the surrounding section (e.g. platform-level admin page). */
  readonly showBadge = input<boolean>(true)

  readonly editRequested = output<AuthSetting>()
  readonly deleteRequested = output<AuthSetting>()
  readonly duplicateRequested = output<AuthSetting>()

  protected readonly pendingDelete = signal(false)

  protected readonly badge = computed<ScopeBadge>(() => SCOPE_BADGES[this.scope()])

  protected readonly menuItems = computed<KebabMenuItem[]>(() => [
    { key: 'edit', label: 'Edit auth setting', icon: 'edit' },
    { key: 'delete', label: 'Delete auth setting', icon: 'delete', variant: 'danger' },
  ])

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.editRequested.emit(this.config())
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDuplicate(): void {
    this.duplicateRequested.emit(this.config())
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
