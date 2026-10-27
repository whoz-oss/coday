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
import { ReasoningEffort } from '@whoz-oss/agentos-api-client'
import { EffortOption } from '../composer-autocomplete/effort-command'

/**
 * EffortAutocompleteComponent — presentational picker opened by the /effort composer command.
 *
 * Lists the effort levels, marks the one the case currently uses and emits the chosen option.
 * Keyboard navigation is delegated from the parent through `navigate(key)`, like the prompt
 * dropdown, whose look it shares.
 */
@Component({
  selector: 'agentos-effort-autocomplete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './effort-autocomplete.component.html',
  styleUrl: '../prompt-autocomplete/prompt-autocomplete.component.scss',
})
export class EffortAutocompleteComponent {
  /** Options matching what the user typed after /effort. */
  readonly options = input.required<EffortOption[]>()

  /** The case-level effort in use; null when each agent uses the effort of its model. */
  readonly current = input<ReasoningEffort | null>(null)

  /** Emits the option chosen by the user (click or Enter). */
  readonly selected = output<EffortOption>()

  /** Emits when the user presses Escape — parent should close the dropdown. */
  readonly dismissed = output<void>()

  protected readonly activeIndex = signal(0)

  private readonly itemRefs = viewChildren<ElementRef<HTMLLIElement>>('effortItem')

  constructor() {
    // Start on the level the case uses, so Enter right after /effort keeps it instead of resetting it.
    effect(() => {
      const index = this.options().findIndex((option) => option.effort === this.current())
      this.activeIndex.set(Math.max(index, 0))
    })
  }

  /**
   * Handle keyboard navigation delegated from the parent composer.
   * @param key ArrowUp | ArrowDown | Enter | Escape
   */
  navigate(key: 'ArrowUp' | 'ArrowDown' | 'Enter' | 'Escape'): void {
    const list = this.options()
    switch (key) {
      case 'ArrowDown':
        this.activeIndex.set(Math.min(this.activeIndex() + 1, list.length - 1))
        this.scrollActiveIntoView()
        break
      case 'ArrowUp':
        this.activeIndex.set(Math.max(this.activeIndex() - 1, 0))
        this.scrollActiveIntoView()
        break
      case 'Enter': {
        const option = list[this.activeIndex()]
        if (option) this.selected.emit(option)
        break
      }
      case 'Escape':
        this.dismissed.emit()
        break
    }
  }

  protected onItemClick(option: EffortOption): void {
    this.selected.emit(option)
  }

  protected isActive(index: number): boolean {
    return this.activeIndex() === index
  }

  protected isCurrent(option: EffortOption): boolean {
    return option.effort === this.current()
  }

  private scrollActiveIntoView(): void {
    this.itemRefs()[this.activeIndex()]?.nativeElement.scrollIntoView({ block: 'nearest' })
  }
}
