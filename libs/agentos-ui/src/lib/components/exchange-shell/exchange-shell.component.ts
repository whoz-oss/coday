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
import { ExchangeDrawerComponent, ExchangeManifestStatus, ExchangeReferenceToggle } from '../exchange-drawer/exchange-drawer.component'

/** Poll only while a visible filter is in use; slow scans may extend this interval. */
const FILTER_REFRESH_INTERVAL_MS = 3000

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
  protected readonly caseManifest = signal<ExchangeFileEntry[] | null>(null)
  protected readonly namespaceManifest = signal<ExchangeFileEntry[] | null>(null)
  protected readonly caseManifestStatus = signal<ExchangeManifestStatus>('idle')
  protected readonly namespaceManifestStatus = signal<ExchangeManifestStatus>('idle')
  private readonly caseQuery = signal('')
  private readonly namespaceQuery = signal('')
  private readonly caseRetry = signal(0)
  private readonly namespaceRetry = signal(0)
  private readonly contextGeneration = signal(0)

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
  private activeNamespaceId: string | null = null
  private selectionGeneration = 0

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
        if (caseId === this.activeCaseId && namespaceId === this.activeNamespaceId) return
        this.contextGeneration.update((value) => value + 1)
        if (namespaceId !== this.activeNamespaceId) {
          this.namespaceManifest.set(null)
          this.namespaceManifestStatus.set(this.namespaceQuery() ? 'loading' : 'idle')
        }
        this.activeCaseId = caseId
        this.activeNamespaceId = namespaceId
        this.resetSelection()
        this.caseManifest.set(null)
        this.caseManifestStatus.set(this.caseQuery() ? 'loading' : 'idle')
        this.state.initializeForCase(namespaceId, caseId)
      } else if (this.activeCaseId !== null || this.activeNamespaceId !== (namespaceId ?? null)) {
        this.contextGeneration.update((value) => value + 1)
        if (this.activeCaseId !== null) this.state.clear()
        this.activeCaseId = null
        this.activeNamespaceId = namespaceId ?? null
        this.resetSelection()
        this.caseManifest.set(null)
        this.namespaceManifest.set(null)
      }
    })
    // A namespace-only drawer borrows the state from its home composer.
    effect((onCleanup) => onCleanup(() => { if (this.activeCaseId !== null) this.state.clear() }))
    // Revoke only the selected scope; the other scope and its filter remain untouched.
    effect(() => {
      const ref = this.selectedFile()
      if (ref && this.scopeForbidden(ref.scope)) this.clearSelection()
    })
    for (const scope of [ExchangeFileEntryScopeEnum.CASE, ExchangeFileEntryScopeEnum.NAMESPACE]) {
      const isCase = scope === ExchangeFileEntryScopeEnum.CASE
      const query = isCase ? this.caseQuery : this.namespaceQuery
      const retry = isCase ? this.caseRetry : this.namespaceRetry
      const version = isCase ? this.state.caseManifestVersion : this.state.namespaceManifestVersion
      const files = isCase ? this.caseManifest : this.namespaceManifest
      const status = isCase ? this.caseManifestStatus : this.namespaceManifestStatus
      const accessUnavailable = isCase ? this.state.caseBrowseUnavailable : this.state.namespaceBrowseUnavailable
      const visible = isCase ? this.state.caseSectionVisible : this.state.namespaceSectionVisible
      const pollingEnabled = computed(() => {
        this.contextGeneration()
        return this.active() && !!query() && (visible() || accessUnavailable()) && !!(isCase ? this.activeCaseId : this.activeNamespaceId)
      })
      let inFlight = false
      let backgroundRefresh = false
      effect((onCleanup) => {
        const active = query()
        const generation = version()
        this.contextGeneration()
        retry()
        if (!active || !(isCase ? this.activeCaseId : this.activeNamespaceId)) { inFlight = false; backgroundRefresh = false; files.set(null); status.set('idle'); return }
        const keepResults = backgroundRefresh && untracked(status) === 'ready'
        backgroundRefresh = false
        if (!keepResults) { files.set(null); status.set('loading') }
        inFlight = true
        const subscription = untracked(() => this.state.fileManifest(scope)).subscribe({
          next: (result) => {
            if (version() !== generation || query() !== active) return
            if (untracked(accessUnavailable)) this.state.revalidateBrowse(scope)
            files.set(result)
            status.set('ready')
          },
          error: (error: { status?: number }) => {
            if (version() !== generation || query() !== active) return
            inFlight = false
            files.set(null)
            status.set('error')
            // GET 404 deliberately hides denied access and can also mean a missing entity.
            // Both 403 and 404 make the selected content unsafe to retain; network errors do not.
            if (error?.status === 403 || error?.status === 404) {
              this.state.denyManifestAccess(scope)
              if (untracked(this.selectedFile)?.scope === scope) this.clearSelection()
              if (untracked(this.pendingDelete)?.scope === scope) this.pendingDelete.set(null)
            }
          },
          complete: () => { inFlight = false },
        })
        onCleanup(() => subscription.unsubscribe())
      })
      effect((onCleanup) => {
        const context = this.contextGeneration()
        if (!pollingEnabled()) return
        const timer = setInterval(() => {
          // Recheck synchronously: context/filter changes can precede effect cleanup.
          if (this.contextGeneration() !== context || !pollingEnabled() || inFlight) return
          inFlight = true
          backgroundRefresh = true
          this.state.invalidateSearchManifest(scope)
        }, FILTER_REFRESH_INTERVAL_MS)
        onCleanup(() => clearInterval(timer))
      })
    }
  }

  protected onFilterChanged(event: { scope: ExchangeScope; query: string }): void {
    const query = event.scope === ExchangeFileEntryScopeEnum.CASE ? this.caseQuery : this.namespaceQuery
    query.set(event.query)
  }

  protected onFilterRetry(scope: ExchangeScope): void {
    const retry = scope === ExchangeFileEntryScopeEnum.CASE ? this.caseRetry : this.namespaceRetry
    retry.update((n) => n + 1)
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
    if (this.scopeForbidden(ref.scope)) return
    const generation = ++this.selectionGeneration
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
      this.loadImage(ref, generation)
      return
    }
    this.state
      .getContent(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (content) => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          this.selectedContent.set(content.content)
          this.contentStatus.set('ready')
        },
        error: () => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          this.contentStatus.set('error')
        },
      })
  }

  // ── Upload (case or namespace, per the drawer section's scope) ────────────────
  protected async onUpload(payload: { scope: ExchangeScope; files: File[] }): Promise<void> {
    if (this.scopeForbidden(payload.scope)) return
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
    if (this.scopeForbidden(ref.scope)) return
    this.actionError.set(null)
    const result = await this.state.downloadFile(ref.scope, ref.path)
    if (!result.success) this.actionError.set(result.error ?? 'Download failed')
  }

  protected async onDownloadAll(scope: ExchangeScope): Promise<void> {
    if (this.scopeForbidden(scope)) return
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
    if (this.scopeForbidden(ref.scope)) return
    this.pendingDelete.set(ref)
  }

  protected async onConfirmDelete(): Promise<void> {
    const ref = this.pendingDelete()
    this.pendingDelete.set(null)
    if (ref && !this.scopeForbidden(ref.scope)) {
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
    if (this.state.caseBrowseUnavailable()) this.state.revalidateBrowse(ExchangeFileEntryScopeEnum.CASE)
    if (this.state.namespaceBrowseUnavailable()) this.state.revalidateBrowse(ExchangeFileEntryScopeEnum.NAMESPACE)
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
  private loadImage(ref: ExchangeFileRef, generation: number): void {
    this.state
      .getBlob(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope)) return
          const typed = ensureImageBlob(blob instanceof Blob ? blob : new Blob([blob]), ref.path)
          this.revokeImageUrl()
          this.selectedImageUrl.set(URL.createObjectURL(typed))
          this.contentStatus.set('ready')
        },
        error: () => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope)) return
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
    this.selectionGeneration++
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
    const isCase = ref.scope === ExchangeFileEntryScopeEnum.CASE
    const files = isCase ? this.state.caseFiles() : this.state.namespaceFiles()
    const manifest = isCase ? this.caseManifest() : this.namespaceManifest()
    return files.find((f) => f.path === ref.path) ?? manifest?.find((f) => f.path === ref.path)
  }

  protected readonly caseBrowseUnavailable = this.state.caseBrowseUnavailable.asReadonly()
  protected readonly namespaceBrowseUnavailable = this.state.namespaceBrowseUnavailable.asReadonly()

  private scopeForbidden(scope: ExchangeScope): boolean {
    const status = scope === ExchangeFileEntryScopeEnum.CASE ? this.state.caseStatus() : this.state.namespaceStatus()
    const unavailable = scope === ExchangeFileEntryScopeEnum.CASE
      ? this.state.caseBrowseUnavailable()
      : this.state.namespaceBrowseUnavailable()
    return status === 'forbidden' || unavailable
  }

  private canViewUnlisted(ref: ExchangeFileRef): boolean {
    if (ref.scope !== ExchangeFileEntryScopeEnum.NAMESPACE) return false
    return isImageFile(ref.path) || isFormatViewable(ref.path)
  }

  private isStillSelected(ref: ExchangeFileRef): boolean {
    const current = this.selectedFile()
    return current !== null && current.scope === ref.scope && current.path === ref.path
  }
}
