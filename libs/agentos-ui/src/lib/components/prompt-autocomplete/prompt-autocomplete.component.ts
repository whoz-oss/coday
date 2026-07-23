import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  OnChanges,
  output,
  QueryList,
  signal,
  SimpleChanges,
  ViewChildren,
} from '@angular/core'
import { Prompt } from '@whoz-oss/agentos-api-client'

/**
 * PromptAutocompleteComponent — presentational slash-command suggestion dropdown.
 *
 * Receives the filtered list of prompts to display and emits the selected one.
 * Keyboard navigation (ArrowUp/Down/Enter/Escape) is delegated from the parent
 * via the `navigate(key)` method — the parent owns the textarea and intercepts keydown.
 *
 * The component is purely presentational: no HTTP calls, no state management.
 */
@Component({
  selector: 'agentos-prompt-autocomplete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './prompt-autocomplete.component.html',
  styleUrl: './prompt-autocomplete.component.scss',
})
export class PromptAutocompleteComponent implements OnChanges {
  /** Filtered list of prompts to display. */
  readonly prompts = input.required<Prompt[]>()

  /** Emits the prompt chosen by the user (click or Enter). */
  readonly selected = output<Prompt>()

  /** Emits when the user presses Escape — parent should close the dropdown. */
  readonly dismissed = output<void>()

  protected readonly activeIndex = signal(0)

  @ViewChildren('promptItem') private itemRefs!: QueryList<ElementRef<HTMLLIElement>>

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['prompts']) {
      // Reset selection index when the list changes.
      this.activeIndex.set(0)
    }
  }

  /**
   * Handle keyboard navigation delegated from the parent composer.
   * @param key ArrowUp | ArrowDown | Enter | Escape
   */
  navigate(key: 'ArrowUp' | 'ArrowDown' | 'Enter' | 'Escape'): void {
    const list = this.prompts()
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
        const prompt = list[this.activeIndex()]
        if (prompt) this.selected.emit(prompt)
        break
      }
      case 'Escape':
        this.dismissed.emit()
        break
    }
  }

  protected onItemClick(prompt: Prompt): void {
    this.selected.emit(prompt)
  }

  protected isActive(index: number): boolean {
    return this.activeIndex() === index
  }

  /**
   * Builds the completion text that will be inserted into the textarea on selection.
   * If the prompt has parameters, appends named placeholders so the user knows what to fill.
   */
  /**
   * Builds the completion text inserted into the textarea on selection.
   *
   * Always `/{name} ` with a trailing space — the user types arguments freely.
   * The backend handles all parsing ($ARGUMENTS, $0, $1, …).
   * Parameter names/defaults are shown as hints in the dropdown, not pre-filled.
   */
  completionFor(prompt: Prompt): string {
    return `/${prompt.name} `
  }

  private scrollActiveIntoView(): void {
    const item = this.itemRefs?.toArray()?.[this.activeIndex()]
    item?.nativeElement.scrollIntoView({ block: 'nearest' })
  }
}
