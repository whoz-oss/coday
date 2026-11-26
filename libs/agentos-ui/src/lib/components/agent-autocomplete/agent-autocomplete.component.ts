import {
  ChangeDetectionStrategy,
  Component,
  effect,
  ElementRef,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core'
import { AgentConfig } from '@whoz-oss/agentos-api-client'
import { MemberMention } from '../composer-autocomplete/member-mention'

/**
 * AgentAutocompleteComponent — presentational @-mention suggestion dropdown.
 *
 * Receives the filtered list of agent configs to display and emits the selected one. A host in a shared case also
 * passes the people it can mention: they are listed after the agents, in the same keyboard order, and selecting one
 * emits `memberSelected` (a person is mentioned, never called like an agent).
 * Keyboard navigation (ArrowUp/Down/Enter/Escape) is delegated from the parent
 * via the `navigate(key)` method — the parent owns the textarea and intercepts keydown.
 *
 * The component is purely presentational: no HTTP calls, no state management.
 */
@Component({
  selector: 'agentos-agent-autocomplete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-autocomplete.component.html',
  styleUrl: './agent-autocomplete.component.scss',
})
export class AgentAutocompleteComponent {
  /** Filtered list of agent configs to display. */
  readonly agents = input.required<AgentConfig[]>()

  /** The people of the case matching what is typed, listed after the agents. Empty outside a shared case. */
  readonly members = input<MemberMention[]>([])

  /** Emits the agent config chosen by the user (click or Enter). */
  readonly selected = output<AgentConfig>()

  /** Emits the person chosen by the user (click or Enter). */
  readonly memberSelected = output<MemberMention>()

  /** Emits when the user presses Escape — parent should close the dropdown. */
  readonly dismissed = output<void>()

  protected readonly activeIndex = signal(0)

  private readonly itemRefs = viewChildren<ElementRef<HTMLLIElement>>('agentItem')

  constructor() {
    // Reset selection index whenever the list changes.
    effect(() => {
      this.agents()
      this.members()
      this.activeIndex.set(0)
    })
  }

  /**
   * Handle keyboard navigation delegated from the parent composer.
   * @param key ArrowUp | ArrowDown | Enter | Escape
   */
  navigate(key: 'ArrowUp' | 'ArrowDown' | 'Enter' | 'Escape'): void {
    const count = this.agents().length + this.members().length
    switch (key) {
      case 'ArrowDown':
        this.activeIndex.set(Math.min(this.activeIndex() + 1, count - 1))
        this.scrollActiveIntoView()
        break
      case 'ArrowUp':
        this.activeIndex.set(Math.max(this.activeIndex() - 1, 0))
        this.scrollActiveIntoView()
        break
      case 'Enter': {
        const index = this.activeIndex()
        const agent = this.agents()[index]
        if (agent) {
          this.selected.emit(agent)
          break
        }
        const member = this.members()[index - this.agents().length]
        if (member) this.memberSelected.emit(member)
        break
      }
      case 'Escape':
        this.dismissed.emit()
        break
    }
  }

  protected onItemClick(agent: AgentConfig): void {
    this.selected.emit(agent)
  }

  protected onMemberClick(member: MemberMention): void {
    this.memberSelected.emit(member)
  }

  protected isActive(index: number): boolean {
    return this.activeIndex() === index
  }

  /** The members come after the agents in the keyboard order. */
  protected isMemberActive(index: number): boolean {
    return this.activeIndex() === this.agents().length + index
  }

  /**
   * Builds the completion text inserted into the textarea on selection.
   * Always `@{name} ` with a trailing space.
   */
  completionFor(agent: AgentConfig): string {
    return `@${agent.name} `
  }

  private scrollActiveIntoView(): void {
    this.itemRefs()[this.activeIndex()]?.nativeElement.scrollIntoView({ block: 'nearest' })
  }
}
