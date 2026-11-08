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
import { canViewFile } from '../../services/exchange-content.utils'
import { ExchangeFileRef, ExchangeScope, ExchangeStateService } from '../../services/exchange-state.service'
import { ExchangeDrawerComponent, ExchangeManifestStatus } from '../exchange-drawer/exchange-drawer.component'

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
  readonly closeRequested = output<void>()

  // ── Current selection / viewer content ────────────────────────────────────────
  protected readonly selectedFile = signal<ExchangeFileRef | null>(null)
  protected readonly selectedContent = signal<string | null>(null)
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
      } else if (this.activeCaseId !== null) {
        this.activeCaseId = null
        this.activeNamespaceId = null
        this.resetSelection()
        this.caseManifest.set(null)
        this.namespaceManifest.set(null)
        this.state.clear()
      }
    })
    // Release the shared exchange state when this shell is torn down.
    effect((onCleanup) => onCleanup(() => this.state.clear()))
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
      effect((onCleanup) => {
        const active = query()
        const generation = version()
        retry()
        if (!active) { files.set(null); status.set('idle'); return }
        files.set(null)
        status.set('loading')
        const subscription = untracked(() => this.state.fileManifest(scope)).subscribe({
          next: (result) => {
            if (version() !== generation || !query()) return
            files.set(result)
            status.set('ready')
          },
          error: () => {
            if (version() !== generation || !query()) return
            files.set(null)
            status.set('error')
          },
        })
        onCleanup(() => subscription.unsubscribe())
      })
    }
  }

  protected onFilterChanged(event: { scope: ExchangeScope; query: string }): void {
    ;(event.scope === ExchangeFileEntryScopeEnum.CASE ? this.caseQuery : this.namespaceQuery).set(event.query)
  }

  protected onFilterRetry(scope: ExchangeScope): void {
    ;(scope === ExchangeFileEntryScopeEnum.CASE ? this.caseRetry : this.namespaceRetry).update((n) => n + 1)
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

  protected onClose(): void {
    this.closeRequested.emit()
  }

  /** Viewer back button: clear the active file so the drawer returns to the list. */
  protected onViewerClosed(): void {
    this.clearSelection()
  }

  private clearSelection(): void {
    this.selectionGeneration++
    this.selectedFile.set(null)
    this.selectedContent.set(null)
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

  private scopeForbidden(scope: ExchangeScope): boolean {
    const status = scope === ExchangeFileEntryScopeEnum.CASE ? this.state.caseStatus() : this.state.namespaceStatus()
    return status === 'forbidden'
  }

  private isStillSelected(ref: ExchangeFileRef): boolean {
    const current = this.selectedFile()
    return current !== null && current.scope === ref.scope && current.path === ref.path
  }
}
