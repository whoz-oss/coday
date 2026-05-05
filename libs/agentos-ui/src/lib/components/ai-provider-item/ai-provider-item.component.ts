import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { AiProvider, UserAiProvider } from '@whoz-oss/agentos-api-client'
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
 * On `scope === 'namespace'` items, an "Override for me" button is shown so a user can
 * fork an NS provider into a personal override (cross-link to the form with `?template=`).
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
  readonly config = input.required<AiProvider | UserAiProvider>()
  readonly scope = input.required<AiProviderScope>()
  readonly readOnly = input<boolean>(false)

  readonly editRequested = output<AiProvider | UserAiProvider>()
  readonly deleteRequested = output<AiProvider | UserAiProvider>()
  readonly overrideRequested = output<AiProvider>()

  protected readonly pendingDelete = signal(false)

  protected readonly badge = computed<ScopeBadge>(() => SCOPE_BADGES[this.scope()])

  protected readonly canOverride = computed(() => this.scope() === 'namespace')

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

  protected onOverride(): void {
    // Only NS items expose this action — see canOverride().
    this.overrideRequested.emit(this.config() as AiProvider)
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
