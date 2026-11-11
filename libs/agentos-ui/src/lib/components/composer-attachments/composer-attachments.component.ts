import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  input,
  output,
  viewChild,
} from '@angular/core'
import { ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { getFileIcon } from '../../services/exchange-content.utils'
import { ExchangeFileRef } from '../../services/exchange-state.service'
import { scopeOfRef } from '../composer-autocomplete/file-mention'
import { isImageFile, kindLabel, middleTruncate, PendingAttachment } from './composer-attachments.utils'

/**
 * ComposerAttachmentsComponent — presentational chip row for the files staged on a chat
 * message (ChatGPT-style): one chip per attachment (kind icon, middle-truncated name,
 * extension label, remove button), a per-chip error line, the attachment-limit message,
 * and the always-rendered hidden file input the host's "+" button opens via [openPicker].
 * Image attachments (e.g. pasted screenshots) show a small thumbnail instead of the kind icon.
 *
 * Exchange files picked in the file drawer ([references]) get the same chips, ahead of the staged uploads: they
 * already live in an exchange, so they are never uploaded and do not count in the attachment limit.
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
  /** Exchange files referenced by the message (picked in the file drawer), shown before the staged uploads. */
  readonly references = input<ExchangeFileRef[]>([])
  /** True when the resolved upload target is the namespace exchange (previewed on each chip). */
  readonly namespaceBadge = input(false)
  readonly limitError = input<string | null>(null)
  /** Disables the remove buttons while an upload batch is in flight. */
  readonly disabled = input(false)

  readonly removed = output<string>()
  readonly referenceRemoved = output<ExchangeFileRef>()
  readonly filesSelected = output<File[]>()

  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput')

  /** Object URLs of the image thumbnails, keyed by attachment id (see [previews]). */
  private readonly previewUrls = new Map<string, string>()

  /**
   * Thumbnail URL per image attachment id. Object URLs are created once per attachment and
   * revoked as soon as it leaves the list (removal, reset) and when the component is destroyed.
   */
  protected readonly previews = computed<ReadonlyMap<string, string>>(() => {
    const live = new Set<string>()
    for (const attachment of this.attachments()) {
      if (!isImageFile(attachment.file) || typeof URL.createObjectURL !== 'function') continue
      live.add(attachment.id)
      if (!this.previewUrls.has(attachment.id)) {
        this.previewUrls.set(attachment.id, URL.createObjectURL(attachment.file))
      }
    }
    for (const [id, url] of this.previewUrls) {
      if (live.has(id)) continue
      URL.revokeObjectURL?.(url)
      this.previewUrls.delete(id)
    }
    return new Map(this.previewUrls)
  })

  constructor() {
    // Keeps [previews] evaluated even when the template stops reading it (no chip left), so
    // the object URLs of removed attachments are revoked right away.
    effect(() => {
      this.previews()
    })
    inject(DestroyRef).onDestroy(() => {
      for (const url of this.previewUrls.values()) URL.revokeObjectURL?.(url)
      this.previewUrls.clear()
    })
  }

  /** Thumbnail shown in place of the icon, except while the upload spinner is displayed. */
  protected thumbnail(attachment: PendingAttachment): string | undefined {
    return attachment.status === 'uploading' ? undefined : this.previews().get(attachment.id)
  }

  protected readonly kindLabel = kindLabel
  protected readonly middleTruncate = middleTruncate
  protected readonly scopeOfRef = scopeOfRef

  protected referenceName(reference: ExchangeFileRef): string {
    return reference.path.split('/').pop() || reference.path
  }

  /** Full scope-qualified path, as the agent reads it. */
  protected referenceTitle(reference: ExchangeFileRef): string {
    return `${scopeOfRef(reference.scope)}:${reference.path}`
  }

  protected referenceIcon(reference: ExchangeFileRef): string {
    return scopeOfRef(reference.scope) === 'namespace' ? 'folder_shared' : 'description'
  }

  protected chipIcon(attachment: PendingAttachment): string {
    if (attachment.status === 'uploading') return 'progress_activity'
    if (attachment.status === 'uploaded') return 'check_circle'
    return getFileIcon(attachment.file.name)
  }

  /**
   * The badge reflects the scope the file actually went to once uploaded; before that it
   * previews the batch-level target resolved from the message text.
   */
  protected showNamespaceBadge(attachment: PendingAttachment): boolean {
    return attachment.uploadedScope
      ? attachment.uploadedScope === ExchangeFileEntryScopeEnum.NAMESPACE
      : this.namespaceBadge()
  }

  protected removeTitle(attachment: PendingAttachment): string {
    return attachment.status === 'uploaded'
      ? `Remove ${attachment.file.name} from the list (the uploaded file stays in the exchange)`
      : `Remove ${attachment.file.name}`
  }

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
