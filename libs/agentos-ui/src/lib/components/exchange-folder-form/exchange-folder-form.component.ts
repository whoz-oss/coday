import { afterNextRender, ChangeDetectionStrategy, Component, ElementRef, inject, Injector, input, output, signal, viewChild } from '@angular/core'
import { folderNameError } from '../../services/exchange-folder.utils'

/**
 * ExchangeFolderFormComponent — inline "new folder" field of a file-exchange section.
 *
 * Asks for the name only: the folder is created inside the folder being browsed ([location]), which the form
 * shows so the user knows where it will land. An invalid name is reported here and never emitted.
 *
 * Presentational, OnPush, I/O in signals.
 */
@Component({
  selector: 'agentos-exchange-folder-form',
  templateUrl: './exchange-folder-form.component.html',
  styleUrl: './exchange-folder-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeFolderFormComponent {
  /** Folder the new one is created in, relative to the scope root. Empty at the root. */
  readonly location = input('')

  /** A valid folder name was entered. */
  readonly submitted = output<string>()

  /** The form was dismissed without creating anything. */
  readonly cancelled = output<void>()

  protected readonly error = signal<string | null>(null)
  private readonly field = viewChild.required<ElementRef<HTMLInputElement>>('field')

  constructor() {
    const injector = inject(Injector)
    afterNextRender(() => this.field().nativeElement.focus(), { injector })
  }

  protected onSubmit(event: Event): void {
    event.preventDefault()
    const name = this.field().nativeElement.value
    const problem = folderNameError(name)
    this.error.set(problem)
    if (!problem) this.submitted.emit(name.trim())
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return
    // The panel closes on Escape: the form takes it first.
    event.stopPropagation()
    event.preventDefault()
    this.cancelled.emit()
  }
}
