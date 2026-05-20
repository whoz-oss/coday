import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { IntegrationConfig } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { IntegrationScope } from '../../services/integration-config-state.service'

interface ScopeBadge {
  label: string
  ariaLabel: string
  variant: 'neutral' | 'info' | 'warning'
}

/**
 * Badge labels are kept short (NS / USER × NS / USER GLOBAL) so the row stays scannable.
 * The `ariaLabel` carries the human-readable French translation for screen readers — the
 * `×` glyph in the visible label would otherwise be announced as "multiplication sign".
 */
const SCOPE_BADGES: Readonly<Record<IntegrationScope, ScopeBadge>> = Object.freeze({
  namespace: { label: 'NS', ariaLabel: 'Configuration partagée du namespace', variant: 'neutral' },
  userOnNs: { label: 'USER × NS', ariaLabel: 'Configuration utilisateur sur ce namespace', variant: 'info' },
  userGlobal: { label: 'USER GLOBAL', ariaLabel: 'Configuration utilisateur globale', variant: 'warning' },
})

/**
 * IntegrationConfigItemComponent — presentational row for one integration config.
 *
 * Renders the name, integration type, a scope badge (NS / USER × NS / USER GLOBAL) and
 * action buttons. Edit and delete are dispatched upward — the container picks the right
 * controller based on `scope`.
 *
 * A "Duplicate" button is exposed on every card regardless of source scope. Clicking it
 * navigates the user to a new-form pre-filled with this config's data; the container
 * decides the default destination scope (currently: same as source) and the user can pick
 * any destination via the radio in the form.
 *
 * Read-only mode hides edit/delete (used for users without write permission on the NS).
 */
@Component({
  selector: 'agentos-integration-config-item',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './integration-config-item.component.html',
  styleUrl: './integration-config-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntegrationConfigItemComponent {
  readonly config = input.required<IntegrationConfig>()
  readonly scope = input.required<IntegrationScope>()
  readonly readOnly = input<boolean>(false)

  readonly editRequested = output<IntegrationConfig>()
  readonly deleteRequested = output<IntegrationConfig>()
  readonly duplicateRequested = output<IntegrationConfig>()

  protected readonly pendingDelete = signal(false)

  protected readonly badge = computed<ScopeBadge>(() => SCOPE_BADGES[this.scope()])

  protected onEdit(): void {
    this.editRequested.emit(this.config())
  }

  protected onDuplicate(): void {
    this.duplicateRequested.emit(this.config())
  }

  protected onDeleteArmed(): void {
    this.pendingDelete.set(true)
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.config())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
