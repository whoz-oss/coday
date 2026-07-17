import { ChangeDetectionStrategy, Component, ElementRef, input, output, viewChild } from '@angular/core'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { getFileIcon } from '../../services/exchange-content.utils'
import { kindLabel, middleTruncate, PendingAttachment } from './composer-attachments.utils'

/**
 * ComposerAttachmentsComponent — presentational chip row for the files staged on a chat
 * message (ChatGPT-style): one chip per attachment (kind icon, middle-truncated name,
 * extension label, remove button), a per-chip error line, the attachment-limit message,
 * and the always-rendered hidden file input the host's "+" button opens via [openPicker].
 */
@Component({
  selector: 'agentos-composer-attachments',
  imports: [IconButtonComponent],
  templateUrl: './composer-attachments.component.html',
  styleUrl: './composer-attachments.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComposerAttachmentsComponent {
  readonly attachments = input.required<PendingAttachment[]>()
  /** True when the resolved upload target is the namespace exchange (previewed on each chip). */
  readonly namespaceBadge = input(false)
  readonly limitError = input<string | null>(null)
  /** Disables the remove buttons while an upload batch is in flight. */
  readonly disabled = input(false)

  readonly removed = output<string>()
  readonly filesSelected = output<File[]>()

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput')

  protected readonly getFileIcon = getFileIcon
  protected readonly kindLabel = kindLabel
  protected readonly middleTruncate = middleTruncate

  /** Opens the native picker; called by the host's "+" button through a template ref. */
  openPicker(): void {
    this.fileInput()?.nativeElement.click()
  }

  protected onFileInputChange(event: Event): void {
    const inputEl = event.target as HTMLInputElement
    const files = inputEl.files ? Array.from(inputEl.files) : []
    if (files.length > 0) this.filesSelected.emit(files)
    // Reset so re-selecting the same file fires (change) again.
    inputEl.value = ''
  }
}
