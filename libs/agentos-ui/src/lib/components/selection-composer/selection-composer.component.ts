import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core'
import { AgentConfig } from '@whoz-oss/agentos-api-client'
import { BlueprintDirective, IconButtonComponent } from '@whoz-oss/design-system'
import { USER_PREFERENCES_PORT } from '../../services/user-preferences.service'
import { AgentAutocompleteComponent } from '../agent-autocomplete/agent-autocomplete.component'
import { ComposerAutocompleteService } from '../composer-autocomplete/composer-autocomplete.service'
import { PromptAutocompleteComponent } from '../prompt-autocomplete/prompt-autocomplete.component'

/** A word starting with @ at the end of the text: the agent mention being typed, as in the chat composer. */
const AGENT_MENTION = /@\w*$/

/**
 * SelectionComposerComponent — the compact composer shown next to a selected passage of an exchange document.
 *
 * It looks and behaves like the composer of the case chat: an auto-growing textarea and a send button, `Enter` sends
 * (as the user chose in the preferences), `Escape` closes it, and an agent is picked by typing `@` (the same
 * suggestions as in the chat, through the same component-scoped `ComposerAutocompleteService`). There is no agent
 * select: the mention in the text is what routes the message.
 *
 * Presentational: it emits the instruction (`sent`) or its closing (`closed`) and knows nothing about the document.
 */
@Component({
  selector: 'agentos-selection-composer',
  standalone: true,
  imports: [AgentAutocompleteComponent, IconButtonComponent, BlueprintDirective],
  providers: [ComposerAutocompleteService],
  templateUrl: './selection-composer.component.html',
  styleUrl: './selection-composer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelectionComposerComponent {
  /** Namespace whose agents the `@` suggestions list. */
  readonly namespaceId = input<string>('')
  /** Text the composer opens with ("Ask again"): it then takes the focus. */
  readonly initialValue = input<string>('')

  readonly sent = output<string>()
  readonly closed = output<void>()

  protected readonly autocomplete = inject(ComposerAutocompleteService)
  private readonly preferences = inject(USER_PREFERENCES_PORT)
  private readonly injector = inject(Injector)
  private readonly textarea = viewChild<ElementRef<HTMLTextAreaElement>>('textarea')
  private readonly agentRef = viewChild(AgentAutocompleteComponent)
  /** The composer offers agents only: there is never a prompt list to navigate. */
  private readonly promptRef = signal<PromptAutocompleteComponent | undefined>(undefined)

  protected readonly value = signal('')

  constructor() {
    effect(() => {
      const namespaceId = this.namespaceId()
      untracked(() => {
        this.autocomplete.reset()
        this.autocomplete.init(namespaceId)
      })
    })
    effect(() => {
      const initial = this.initialValue()
      untracked(() => {
        if (!initial) return
        this.value.set(initial)
        afterNextRender(
          () => {
            this.resize()
            this.focus()
          },
          { injector: this.injector }
        )
      })
    })
  }

  /** Puts the caret at the end of the text. */
  focus(): void {
    const element = this.textarea()?.nativeElement
    if (!element) return
    element.focus()
    element.setSelectionRange(element.value.length, element.value.length)
  }

  protected onInput(event: Event): void {
    const element = event.target as HTMLTextAreaElement
    // Only an @mention opens a list: slash commands and files are not offered here.
    if (AGENT_MENTION.test(element.value) && !element.value.startsWith('/')) {
      this.autocomplete.onInput(element.value, this.value)
    } else {
      this.value.set(element.value)
      if (this.autocomplete.atSuggestions().length) this.autocomplete.atSuggestions.set([])
    }
    this.resize(element)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (this.autocomplete.onKeydown(event, this.promptRef, this.agentRef)) return
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      this.closed.emit()
    } else if (this.preferences.shouldSend(event)) {
      event.preventDefault()
      this.send()
    }
  }

  protected onAgentSelected(agent: AgentConfig): void {
    this.autocomplete.onAgentSelected(agent, this.agentRef, this.textarea, this.value)
  }

  protected closeAgentSuggestions(): void {
    this.autocomplete.atSuggestions.set([])
  }

  protected send(): void {
    const text = this.value().trim()
    if (!text) return
    this.sent.emit(text)
    this.value.set('')
    this.autocomplete.atSuggestions.set([])
    afterNextRender(() => this.resize(), { injector: this.injector })
  }

  /** Grows with the text, up to the CSS max-height; resetting first lets it shrink again. */
  private resize(element = this.textarea()?.nativeElement): void {
    if (!element) return
    element.style.height = 'auto'
    element.style.height = `${element.scrollHeight}px`
  }
}
