import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { AiProvider } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'
import { AiProviderScope } from '../../services/ai-provider-config-state.service'

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
 * Same convention as story 6.5 IntegrationConfigItemComponent.
 */
const SCOPE_BADGES: Readonly<Record<AiProviderScope, ScopeBadge>> = Object.freeze({
  namespace: { label: 'NS', ariaLabel: 'Configuration partagée du namespace', variant: 'neutral' },
  userOnNs: { label: 'USER × NS', ariaLabel: 'Configuration utilisateur sur ce namespace', variant: 'info' },
  userGlobal: { label: 'USER GLOBAL', ariaLabel: 'Configuration utilisateur globale', variant: 'warning' },
})

/**
 * AiProviderItemComponent — presentational row for one AI provider config (story 6.6).
 *
 * Displays the provider name, API type, baseUrl, plus a scope badge (NS / USER × NS / USER
 * GLOBAL). Edit and delete are dispatched upward via outputs — the container picks the right
 * controller based on `scope`.
 *
 * A "Duplicate" button is exposed on every card regardless of source scope. Clicking it
 * navigates the user to a new-form pre-filled with this provider's data; the container
 * decides the default destination scope (currently: same as source) and the user can pick
 * any destination via the radio in the form. The apiKey is intentionally NOT carried into
 * the duplicate (NFR-SEC-1: credentials never migrate across resources).
 *
 * Read-only mode hides edit/delete (used for users without write permission on the NS).
 *
 * The `apiKey` is never displayed on this row — credentials are only visible in the form
 * (and even there, masked unless the user types a new value). NFR-SEC-1.
 */
@Component({
  selector: 'agentos-ai-provider-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-provider-item.component.html',
  styleUrl: './ai-provider-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiProviderItemComponent {
  readonly config = input.required<AiProvider>()
  readonly scope = input.required<AiProviderScope>()
  readonly readOnly = input<boolean>(false)
  /** Whether to display the scope badge. Set to false when the scope is already
   * conveyed by the surrounding section (e.g. platform-level admin page). */
  readonly showBadge = input<boolean>(true)

  readonly editRequested = output<AiProvider>()
  readonly deleteRequested = output<AiProvider>()
  readonly duplicateRequested = output<AiProvider>()

  protected readonly pendingDelete = signal(false)

  protected readonly badge = computed<ScopeBadge>(() => SCOPE_BADGES[this.scope()])

  protected readonly menuItems = computed<KebabMenuItem[]>(() => [
    { key: 'edit', label: 'Edit provider', icon: 'edit' },
    { key: 'delete', label: 'Delete provider', icon: 'delete', variant: 'danger' },
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
