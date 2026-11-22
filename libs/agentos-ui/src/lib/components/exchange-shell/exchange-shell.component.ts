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
import {
  canViewFile,
  ensureTypedBlob,
  isBlobPreviewFile,
  isEditableFormat,
  isFormatViewable,
} from '../../services/exchange-content.utils'
import {
  ExchangeFileRef,
  ExchangeSaveStatus,
  ExchangeScope,
  ExchangeSelectionAction,
  ExchangeStateService,
} from '../../services/exchange-state.service'
import {
  ExchangeDrawerComponent,
  ExchangeManifestStatus,
  ExchangeReferenceToggle,
} from '../exchange-drawer/exchange-drawer.component'

/**
 * Poll only while a visible filter is in use; slow scans may extend this interval. Each poll walks the
 * whole scope on the server, and the agent's own changes already refresh the manifest through events, so
 * polling only has to catch changes made elsewhere.
 */
export const FILTER_REFRESH_INTERVAL_MS = 30000

/**
 * ExchangeShellComponent — smart container for the file-exchange drawer panel.
 *
 * Owns the ExchangeStateService lifecycle (initializeForCase / clear from the `?ns`/`?case` query params),
 * loads file content on selection, saves edits of a text file the user may write, relays "ask an agent about
 * this selection" upward, and routes upload (always CASE) / download / delete
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
  /** The user asked an agent to act on a selection of the previewed document. */
  readonly selectionActionRequested = output<ExchangeSelectionAction>()

  // ── Current selection / viewer content ────────────────────────────────────────
  protected readonly selectedFile = signal<ExchangeFileRef | null>(null)
  protected readonly selectedContent = signal<string | null>(null)
  /**
   * Object URL of the previewed image or PDF, and the typed blob behind it. Owned here so the URL can be revoked
   * when the selection changes.
   */
  protected readonly selectedBlobUrl = signal<string | null>(null)
  protected readonly selectedBlob = signal<Blob | null>(null)
  /** Etag of the loaded content: the save is refused by the server if the file changed since. */
  protected readonly selectedEtag = signal<string | null>(null)
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

  // ── Editing ───────────────────────────────────────────────────────────────────
  protected readonly saveStatus = signal<ExchangeSaveStatus>('idle')
  protected readonly saveError = signal<string | null>(null)
  /** The viewer holds unsaved edits: the content must not be replaced under them. */
  private readonly viewerDirty = signal(false)

  /** The selected file is a text file of a scope the user may write (fail-closed on anything not ready). */
  protected readonly canEditSelected = computed(() => {
    const ref = this.selectedFile()
    if (!ref || !this.canViewSelected() || this.contentStatus() !== 'ready') return false
    const isCase = ref.scope === ExchangeFileEntryScopeEnum.CASE
    const scopeReady = (isCase ? this.state.caseStatus() : this.state.namespaceStatus()) === 'ready'
    const canWrite = isCase ? this.state.canWriteCase() : this.state.canWriteNamespace()
    return scopeReady && canWrite && isEditableFormat(ref.path.split('/').pop() ?? ref.path)
  })

  /**
   * Every content request and every deselection bumps it: only the answer to the latest one is
   * applied, so a slow reply can neither resurrect a closed file nor overwrite a newer content.
   */
  private selectionGeneration = 0
  /** Size and modification time of the file in the listing when its content was last requested. */
  private loadedStamp: string | null = null

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

  protected readonly environmentCaseId = signal<string | null>(null)

  constructor() {
    // An object URL keeps its blob in memory until revoked.
    this.destroyRef.onDestroy(() => this.revokeBlobUrl())
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
    effect((onCleanup) =>
      onCleanup(() => {
        if (this.activeCaseId !== null) this.state.clear()
      })
    )
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
        return (
          this.active() &&
          !!query() &&
          (visible() || accessUnavailable()) &&
          !!(isCase ? this.activeCaseId : this.activeNamespaceId)
        )
      })
      let inFlight = false
      let backgroundRefresh = false
      effect((onCleanup) => {
        const active = query()
        const generation = version()
        this.contextGeneration()
        retry()
        if (!active || !(isCase ? this.activeCaseId : this.activeNamespaceId)) {
          inFlight = false
          backgroundRefresh = false
          files.set(null)
          status.set('idle')
          return
        }
        // A closed drawer keeps its last result and fetches again when it reopens, not on every change.
        if (!this.active()) {
          inFlight = false
          backgroundRefresh = false
          return
        }
        const background = backgroundRefresh
        backgroundRefresh = false
        // Keep showing the previous result while a refresh is in flight, rather than flashing a spinner.
        // A poll after a failure likewise keeps the error and its Retry button in place.
        if (untracked(files) === null && !(background && untracked(status) === 'error')) status.set('loading')
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
          complete: () => {
            inFlight = false
          },
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
    // The agent edits files during a turn: when the listing reports a new version of the previewed
    // text file, show it. Never under unsaved edits or a save in flight; the change is picked up as
    // soon as they end (the stamp still differs then).
    effect(() => {
      const ref = this.selectedFile()
      const stamp = ref ? this.stampOf(ref) : null
      const blocked = this.viewerDirty() || this.saveStatus() === 'saving'
      untracked(() => {
        if (!ref || stamp === null || blocked || stamp === this.loadedStamp) return
        // Only the text endpoint is reloaded here; images and PDFs keep their object URL.
        if (!this.canViewSelected() || isBlobPreviewFile(ref.path) || this.scopeForbidden(ref.scope)) return
        this.loadedStamp = stamp
        this.fetchContent(ref, true)
      })
    })
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
    this.revokeBlobUrl()
    this.selectedEtag.set(null)
    this.resetEditing()
    const file = this.findFile(ref)
    // A file reached through the filter lives deeper than the directory being browsed: judge it by its
    // manifest entry, or by its name alone when even that is unknown.
    const viewable = file ? canViewFile(file) : this.canViewUnlisted(ref)
    this.canViewSelected.set(viewable)
    if (!viewable) {
      this.selectionGeneration++
      this.contentStatus.set('ready')
      return
    }
    this.contentStatus.set('loading')
    if (isBlobPreviewFile(ref.path)) {
      this.loadBlob(ref, generation)
      return
    }
    this.loadedStamp = this.stampOf(ref)
    this.fetchContent(ref, false)
  }

  /**
   * Load the text content of [ref]. A [background] load (the file changed in the listing, or a reload after
   * a conflict) keeps what is shown until the answer comes, and drops it when it is the version already shown.
   */
  private fetchContent(ref: ExchangeFileRef, background: boolean): void {
    const generation = ++this.selectionGeneration
    this.state
      .getContent(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (content) => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          if (background && content.etag != null && content.etag === this.selectedEtag()) return
          this.selectedContent.set(content.content)
          this.selectedEtag.set(content.etag ?? null)
          this.contentStatus.set('ready')
        },
        error: () => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          // A failed background reload keeps what is shown.
          if (!background) this.contentStatus.set('error')
        },
      })
  }

  /** Version of a file in the listing being browsed, null when it is not listed. */
  private stampOf(ref: ExchangeFileRef): string | null {
    const file = this.findFile(ref)
    return file ? `${file.size ?? ''}:${file.lastModified ?? ''}` : null
  }

  // ── Edit ──────────────────────────────────────────────────────────────────────
  protected async onSaveRequested(event: { ref: ExchangeFileRef; content: string }): Promise<void> {
    const { ref, content } = event
    if (this.saveStatus() === 'saving' || !this.isStillSelected(ref)) return
    this.saveStatus.set('saving')
    this.saveError.set(null)
    const result = await this.state.saveContent(ref.scope, ref.path, content, this.selectedEtag() ?? undefined)
    if (!this.isStillSelected(ref)) return
    if (result.success) {
      // The viewer leaves its edit mode once the content it is given is the text it saved.
      this.selectedContent.set(content)
      this.selectedEtag.set(result.etag ?? null)
      this.saveStatus.set('idle')
    } else {
      this.saveStatus.set(result.conflict ? 'conflict' : 'error')
      this.saveError.set(result.error ?? 'Save failed')
    }
  }

  /** After a conflict: drop the stale edits and load the file as the server now has it. */
  protected onReloadRequested(): void {
    const ref = this.selectedFile()
    this.resetEditing()
    if (!ref) return
    this.loadedStamp = this.stampOf(ref)
    this.fetchContent(ref, false)
  }

  protected onDirtyChanged(dirty: boolean): void {
    this.viewerDirty.set(dirty)
  }

  private resetEditing(): void {
    this.saveStatus.set('idle')
    this.saveError.set(null)
    this.viewerDirty.set(false)
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
   * Images and PDFs cannot go through the text endpoint: fetch the raw bytes via the download endpoint and
   * preview them from an object URL.
   */
  private loadBlob(ref: ExchangeFileRef, generation: number): void {
    this.state
      .getBlob(ref.scope, ref.path)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (blob) => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          const typed = ensureTypedBlob(blob instanceof Blob ? blob : new Blob([blob]), ref.path)
          this.revokeBlobUrl()
          this.selectedBlob.set(typed)
          this.selectedBlobUrl.set(URL.createObjectURL(typed))
          this.contentStatus.set('ready')
        },
        error: () => {
          if (generation !== this.selectionGeneration || !this.isStillSelected(ref) || this.scopeForbidden(ref.scope))
            return
          this.contentStatus.set('error')
        },
      })
  }

  private revokeBlobUrl(): void {
    const url = this.selectedBlobUrl()
    if (url) URL.revokeObjectURL(url)
    this.selectedBlobUrl.set(null)
    this.selectedBlob.set(null)
  }

  private clearSelection(): void {
    this.selectionGeneration++
    this.selectedFile.set(null)
    this.selectedContent.set(null)
    this.revokeBlobUrl()
    this.selectedEtag.set(null)
    this.loadedStamp = null
    this.resetEditing()
    this.contentStatus.set('loading')
    this.canViewSelected.set(true)
  }

  private resetSelection(): void {
    this.clearSelection()
    // A confirmation opened in the previous context would otherwise delete the same path in the new one.
    this.pendingDelete.set(null)
    this.actionError.set(null)
  }

  private findFile(
    ref: ExchangeFileRef
  ): { name?: string; filename?: string; size?: number; lastModified?: string } | undefined {
    const isCase = ref.scope === ExchangeFileEntryScopeEnum.CASE
    const files = isCase ? this.state.caseFiles() : this.state.namespaceFiles()
    const manifest = isCase ? this.caseManifest() : this.namespaceManifest()
    return files.find((f) => f.path === ref.path) ?? manifest?.find((f) => f.path === ref.path)
  }

  protected readonly caseBrowseUnavailable = this.state.caseBrowseUnavailable.asReadonly()
  protected readonly namespaceBrowseUnavailable = this.state.namespaceBrowseUnavailable.asReadonly()

  private scopeForbidden(scope: ExchangeScope): boolean {
    const status = scope === ExchangeFileEntryScopeEnum.CASE ? this.state.caseStatus() : this.state.namespaceStatus()
    const unavailable =
      scope === ExchangeFileEntryScopeEnum.CASE
        ? this.state.caseBrowseUnavailable()
        : this.state.namespaceBrowseUnavailable()
    return status === 'forbidden' || unavailable
  }

  private canViewUnlisted(ref: ExchangeFileRef): boolean {
    if (ref.scope !== ExchangeFileEntryScopeEnum.NAMESPACE) return false
    return isBlobPreviewFile(ref.path) || isFormatViewable(ref.path)
  }

  private isStillSelected(ref: ExchangeFileRef): boolean {
    const current = this.selectedFile()
    return current !== null && current.scope === ref.scope && current.path === ref.path
  }
}
