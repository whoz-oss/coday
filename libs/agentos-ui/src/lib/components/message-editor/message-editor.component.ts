import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  linkedSignal,
  output,
  viewChild,
} from '@angular/core'
import { ButtonDirective } from '@whoz-oss/design-system'

/**
 * MessageEditorComponent: the in-place editor of a user message, shown in the bubble of the message it edits.
 *
 * It holds the draft and nothing else: the host decides what a re-run is. The one button says what it does,
 * `Rerun` while the text is the original one (trimmed), `Save & Rerun` once it has changed: there is no way
 * to save without running again. Escape cancels, Ctrl/Cmd+Enter sends, and an empty text cannot be sent.
 * The textarea takes the focus when the editor appears.
 *
 * Usage:
 *   <agentos-message-editor [text]="original" [busy]="isRunning()" (rerun)="send($event)" (cancelled)="close()" />
 */
@Component({
  selector: 'agentos-message-editor',
  imports: [ButtonDirective],
  templateUrl: './message-editor.component.html',
  styleUrl: './message-editor.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MessageEditorComponent {
  /** The text of the message being edited: what the draft starts from, and what `Rerun` is compared to. */
  readonly text = input.required<string>()
  /** The case cannot take a message now (a run is in progress): the text can still be edited, not sent. */
  readonly busy = input(false)

  /** The text to run again, trimmed and never empty. */
  readonly rerun = output<string>()
  readonly cancelled = output<void>()

  protected readonly draft = linkedSignal(() => this.text())
  protected readonly rerunLabel = computed(() =>
    this.draft().trim() === this.text().trim() ? 'Rerun' : 'Save & Rerun'
  )
  protected readonly canRerun = computed(() => this.draft().trim().length > 0 && !this.busy())

  private readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field')

  constructor() {
    afterNextRender(() => {
      const field = this.field().nativeElement
      this.resize(field)
      field.focus()
      field.setSelectionRange(field.value.length, field.value.length)
    })
  }

  protected onInput(event: Event): void {
    const field = event.target as HTMLTextAreaElement
    this.draft.set(field.value)
    this.resize(field)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault()
      this.cancelled.emit()
    } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
      event.preventDefault()
      this.submit()
    }
  }

  protected submit(): void {
    if (!this.canRerun()) return
    this.rerun.emit(this.draft().trim())
  }

  /** Grows to the content, the CSS max-height taking over for long texts. */
  private resize(field: HTMLTextAreaElement): void {
    field.style.height = 'auto'
    if (field.scrollHeight > 0) field.style.height = `${field.scrollHeight}px`
  }
}
