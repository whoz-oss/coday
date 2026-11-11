import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { ProviderId, SetupProviderInfo } from '../setup.models'
import { SetupStateService } from '../setup-state.service'

const FALLBACK_PROVIDERS: SetupProviderInfo[] = [
  {
    id: 'anthropic',
    label: 'Anthropic',
    description: 'Claude models, directly from Anthropic.',
    keyUrl: 'https://console.anthropic.com/settings/keys',
  },
  {
    id: 'openai',
    label: 'OpenAI',
    description: 'GPT models, directly from OpenAI.',
    keyUrl: 'https://platform.openai.com/api-keys',
  },
  {
    id: 'requesty',
    label: 'Requesty',
    description:
      'Requires Requesty policies named claude-fable, claude-opus, claude-sonnet and claude-haiku configured in your account.',
    keyUrl: 'https://app.requesty.ai/api-keys',
  },
]

/**
 * Step 2: pick one or more providers, paste each key, rank them (drag a card or use the arrows).
 * At least one is required. Rank 1 wins aliases shared by providers; default currently exists only in Requesty.
 */
@Component({
  selector: 'app-providers-step',
  templateUrl: './providers-step.component.html',
  styleUrls: ['./step.scss', './providers-step.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProvidersStepComponent {
  protected readonly state = inject(SetupStateService)
  protected readonly dragging = signal<ProviderId | null>(null)
  protected readonly dragOver = signal<ProviderId | null>(null)

  protected readonly info = computed(() => this.state.status()?.providers ?? FALLBACK_PROVIDERS)
  protected readonly cards = computed(() =>
    this.state.providers().map((draft) => ({
      draft,
      info:
        this.info().find((i) => i.id === draft.id) ??
        (FALLBACK_PROVIDERS.find((i) => i.id === draft.id) as SetupProviderInfo),
      rank: this.state.rankOf(draft.id),
    }))
  )
  protected readonly enabledCount = computed(() => this.state.enabledProviders().length)

  protected onKey(id: ProviderId, event: Event): void {
    this.state.setApiKey(id, (event.target as HTMLInputElement).value)
  }

  protected keyTooShort(id: ProviderId): boolean {
    const draft = this.state.providers().find((p) => p.id === id)
    return !!draft && draft.enabled && draft.apiKey.length > 0 && draft.apiKey.trim().length < 8
  }

  // --- drag and drop (enabled cards only) -------------------------------------------------------

  protected onDragStart(id: ProviderId, event: DragEvent): void {
    this.dragging.set(id)
    event.dataTransfer?.setData('text/plain', id)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  protected onDragOver(id: ProviderId, event: DragEvent): void {
    if (!this.dragging() || this.state.rankOf(id) === null) return
    event.preventDefault()
    this.dragOver.set(id)
  }

  protected onDrop(id: ProviderId, event: DragEvent): void {
    event.preventDefault()
    const source = this.dragging()
    if (source) this.state.moveProviderTo(source, id)
    this.onDragEnd()
  }

  protected onDragEnd(): void {
    this.dragging.set(null)
    this.dragOver.set(null)
  }

  protected onCardKeydown(id: ProviderId, event: KeyboardEvent): void {
    // Alt + arrows reorder from the keyboard, anywhere in the card.
    if (!event.altKey) return
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      this.state.moveProvider(id, -1)
    } else if (event.key === 'ArrowDown') {
      event.preventDefault()
      this.state.moveProvider(id, 1)
    }
  }
}
