import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  input,
  output,
  signal,
  viewChildren,
} from '@angular/core'
import { ModelChoice, sameModel } from '../composer-autocomplete/model-command'

/** Source of the default id prefixes: two pickers on the same page never share an id. */
let nextPickerId = 0

/**
 * ModelAutocompleteComponent — presentational picker opened by the /model composer command.
 *
 * Lists the models the case can use (and the way back to each agent's own model), marks the one the case
 * currently uses and emits the chosen entry. Keyboard navigation is delegated from the parent through
 * `navigate(key)`, like the prompt and effort dropdowns, whose look it shares.
 *
 * Accessibility: the list and each of its options carry an id derived from `idPrefix`, so that the element which
 * keeps the focus (the chip, or the composer textarea) can point at the active option with `aria-activedescendant`
 * (see `listboxId` and `activeOptionId`). `aria-selected` marks the model in use, not the active entry; the options
 * never take the focus, a mousedown on the list is cancelled.
 */
@Component({
  selector: 'agentos-model-autocomplete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './model-autocomplete.component.html',
  styleUrl: '../prompt-autocomplete/prompt-autocomplete.component.scss',
})
export class ModelAutocompleteComponent {
  /** Entries matching what the user typed after /model. */
  readonly options = input.required<ModelChoice[]>()

  /** The case-level model in use; null when each agent uses its own model. */
  readonly current = input<string | null>(null)

  /** Prefix of the ids of the list (`<prefix>-listbox`) and of its options (`<prefix>-option-<index>`). */
  readonly idPrefix = input(`agentos-model-picker-${nextPickerId++}`)

  /** Emits the entry chosen by the user (click or Enter). */
  readonly selected = output<ModelChoice>()

  /** Emits when the user presses Escape — parent should close the dropdown. */
  readonly dismissed = output<void>()

  protected readonly activeIndex = signal(0)

  /** Id of the list, for the `aria-controls` of the element that owns the picker. */
  readonly listboxId = computed(() => `${this.idPrefix()}-listbox`)

  /**
   * Id of the active option, for the `aria-activedescendant` of the element that owns the focus. Index-based on
   * purpose: it must not read `options`, a required input that a host may read this from before it is bound.
   */
  readonly activeOptionId = computed(() => this.optionId(this.activeIndex()))

  private readonly itemRefs = viewChildren<ElementRef<HTMLLIElement>>('modelItem')

  constructor() {
    // Start on the model the case uses, so Enter right after /model keeps it instead of resetting it.
    effect(() => {
      const current = this.current()
      const index = this.options().findIndex((option) => sameModel(option.model, current))
      this.activeIndex.set(Math.max(index, 0))
    })
  }

  /**
   * Handle keyboard navigation delegated from the parent composer.
   * @param key ArrowUp | ArrowDown | Home | End | Enter | Escape
   */
  navigate(key: 'ArrowUp' | 'ArrowDown' | 'Home' | 'End' | 'Enter' | 'Escape'): void {
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
      case 'Home':
        this.activeIndex.set(0)
        this.scrollActiveIntoView()
        break
      case 'End':
        this.activeIndex.set(Math.max(list.length - 1, 0))
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

  protected onItemClick(option: ModelChoice): void {
    this.selected.emit(option)
  }

  protected optionId(index: number): string {
    return `${this.idPrefix()}-option-${index}`
  }

  protected isActive(index: number): boolean {
    return this.activeIndex() === index
  }

  protected isCurrent(option: ModelChoice): boolean {
    return sameModel(option.model, this.current())
  }

  private scrollActiveIntoView(): void {
    this.itemRefs()[this.activeIndex()]?.nativeElement.scrollIntoView({ block: 'nearest' })
  }
}
