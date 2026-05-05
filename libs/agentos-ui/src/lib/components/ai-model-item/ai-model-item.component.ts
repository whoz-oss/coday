import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { AiModel, UserAiModel } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'
import { AiModelScope } from '../../services/ai-model-config-state.service'
import { AiProviderScope } from '../../services/ai-provider-config-state.service'

interface ScopeBadge {
  label: string
  ariaLabel: string
  variant: 'neutral' | 'info' | 'warning'
}

const SCOPE_BADGES: Readonly<Record<AiModelScope, ScopeBadge>> = Object.freeze({
  namespace: { label: 'NS', ariaLabel: 'Configuration partagée du namespace', variant: 'neutral' },
  userOnNs: { label: 'USER × NS', ariaLabel: 'Configuration utilisateur sur ce namespace', variant: 'info' },
  userGlobal: { label: 'USER GLOBAL', ariaLabel: 'Configuration utilisateur globale', variant: 'warning' },
})

/**
 * Identification of the parent provider for display alongside the model row.
 * The container resolves it via `AiModelConfigStateService.eligibleProviders$` and passes it
 * down — the item itself never queries.
 *
 * `null` means the parent provider is unknown to the user (could be a deleted record or one
 * the user lost access to). The item then renders a discreet warning per AC6 — orphan AiModel
 * stays visible (FR30 dormant override) rather than being hidden.
 */
export interface ParentProviderRef {
  name: string
  scope: AiProviderScope
}

/**
 * AiModelItemComponent — presentational row for one AI model config (story 6.6).
 *
 * Displays the model display name (alias or apiModelName), priority, and the parent provider
 * (with its own scope tag — useful when a userOnNs model points to a userOnNs provider). The
 * scope badge follows the same convention as `AiProviderItemComponent` and the integration
 * config item from story 6.5.
 *
 * Edit and delete are dispatched upward via outputs; "Override pour moi" is shown on
 * `scope === 'namespace'` only.
 */
@Component({
  selector: 'agentos-ai-model-item',
  standalone: true,
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './ai-model-item.component.html',
  styleUrl: './ai-model-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AiModelItemComponent {
  readonly model = input.required<AiModel | UserAiModel>()
  readonly scope = input.required<AiModelScope>()
  readonly readOnly = input<boolean>(false)
  /** Parent provider info resolved by the container; null means unknown / orphan. */
  readonly parentProvider = input<ParentProviderRef | null>(null)

  readonly editRequested = output<AiModel | UserAiModel>()
  readonly deleteRequested = output<AiModel | UserAiModel>()
  readonly overrideRequested = output<AiModel>()

  protected readonly pendingDelete = signal(false)

  protected readonly badge = computed<ScopeBadge>(() => SCOPE_BADGES[this.scope()])

  protected readonly canOverride = computed(() => this.scope() === 'namespace')

  protected readonly displayTitle = computed(() => this.model().alias ?? this.model().apiModelName)

  protected readonly parentLabel = computed<string>(() => {
    const provider = this.parentProvider()
    if (!provider) return 'Provider introuvable'
    const scopeTag = SCOPE_BADGES[provider.scope].label
    return `${provider.name} (${scopeTag})`
  })

  protected readonly parentIsOrphan = computed(() => !this.parentProvider())

  protected readonly menuItems = computed<KebabMenuItem[]>(() => [
    { key: 'edit', label: 'Edit model', icon: 'edit' },
    { key: 'delete', label: 'Delete model', icon: 'delete', variant: 'danger' },
  ])

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'edit':
        this.editRequested.emit(this.model())
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onOverride(): void {
    this.overrideRequested.emit(this.model() as AiModel)
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.model())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }
}
