import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { validateFolderName } from '../../services/exchange-folder.utils'

/**
 * ExchangeFolderFormComponent — the name prompt of a new folder, shown inline in a drawer section.
 *
 * Presentational: it checks the name is one acceptable path segment, then emits it. Creating the folder, and the
 * server's own refusals (a name already used, a protected location), are the host's business.
 */
@Component({
  selector: 'agentos-exchange-folder-form',
  templateUrl: './exchange-folder-form.component.html',
  styleUrl: './exchange-folder-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeFolderFormComponent {
  /** Where the folder will be created, for the label: a path relative to the scope root, empty for the root. */
  readonly parentPath = input<string>('')

  readonly submitted = output<string>()
  readonly cancelled = output<void>()

  protected readonly name = signal('')
  protected readonly error = signal<string | null>(null)
  private readonly nameInput = viewChild<ElementRef<HTMLInputElement>>('nameInput')

  constructor() {
    afterNextRender(() => this.nameInput()?.nativeElement.focus())
  }

  protected onInput(event: Event): void {
    this.name.set((event.target as HTMLInputElement).value)
    if (this.error()) this.error.set(null)
  }

  protected onSubmit(event: Event): void {
    event.preventDefault()
    const problem = validateFolderName(this.name())
    if (problem) {
      this.error.set(problem)
      return
    }
    this.submitted.emit(this.name().trim())
  }

  protected onEscape(event: Event): void {
    // The panel closes on Escape: here it only dismisses the prompt.
    event.stopPropagation()
    event.preventDefault()
    this.cancelled.emit()
  }
}
