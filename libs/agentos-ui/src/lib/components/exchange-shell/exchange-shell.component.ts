import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import { ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ConfirmDialogComponent } from '@whoz-oss/design-system'
import { canViewFile, ensureImageBlob, isFormatViewable, isImageFile } from '../../services/exchange-content.utils'
import { ExchangeFileRef, ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import {
  ExchangeDrawerComponent,
  ExchangeManifestStatus,
  ExchangeReferenceToggle,
} from '../exchange-drawer/exchange-drawer.component'

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

  readonly active = input(false)
  /** Lets the user check files to put them in the message being written (relayed to the drawer). */
  readonly selectable = input(false)
  /** Files the message references: the drawer checks exactly these. */
  readonly referenced = input<ExchangeFileRef[]>([])
  readonly closeRequested = output<void>()
  readonly referenceToggled = output<ExchangeReferenceToggle>()

  // ── Current selection / viewer content ────────────────────────────────────────
  protected readonly selectedFile = signal<ExchangeFileRef | null>(null)
  protected readonly selectedContent = signal<string | null>(null)
  /** Object URL of the previewed image; owned here so it can be revoked when the selection changes. */
  protected readonly selectedImageUrl = signal<string | null>(null)
  protected readonly contentStatus = signal<'loading' | 'ready' | 'error'>('loading')
  protected readonly canViewSelected = signal<boolean>(true)
  protected readonly actionError = signal<string | null>(null)

  // ── Namespace file filter ────────────────────────────────────────────────────────────────────────────────
  /** Query reported by the drawer; the recursive manifest is only fetched while it is non-empty. */
  private readonly namespaceFilterQuery = signal('')
  private readonly manifestRetry = signal(0)
  protected readonly namespaceManifest = signal<ExchangeFileEntry[] | null>(null)
  protected readonly namespaceManifestStatus = signal<ExchangeManifestStatus>('idle')

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

  protected readonly environmentCaseId = signal<string | null>(null)

  constructor() {
    // An object URL keeps its blob in memory until revoked.
    this.destroyRef.onDestroy(() => this.revokeImageUrl())
    // The case view is rendered directly by the case-shell (not through a router-outlet with
    // `:namespaceId/cases/:caseId` path params), so the active case/namespace come through query
    // params (`?ns=..&case=..`), mirroring case-chat. Subscribing (not reading the snapshot once)
    // re-initialises the drawer when the user switches case on the reused component instance.
    // In the constructor injection context, takeUntilDestroyed() needs no explicit DestroyRef.
    this.route.queryParams.pipe(takeUntilDestroyed()).subscribe((params) => {
      const namespaceId = params['ns'] as string | undefined
      const caseId = params['case'] as string | undefined
      this.environmentCaseId.set(caseId ?? null)
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
    // Release the shared exchange state when this shell is torn down, but only if it initialised a case itself:
    // without a case (namespace-only mode on the home composer) the state belongs to the host.
    effect((onCleanup) =>
      onCleanup(() => {
        if (this.activeCaseId !== null) this.state.clear()
      })
    )

    // Load the recursive namespace manifest lazily, when a filter is typed, and again whenever the
    // namespace scope changed (upload, delete, refresh): the state service hands out a fresh request
    // then, and replays the cached one otherwise, so typing more characters costs nothing.
    effect((onCleanup) => {
      const query = this.namespaceFilterQuery()
      this.state.namespaceManifestVersion()
      this.manifestRetry()
      if (!query) {
        this.namespaceManifest.set(null)
        this.namespaceManifestStatus.set('idle')
        return
      }
      // Keep showing the previous result while a refresh is in flight, rather than flashing a spinner.
      if (untracked(() => this.namespaceManifest()) === null) this.namespaceManifestStatus.set('loading')
      const subscription = untracked(() => this.state.namespaceManifest()).subscribe({
        next: (files) => {
          this.namespaceManifest.set(files)
          this.namespaceManifestStatus.set('ready')
        },
        error: () => {
          this.namespaceManifest.set(null)
          this.namespaceManifestStatus.set('error')
        },
      })
      onCleanup(() => subscription.unsubscribe())
    })
  }

  // ── View ──────────────────────────────────────────────────────────────────────
  /** Navigate the drawer into a folder, or back to a breadcrumb level. */
  protected onFolderOpened(event: { scope: ExchangeScope; path: string }): void {
    if (event.scope === ExchangeFileEntryScopeEnum.CASE) {
      this.state.browseCase(event.path)
    } else {
      this.state.browseNamespace(event.path)
    }
  }

  protected onFileSelected(ref: ExchangeFileRef): void {
    this.selectedFile.set(ref)
    this.selectedContent.set(null)
    this.revokeImageUrl()
    const file = this.findFile(ref)
    // A file reached through the filter lives deeper than the directory being browsed: judge it by its
    // manifest entry, or by its name alone when even that is unknown.
    const viewable = file ? canViewFile(file) : this.canViewUnlisted(ref)
    this.canViewSelected.set(viewable)
    if (!viewable) {
      this.contentStatus.set('ready')
      return
    }
    this.contentStatus.set('loading')
    if (isImageFile(ref.path)) {
      this.loadImage(ref)
      return
    }
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
        result.error ??
          (result.failedCount === 1 ? 'A file failed to download.' : `${result.failedCount} files failed to download.`)
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

  protected onNamespaceFilterChanged(query: string): void {
    this.namespaceFilterQuery.set(query)
  }

  protected onNamespaceFilterRetry(): void {
    this.manifestRetry.update((n) => n + 1)
  }

  protected onClose(): void {
    this.closeRequested.emit()
  }

  /** Without a case in the route, only the namespace is browsed: the case section has nothing to show. */
  protected readonly caseSectionVisible = computed(() => this.state.caseSectionVisible() && !!this.environmentCaseId())

  /** Viewer back button: clear the active file so the drawer returns to the list. */
  protected onViewerClosed(): void {
    this.clearSelection()
  }

  /**
   * Images cannot go through the text endpoint: fetch the raw bytes via the download endpoint and
   * preview them from an object URL.
   */
  private loadImage(ref: ExchangeFileRef): void {
    this.state
      .getBlob(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          if (!this.isStillSelected(ref)) return
          const typed = ensureImageBlob(blob instanceof Blob ? blob : new Blob([blob]), ref.path)
          this.revokeImageUrl()
          this.selectedImageUrl.set(URL.createObjectURL(typed))
          this.contentStatus.set('ready')
        },
        error: () => {
          if (!this.isStillSelected(ref)) return
          this.contentStatus.set('error')
        },
      })
  }

  private revokeImageUrl(): void {
    const url = this.selectedImageUrl()
    if (url) URL.revokeObjectURL(url)
    this.selectedImageUrl.set(null)
  }

  private clearSelection(): void {
    this.selectedFile.set(null)
    this.selectedContent.set(null)
    this.revokeImageUrl()
    this.contentStatus.set('loading')
    this.canViewSelected.set(true)
  }

  private resetSelection(): void {
    this.clearSelection()
    this.actionError.set(null)
  }

  private findFile(ref: ExchangeFileRef): { name?: string; filename?: string; size?: number } | undefined {
    const files = ref.scope === ExchangeFileEntryScopeEnum.CASE ? this.state.caseFiles() : this.state.namespaceFiles()
    const listed = files.find((f) => f.path === ref.path)
    if (listed || ref.scope === ExchangeFileEntryScopeEnum.CASE) return listed
    return this.namespaceManifest()?.find((f) => f.path === ref.path)
  }

  /**
   * Preview decision for a namespace file absent from both the current directory and the manifest.
   * Without a size only the format can be checked; the server still enforces its own limits.
   */
  private canViewUnlisted(ref: ExchangeFileRef): boolean {
    if (ref.scope !== ExchangeFileEntryScopeEnum.NAMESPACE) return false
    return isImageFile(ref.path) || isFormatViewable(ref.path)
  }

  private isStillSelected(ref: ExchangeFileRef): boolean {
    const current = this.selectedFile()
    return current !== null && current.scope === ref.scope && current.path === ref.path
  }
}
