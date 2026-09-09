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

  /**
   * Bumped by [reset]. An upload batch captures the value on entry and aborts (returns null)
   * when it changed mid-flight — e.g. the user switched case during the await, which resets
   * this composer-scoped state while the exchange singleton is re-pointed to the new case.
   */
  private generation = 0

  /** Depth of nested dragenter/dragleave pairs — see [onDragLeave]. */
  private dragDepth = 0

  readonly attachments = signal<PendingAttachment[]>([])
  readonly limitError = signal<string | null>(null)
  readonly isDragOver = signal(false)
  readonly isUploading = signal(false)
  readonly hasAttachments = computed(() => this.attachments().length > 0)

  addFiles(files: File[] | FileList): void {
    // The upload loop iterates a snapshot: a file staged mid-batch would silently never
    // upload. The hosts disable the pickers during a batch; this is the backstop.
    if (this.isUploading()) return
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
    if (this.isUploading()) return
    this.attachments.update((current) => current.filter((attachment) => attachment.id !== id))
    if (this.attachments().length < MAX_ATTACHMENTS) this.limitError.set(null)
  }

  reset(): void {
    this.generation++
    this.attachments.set([])
    this.limitError.set(null)
    this.isDragOver.set(false)
    this.isUploading.set(false)
    this.dragDepth = 0
  }

  // ── Drag & drop (bound directly from the host templates) ──────────────────────

  onDragEnter(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    this.dragDepth++
    this.isDragOver.set(true)
  }

  onDragOver(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  }

  /**
   * Enter/leave pairs are counted instead of checking `relatedTarget` containment: WebKit
   * reports `relatedTarget` as null on dragleave (bug 66547), which would collapse the
   * overlay on the first child crossing in Safari. Every child boundary fires one enter
   * then one leave on the container (bubbling), so the depth reaches 0 only on real exit.
   */
  onDragLeave(): void {
    if (this.dragDepth > 0) this.dragDepth--
    if (this.dragDepth === 0) this.isDragOver.set(false)
  }

  onDrop(event: DragEvent): void {
    if (!this.holdsFiles(event)) return
    event.preventDefault()
    this.dragDepth = 0
    this.isDragOver.set(false)
    const files = event.dataTransfer?.files
    if (files && files.length > 0) this.addFiles(files)
  }

  /**
   * Uploads every non-uploaded attachment to [scope], continuing on failure so one duplicate
   * does not silently skip the rest of the batch (mirrors ExchangeShellComponent.onUpload).
   * Entries already `uploaded` by a previous attempt are skipped (no self-inflicted 409) but
   * still appear in the mention. Returns the mention block on full success, or null when at
   * least one file failed (its chip then carries the server-mapped error) or when [reset]
   * was called mid-flight (case/namespace switch) — the caller must not send in either case.
   */
  async uploadAllAndBuildMention(scope: ExchangeScope): Promise<string | null> {
    const generation = this.generation
    this.isUploading.set(true)
    try {
      let anyFailure = false
      for (const attachment of this.attachments()) {
        // Aborted by reset(): the exchange singleton may already point at another case, so
        // uploading the remaining files would land them in the wrong exchange.
        if (this.generation !== generation) return null
        if (attachment.status === 'uploaded') continue
        this.patch(attachment.id, { status: 'uploading', error: undefined })
        const result = await this.exchangeState.uploadFile(scope, attachment.file)
        if (this.generation !== generation) return null
        if (result.success) {
          this.patch(attachment.id, { status: 'uploaded', uploadedScope: scope })
        } else {
          anyFailure = true
          this.patch(attachment.id, { status: 'error', error: result.error ?? 'Upload failed' })
        }
      }
      return anyFailure || this.generation !== generation ? null : buildAttachmentMention(this.attachments())
    } finally {
      if (this.generation === generation) this.isUploading.set(false)
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
