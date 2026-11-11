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
import { getFileIcon } from '../../services/exchange-content.utils'
import { directoryOf, FileSuggestion, fileReference } from '../composer-autocomplete/file-mention'

/**
 * FileAutocompleteComponent — presentational `#` file suggestion dropdown.
 *
 * Lists the files the agent can reach through its exchange tools (namespace and case exchanges) and emits the
 * chosen one. Selecting a file inserts a plain-text, scope-qualified reference in the message —
 * `namespace:docs/readme.md` or `case:notes/todo.md` — so the agent knows which tool scope to read it from.
 *
 * Keyboard navigation (ArrowUp/Down/Enter/Escape) is delegated from the parent through `navigate(key)`, like the
 * agent dropdown whose behaviour it mirrors. No HTTP, no state: the list comes from ComposerAutocompleteService.
 */
@Component({
  selector: 'agentos-file-autocomplete',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './file-autocomplete.component.html',
  styleUrl: './file-autocomplete.component.scss',
})
export class FileAutocompleteComponent {
  /** Filtered, ranked files to display. */
  readonly files = input.required<FileSuggestion[]>()

  /** Emits the file chosen by the user (click or Enter). */
  readonly selected = output<FileSuggestion>()

  /** Emits when the user presses Escape — parent should close the dropdown. */
  readonly dismissed = output<void>()

  protected readonly activeIndex = signal(0)

  protected readonly iconFor = (file: FileSuggestion): string => getFileIcon(file.filename)
  protected readonly directoryOf = directoryOf

  private readonly itemRefs = viewChildren<ElementRef<HTMLLIElement>>('fileItem')

  constructor() {
    // Reset selection index whenever the list changes.
    effect(() => {
      this.files()
      this.activeIndex.set(0)
    })
  }

  /**
   * Handle keyboard navigation delegated from the parent composer.
   * @param key ArrowUp | ArrowDown | Enter | Escape
   */
  navigate(key: 'ArrowUp' | 'ArrowDown' | 'Enter' | 'Escape'): void {
    const list = this.files()
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
        const file = list[this.activeIndex()]
        if (file) this.selected.emit(file)
        break
      }
      case 'Escape':
        this.dismissed.emit()
        break
    }
  }

  protected onItemClick(file: FileSuggestion): void {
    this.selected.emit(file)
  }

  protected isActive(index: number): boolean {
    return this.activeIndex() === index
  }

  /** Text inserted into the textarea on selection: `<scope>:<path> ` with a trailing space. */
  completionFor(file: FileSuggestion): string {
    return fileReference(file)
  }

  private scrollActiveIntoView(): void {
    this.itemRefs()[this.activeIndex()]?.nativeElement.scrollIntoView({ block: 'nearest' })
  }
}
