import { computed, inject, Injectable, signal } from '@angular/core'
import { ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import { buildAttachmentMention, MAX_ATTACHMENTS, PendingAttachment } from './composer-attachments.utils'

/**
 * ComposerAttachmentsService — staging and upload orchestration for the files attached to a
 * chat message. Provided by each composer component (NOT root): every composer gets an
 * isolated instance whose lifecycle follows its host.
 *
 * Pending files live here (never in [ExchangeStateService]); uploads delegate to
 * [ExchangeStateService.uploadFile], reusing its server-error mapping and manifest refresh.
 */
@Injectable()
export class ComposerAttachmentsService {
  private readonly exchangeState = inject(ExchangeStateService)

  private nextId = 0

  readonly attachments = signal<PendingAttachment[]>([])
  readonly limitError = signal<string | null>(null)
  readonly isDragOver = signal(false)
  readonly isUploading = signal(false)
  readonly hasAttachments = computed(() => this.attachments().length > 0)

  addFiles(files: File[] | FileList): void {
    const incoming = Array.from(files)
    if (incoming.length === 0) return
    const current = this.attachments()
    const room = MAX_ATTACHMENTS - current.length
    this.limitError.set(incoming.length > room ? `You can attach up to ${MAX_ATTACHMENTS} files per message.` : null)
    const accepted = incoming.slice(0, Math.max(0, room)).map(
      (file): PendingAttachment => ({
        id: `attachment-${this.nextId++}`,
        file,
        status: 'pending',
      })
    )
    if (accepted.length > 0) this.attachments.set([...current, ...accepted])
  }

  /** Removes the chip only: an already-uploaded file stays on the server (deletable via the drawer). */
  remove(id: string): void {
    this.attachments.update((current) => current.filter((attachment) => attachment.id !== id))
    if (this.attachments().length < MAX_ATTACHMENTS) this.limitError.set(null)
  }

  reset(): void {
    this.attachments.set([])
    this.limitError.set(null)
    this.isDragOver.set(false)
    this.isUploading.set(false)
  }

  // ── Drag & drop (bound directly from the host templates) ──────────────────────

  onDragEnter(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    this.isDragOver.set(true)
  }

  onDragOver(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  }

  /** Clears the highlight only when the pointer actually leaves [container], not a child of it. */
  onDragLeave(event: DragEvent, container: HTMLElement): void {
    const related = event.relatedTarget as Node | null
    if (related && container.contains(related)) return
    this.isDragOver.set(false)
  }

  onDrop(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    this.isDragOver.set(false)
    const files = event.dataTransfer?.files
    if (files && files.length > 0) this.addFiles(files)
  }

  /**
   * Uploads every non-uploaded attachment to [scope], continuing on failure so one duplicate
   * does not silently skip the rest of the batch (mirrors ExchangeShellComponent.onUpload).
   * Entries already `uploaded` by a previous attempt are skipped (no self-inflicted 409) but
   * still appear in the mention. Returns the mention block on full success, or null when at
   * least one file failed — its chip then carries the server-mapped error.
   */
  async uploadAllAndBuildMention(scope: ExchangeScope): Promise<string | null> {
    this.isUploading.set(true)
    try {
      let anyFailure = false
      for (const attachment of this.attachments()) {
        if (attachment.status === 'uploaded') continue
        this.patch(attachment.id, { status: 'uploading', error: undefined })
        const result = await this.exchangeState.uploadFile(scope, attachment.file)
        if (result.success) {
          this.patch(attachment.id, { status: 'uploaded', uploadedScope: scope })
        } else {
          anyFailure = true
          this.patch(attachment.id, { status: 'error', error: result.error ?? 'Upload failed' })
        }
      }
      return anyFailure ? null : buildAttachmentMention(this.attachments())
    } finally {
      this.isUploading.set(false)
    }
  }

  private holdsFiles(event: DragEvent): boolean {
    return event.dataTransfer?.types.includes('Files') ?? false
  }

  private patch(id: string, changes: Partial<PendingAttachment>): void {
    this.attachments.update((current) =>
      current.map((attachment) => (attachment.id === id ? { ...attachment, ...changes } : attachment))
    )
  }
}
