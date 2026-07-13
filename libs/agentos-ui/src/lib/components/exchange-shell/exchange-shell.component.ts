import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, output, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import { ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ConfirmDialogComponent } from '@whoz-oss/design-system'
import { canViewFile } from '../../services/exchange-content.utils'
import { ExchangeFileRef, ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import { ExchangeDrawerComponent } from '../exchange-drawer/exchange-drawer.component'

/**
 * ExchangeShellComponent — smart container for the file-exchange drawer panel.
 *
 * Owns the ExchangeStateService lifecycle (initializeForCase / clear from the `?ns`/`?case` query params),
 * loads file content on selection, and routes upload (always CASE) / download / delete
 * (behind ds-confirm-dialog) / retry. Rendered inside the right ds-drawer hosted by
 * case-chat; closing is delegated upward via `closeRequested`.
 *
 * I/O in signals (decision #8).
 */
@Component({
  selector: 'agentos-exchange-shell',
  standalone: true,
  imports: [ExchangeDrawerComponent, ConfirmDialogComponent],
  templateUrl: './exchange-shell.component.html',
  styleUrl: './exchange-shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeShellComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly destroyRef = inject(DestroyRef)
  protected readonly state = inject(ExchangeStateService)

  readonly closeRequested = output<void>()

  // ── Current selection / viewer content ────────────────────────────────────────
  protected readonly selectedFile = signal<ExchangeFileRef | null>(null)
  protected readonly selectedContent = signal<string | null>(null)
  protected readonly contentStatus = signal<'loading' | 'ready' | 'error'>('loading')
  protected readonly canViewSelected = signal<boolean>(true)
  protected readonly actionError = signal<string | null>(null)

  // ── Delete confirmation ─────────────────────────────────────────────────────
  protected readonly pendingDelete = signal<ExchangeFileRef | null>(null)
  protected readonly confirmOpen = computed(() => this.pendingDelete() !== null)
  protected readonly deleteMessage = computed(() => {
    const ref = this.pendingDelete()
    if (!ref) return ''
    const filename = ref.path.split('/').pop() ?? ref.path
    return `Delete "${filename}"? This action cannot be undone.`
  })

  /** The case currently initialised, so an unrelated query-param change does not re-init. */
  private activeCaseId: string | null = null

  constructor() {
    // The case view is rendered directly by the case-shell (not through a router-outlet with
    // `:namespaceId/cases/:caseId` path params), so the active case/namespace come through query
    // params (`?ns=..&case=..`), mirroring case-chat. Subscribing (not reading the snapshot once)
    // re-initialises the drawer when the user switches case on the reused component instance.
    // In the constructor injection context, takeUntilDestroyed() needs no explicit DestroyRef.
    this.route.queryParams.pipe(takeUntilDestroyed()).subscribe((params) => {
      const namespaceId = params['ns'] as string | undefined
      const caseId = params['case'] as string | undefined
      if (namespaceId && caseId) {
        // Guard on the case actually changing (like case-chat): a re-emission for some other query
        // param must not wipe the open file and double-refetch both manifests.
        if (caseId === this.activeCaseId) return
        this.activeCaseId = caseId
        this.resetSelection()
        this.state.initializeForCase(namespaceId, caseId)
      } else if (this.activeCaseId !== null) {
        this.activeCaseId = null
        this.state.clear()
      }
    })
    // Release the shared exchange state when this shell is torn down.
    this.destroyRef.onDestroy(() => this.state.clear())
  }

  // ── View ──────────────────────────────────────────────────────────────────────
  protected onFileSelected(ref: ExchangeFileRef): void {
    this.selectedFile.set(ref)
    this.selectedContent.set(null)
    const file = this.findFile(ref)
    const viewable = file ? canViewFile(file) : false
    this.canViewSelected.set(viewable)
    if (!viewable) {
      this.contentStatus.set('ready')
      return
    }
    this.contentStatus.set('loading')
    this.state
      .getContent(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (content) => {
          if (!this.isStillSelected(ref)) return
          this.selectedContent.set(content.content)
          this.contentStatus.set('ready')
        },
        error: () => {
          if (!this.isStillSelected(ref)) return
          this.contentStatus.set('error')
        },
      })
  }

  // ── Upload (case or namespace, per the drawer section's scope) ────────────────
  protected async onUpload(payload: { scope: ExchangeScope; files: File[] }): Promise<void> {
    this.actionError.set(null)
    // Upload every selected file even if one fails, then report all failures together — a leading
    // failure (e.g. a duplicate) must not silently skip the rest of the batch.
    const failures: { name: string; error: string }[] = []
    for (const file of payload.files) {
      const result = await this.state.uploadFile(payload.scope, file)
      if (!result.success) failures.push({ name: file.name, error: result.error ?? 'Upload failed' })
    }
    if (failures.length === 1) {
      this.actionError.set(failures[0]?.error ?? 'Upload failed')
    } else if (failures.length > 1) {
      this.actionError.set(
        `${failures.length} of ${payload.files.length} files failed to upload: ${failures.map((f) => f.name).join(', ')}`
      )
    }
  }

  // ── Download ──────────────────────────────────────────────────────────────────
  protected async onDownload(ref: ExchangeFileRef): Promise<void> {
    this.actionError.set(null)
    const result = await this.state.downloadFile(ref.scope, ref.path)
    if (!result.success) this.actionError.set(result.error ?? 'Download failed')
  }

  protected async onDownloadAll(scope: ExchangeScope): Promise<void> {
    this.actionError.set(null)
    const result = await this.state.downloadAll(scope)
    if (result.failedCount > 0) {
      this.actionError.set(
        result.failedCount === 1 ? 'A file failed to download.' : `${result.failedCount} files failed to download.`
      )
    }
  }

  // ── Delete (confirmed, case or namespace) ──────────────────────────────────────
  protected onDeleteRequest(ref: ExchangeFileRef): void {
    this.pendingDelete.set(ref)
  }

  protected async onConfirmDelete(): Promise<void> {
    const ref = this.pendingDelete()
    this.pendingDelete.set(null)
    if (ref) {
      const result = await this.state.deleteFile(ref.scope, ref.path)
      if (result.success) {
        // Close the viewer if the file being previewed is the one just deleted.
        if (this.isStillSelected(ref)) this.clearSelection()
      } else {
        this.actionError.set(result.error ?? 'Delete failed')
      }
    }
  }

  protected onCancelDelete(): void {
    this.pendingDelete.set(null)
  }

  // ── Misc ──────────────────────────────────────────────────────────────────────
  protected onRetry(): void {
    this.state.refreshManifest()
  }

  protected onClose(): void {
    this.closeRequested.emit()
  }

  /** Viewer back button: clear the active file so the drawer returns to the list. */
  protected onViewerClosed(): void {
    this.clearSelection()
  }

  private clearSelection(): void {
    this.selectedFile.set(null)
    this.selectedContent.set(null)
    this.contentStatus.set('loading')
    this.canViewSelected.set(true)
  }

  private resetSelection(): void {
    this.clearSelection()
    this.actionError.set(null)
  }

  private findFile(ref: ExchangeFileRef): ExchangeFileEntry | undefined {
    const files = ref.scope === ExchangeFileEntryScopeEnum.CASE ? this.state.caseFiles() : this.state.namespaceFiles()
    return files.find((f) => f.path === ref.path)
  }

  private isStillSelected(ref: ExchangeFileRef): boolean {
    const current = this.selectedFile()
    return current !== null && current.scope === ref.scope && current.path === ref.path
  }
}
