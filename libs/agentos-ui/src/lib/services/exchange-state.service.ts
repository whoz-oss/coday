import { computed, inject, Injectable, Signal, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import {
  ExchangeControllerService,
  ExchangeFileContent,
  ExchangeFileEntryScopeEnum,
  ExchangeDirectoryEntry,
  ExchangeDirectoryListing,
  ExchangeDirectoryListingCapabilityEnum,
} from '@whoz-oss/agentos-api-client'
import {
  BehaviorSubject,
  catchError,
  EMPTY,
  expand,
  filter,
  finalize,
  firstValueFrom,
  map,
  Observable,
  of,
  scan,
  shareReplay,
  startWith,
  switchMap,
  tap,
  take,
  throwError,
} from 'rxjs'

/** Scope of an exchange (matches the generated `ExchangeFileEntryScopeEnum`). */
export type ExchangeScope = ExchangeFileEntryScopeEnum

/** Per-scope load status — drives the fail-closed gating (forbidden ≠ error ≠ ready). */
export type ExchangeScopeStatus = 'loading' | 'ready' | 'forbidden' | 'error'

/** Minimal reference to a file, used to address it across components. */
export interface ExchangeFileRef {
  scope: ExchangeScope
  path: string
}

interface ExchangeScopeView {
  status: ExchangeScopeStatus
  /** Entries of the directory currently being browsed: sub-directories first, then files. */
  entries: ExchangeDirectoryEntry[]
  /** Directory currently browsed, relative to the scope root. Empty string is the root. */
  path: string
  /** Total entries in this directory, which may exceed those loaded so far. */
  totalEntries: number
  hasMore: boolean
  capability: ExchangeDirectoryListingCapabilityEnum
}

const NEUTRAL_LOADING: ExchangeScopeView = {
  status: 'loading',
  entries: [],
  path: '',
  totalEntries: 0,
  hasMore: false,
  capability: ExchangeDirectoryListingCapabilityEnum.NONE,
}

/** Breadcrumb segment: the label to show and the path to navigate to. */
export interface ExchangePathSegment {
  label: string
  path: string
}

/** Split a directory path into navigable breadcrumb segments, root first. */
export function toBreadcrumb(path: string): ExchangePathSegment[] {
  const trimmed = path.replace(/^\/+|\/+$/g, '')
  if (!trimmed) return []
  const parts = trimmed.split('/')
  return parts.map((label, index) => ({ label, path: parts.slice(0, index + 1).join('/') }))
}

/**
 * ExchangeStateService — reactive state for the case / namespace file-exchange drawer.
 *
 * Source of truth = the server-computed manifest. Capability is **fail-closed**:
 *   - any error maps to capability `NONE`;
 *   - 403/404 → `forbidden` (section hidden, zero disclosure);
 *   - other errors → `error` (retry banner), still `NONE` (no write affordance).
 * We deliberately DO NOT use `multicastRefreshable` (it fail-opens, swallowing 403/404/5xx),
 * so the forbidden / error / ready distinction the gating depends on is preserved.
 *
 * Listings use the generated `ExchangeControllerService`.
 */
@Injectable({ providedIn: 'root' })
export class ExchangeStateService {
  private readonly controller = inject(ExchangeControllerService)

  private namespaceId: string | null = null
  private caseId: string | null = null

  // Per-scope refresh triggers: the agent only mutates the case scope, so case-driven refreshes
  // don't refetch the (read-only) namespace manifest. refreshManifest() refreshes both.
  private readonly caseRefresh$ = new BehaviorSubject<void>(undefined)
  private readonly namespaceRefresh$ = new BehaviorSubject<void>(undefined)

  // Directory currently browsed per scope. Held here rather than in the drawer so it survives the
  // drawer being collapsed and reopened, and so a refresh reloads the level the user is looking at
  // instead of snapping back to the root.
  private casePath = ''
  private namespacePath = ''
  private casePage = 0
  private namespacePage = 0
  readonly caseLoadingMore = signal(false)
  readonly namespaceLoadingMore = signal(false)

  private readonly caseView = this.buildScopeView(
    this.caseRefresh$,
    () => this.caseId,
    () => this.casePath,
    (id) =>
      this.loadScope(() => this.loadDirectory(ExchangeFileEntryScopeEnum.CASE, id)).pipe(
        finalize(() => this.caseLoadingMore.set(false))
      )
  )
  private readonly namespaceView = this.buildScopeView(
    this.namespaceRefresh$,
    () => this.namespaceId,
    () => this.namespacePath,
    (id) =>
      this.loadScope(() => this.loadDirectory(ExchangeFileEntryScopeEnum.NAMESPACE, id)).pipe(
        finalize(() => this.namespaceLoadingMore.set(false))
      )
  )

  // ── Public derived state (always from the manifest, never inferred from raw roles) ──
  readonly caseStatus = computed(() => this.caseView().status)
  readonly namespaceStatus = computed(() => this.namespaceView().status)
  /** Files at the level currently browsed (directories are exposed separately). */
  readonly caseFiles = computed(() => this.caseView().entries.filter((e) => !e.directory))
  readonly namespaceFiles = computed(() => this.namespaceView().entries.filter((e) => !e.directory))
  readonly caseFolders = computed(() => this.caseView().entries.filter((e) => e.directory))
  readonly namespaceFolders = computed(() => this.namespaceView().entries.filter((e) => e.directory))

  readonly caseBrowsePath = computed(() => this.caseView().path)
  readonly namespaceBrowsePath = computed(() => this.namespaceView().path)
  readonly caseBreadcrumb = computed(() => toBreadcrumb(this.caseView().path))
  readonly namespaceBreadcrumb = computed(() => toBreadcrumb(this.namespaceView().path))
  readonly caseHasMore = computed(() => this.caseView().hasMore)
  readonly namespaceHasMore = computed(() => this.namespaceView().hasMore)

  // Counts describe the level being browsed, not the whole tree: a recursive count would mean
  // walking a repository on every open, which is what browsing exists to avoid.
  readonly caseFileCount = computed(() => this.caseView().totalEntries)
  readonly namespaceFileCount = computed(() => this.namespaceView().totalEntries)
  readonly fileCount = computed(() => this.caseFileCount() + this.namespaceFileCount())
  readonly canWriteCase = computed(
    () => this.caseView().capability === ExchangeDirectoryListingCapabilityEnum.READ_WRITE
  )
  readonly canWriteNamespace = computed(
    () => this.namespaceView().capability === ExchangeDirectoryListingCapabilityEnum.READ_WRITE
  )
  readonly caseSectionVisible = computed(() => this.caseView().status !== 'forbidden')
  readonly namespaceSectionVisible = computed(() => this.namespaceView().status !== 'forbidden')
  // Tracked per scope: an in-flight upload in one scope must not disable the other scope's upload.
  readonly caseUploading = signal(false)
  readonly namespaceUploading = signal(false)

  /** Reload the pages already opened, keeping directory refreshes and load-more consistent. */
  private loadDirectory(scope: ExchangeScope, id: string): Observable<ExchangeDirectoryListing> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const path = isCase ? this.casePath : this.namespacePath
    const lastPage = isCase ? this.casePage : this.namespacePage
    const loading = isCase ? this.caseLoadingMore : this.namespaceLoadingMore
    loading.set(lastPage > 0)
    const browse = (directory: string, page: number) =>
      isCase
        ? this.controller.browseCaseFilesExchange(id, directory, page)
        : this.controller.browseNamespaceFilesExchange(id, directory, page)
    return browse(path, 0).pipe(
      expand((listing, index) => (listing.hasMore && index < lastPage ? browse(path, index + 1) : EMPTY)),
      scan(
        (combined: ExchangeDirectoryListing | null, listing) => ({
          ...listing,
          entries: [...(combined?.entries ?? []), ...listing.entries],
        }),
        null
      ),
      filter(
        (listing): listing is ExchangeDirectoryListing =>
          listing !== null && (!listing.hasMore || listing.page >= lastPage)
      ),
      take(1),
      catchError((err: { status?: number }) => {
        if (err.status !== 404 || !path) return throwError(() => err)
        // A missing subdirectory is not a revoked scope. Check the authorized root before
        // restoring navigation; a genuine 403/404 still reaches the fail-closed error path.
        return browse('', 0).pipe(
          tap(() => {
            if (isCase) {
              this.casePath = ''
              this.casePage = 0
            } else {
              this.namespacePath = ''
              this.namespacePage = 0
            }
          })
        )
      })
    )
  }

  private loadScope(loader: () => Observable<ExchangeDirectoryListing>): Observable<ExchangeScopeView> {
    return loader().pipe(
      map((listing) => ({
        status: 'ready' as const,
        // The server already orders directories first, then names: preserve it rather than
        // re-sorting by date, which would interleave folders and files.
        entries: listing.entries ?? [],
        path: listing.path ?? '',
        totalEntries: listing.totalEntries ?? 0,
        hasMore: listing.hasMore ?? false,
        capability: listing.capability ?? ExchangeDirectoryListingCapabilityEnum.NONE,
      })),
      catchError((err: { status?: number }) => of(this.scopeError(err)))
    )
  }

  private scopeError(err: { status?: number }): ExchangeScopeView {
    return {
      ...NEUTRAL_LOADING,
      status: err?.status === 403 || err?.status === 404 ? 'forbidden' : 'error',
    }
  }

  /**
   * Build the reactive view signal for one scope: re-fetch its manifest on every `refresh$`,
   * map to the fail-closed [ExchangeScopeView] via [loadScope], expose as a signal.
   */
  private buildScopeView(
    refresh$: Observable<void>,
    getId: () => string | null,
    getPath: () => string,
    load: (id: string) => Observable<ExchangeScopeView>
  ): Signal<ExchangeScopeView> {
    let previousId: string | null = null
    let previousPath = ''
    let latestView = NEUTRAL_LOADING
    const view$ = refresh$.pipe(
      switchMap(() => {
        const id = getId()
        const path = getPath()
        // Updating the directory already on screen must not replace its DOM with a spinner:
        // that collapses the list height and loses the user's scroll position. A new scope or
        // folder still clears immediately; errors and revoked permissions remain fail-closed.
        const keepListing = id === previousId && path === previousPath && latestView.status === 'ready'
        previousId = id
        previousPath = path
        if (!id) return of(NEUTRAL_LOADING)
        const loaded = load(id)
        return keepListing ? loaded : loaded.pipe(startWith(NEUTRAL_LOADING))
      }),
      tap((view) => {
        latestView = view
        if (view.status === 'ready') previousPath = view.path
      }),
      shareReplay({ bufferSize: 1, refCount: true })
    )
    return toSignal(view$, { initialValue: NEUTRAL_LOADING })
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────────
  initializeForCase(namespaceId: string, caseId: string): void {
    if (this.namespaceId !== namespaceId) this.namespacePath = ''
    if (this.caseId !== caseId) this.casePath = ''
    this.namespaceId = namespaceId
    this.caseId = caseId
    this.refreshManifest()
  }

  /**
   * Namespace-only initialisation for composers rendered outside any case (home screen):
   * loads the namespace manifest so `canWriteNamespace()` gating works before a case exists.
   */
  initializeForNamespace(namespaceId: string): void {
    if (this.namespaceId !== namespaceId) this.namespacePath = ''
    this.namespaceId = namespaceId
    this.caseId = null
    this.casePath = ''
    this.casePage = 0
    this.namespacePage = 0
    this.caseRefresh$.next()
    this.namespaceRefresh$.next()
  }

  clear(): void {
    this.namespaceId = null
    this.caseId = null
    this.casePath = ''
    this.namespacePath = ''
    this.refreshManifest()
  }

  /**
   * Browse into a directory of the case scope, or back to a breadcrumb level.
   *
   * Passing the empty string returns to the scope root.
   */
  browseCase(path: string): void {
    this.casePath = path.replace(/^\/+|\/+$/g, '')
    this.casePage = 0
    this.caseRefresh$.next()
  }

  /** Browse into a directory of the namespace scope, or back to a breadcrumb level. */
  browseNamespace(path: string): void {
    this.namespacePath = path.replace(/^\/+|\/+$/g, '')
    this.namespacePage = 0
    this.namespaceRefresh$.next()
  }

  /** Refresh both scope manifests (user-driven actions, init). */
  refreshManifest(): void {
    this.casePage = 0
    this.namespacePage = 0
    this.caseRefresh$.next()
    this.namespaceRefresh$.next()
  }

  /** Refresh only the case manifest — used when the agent mutates the case scope. */
  refreshCase(): void {
    this.caseRefresh$.next()
  }

  /** Refresh only the namespace manifest — used when the agent mutates the namespace scope. */
  refreshNamespace(): void {
    this.namespaceRefresh$.next()
  }

  loadMore(scope: ExchangeScope): void {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const view = isCase ? this.caseView() : this.namespaceView()
    const loading = isCase ? this.caseLoadingMore : this.namespaceLoadingMore
    if (view.status !== 'ready' || !view.hasMore || loading()) return
    loading.set(true)
    if (isCase) {
      this.casePage++
      this.caseRefresh$.next()
    } else {
      this.namespacePage++
      this.namespaceRefresh$.next()
    }
  }

  // ── Reads ───────────────────────────────────────────────────────────────────
  getContent(scope: ExchangeScope, path: string): Observable<ExchangeFileContent> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const id = isCase ? this.caseId : this.namespaceId
    if (!id) return throwError(() => new Error('No active scope for content request'))
    return isCase
      ? this.controller.getCaseFileContentExchange(id, path)
      : this.controller.getNamespaceFileContentExchange(id, path)
  }

  // ── Writes (case + namespace, gated per scope by the server-computed capability) ──
  async uploadFile(scope: ExchangeScope, file: File): Promise<{ success: boolean; error?: string }> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const id = isCase ? this.caseId : this.namespaceId
    if (!id) return { success: false, error: 'No active scope' }
    const uploading = isCase ? this.caseUploading : this.namespaceUploading
    uploading.set(true)
    const upload$ = isCase
      ? this.controller.uploadCaseFileExchange(id, file)
      : this.controller.uploadNamespaceFileExchange(id, file)
    return new Promise((resolve) => {
      upload$.subscribe({
        next: () => {
          uploading.set(false)
          this.refreshManifest()
          resolve({ success: true })
        },
        error: (err: { status?: number; message?: string; error?: { message?: string } }) => {
          uploading.set(false)
          resolve({ success: false, error: this.uploadErrorMessage(err) })
        },
      })
    })
  }

  /** Maps an upload error response to a user-facing message (disallowed type, conflict, too large). */
  private uploadErrorMessage(err: { status?: number; message?: string; error?: { message?: string } }): string {
    const byStatus: Record<number, string | undefined> = {
      400: err?.error?.message ?? 'This file type is not allowed.',
      409: 'A file with this name already exists.',
      413: 'This file is too large.',
    }
    return (err?.status != null && byStatus[err.status]) || err?.message || 'Upload failed'
  }

  async deleteFile(scope: ExchangeScope, path: string): Promise<{ success: boolean; error?: string }> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const id = isCase ? this.caseId : this.namespaceId
    if (!id) return { success: false, error: 'No active scope' }
    const delete$ = isCase
      ? this.controller.deleteCaseFileExchange(id, path)
      : this.controller.deleteNamespaceFileExchange(id, path)
    return new Promise((resolve) => {
      delete$.subscribe({
        next: () => {
          this.refreshManifest()
          resolve({ success: true })
        },
        error: (err: { message?: string }) => resolve({ success: false, error: err?.message ?? 'Delete failed' }),
      })
    })
  }

  // ── Download ──────────────────────────────────────────────────────────────────
  downloadFile(scope: ExchangeScope, path: string): Promise<{ success: boolean; error?: string }> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const id = isCase ? this.caseId : this.namespaceId
    if (!id) return Promise.resolve({ success: false, error: 'No active scope' })
    return this.downloadFrom(scope, id, path)
  }

  private downloadFrom(scope: ExchangeScope, id: string, path: string): Promise<{ success: boolean; error?: string }> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const filename = path.split('/').pop() ?? path
    const download$ = isCase
      ? this.controller.downloadCaseFileExchange(id, path)
      : this.controller.downloadNamespaceFileExchange(id, path)
    // The generated method is typed Observable<string> but requests responseType:'blob'
    // (Accept '*/*'), so at runtime it yields a Blob.
    return new Promise((resolve) => {
      ;(download$ as unknown as Observable<Blob>).subscribe({
        next: (body) => {
          this.saveBlob(body, filename)
          resolve({ success: true })
        },
        error: (err: { message?: string }) => {
          console.error('[exchange] download failed', err)
          resolve({ success: false, error: err?.message ?? 'Download failed' })
        },
      })
    })
  }

  /**
   * Download every file of a scope, staggered ~300 ms apart (ported from legacy).
   * Returns the count of files that failed so the caller can surface a single summary error.
   */
  async downloadAll(scope: ExchangeScope): Promise<{ success: boolean; failedCount: number; error?: string }> {
    const isCase = scope === ExchangeFileEntryScopeEnum.CASE
    const id = isCase ? this.caseId : this.namespaceId
    if (!id) return { success: false, failedCount: 1, error: 'No active scope' }
    // Download-all retains the recursive manifest contract; browsing is only one directory.
    // Capture the scope id for the entire operation, even if the user navigates meanwhile.
    let manifest
    try {
      manifest = await firstValueFrom(
        isCase
          ? this.controller.getCaseFilesManifestExchange(id)
          : this.controller.getNamespaceFilesManifestExchange(id)
      )
    } catch {
      return { success: false, failedCount: 1, error: 'Could not list all files. No complete download was performed.' }
    }
    let failedCount = 0
    let index = 0
    for (const file of manifest.files) {
      if (index++ > 0) await new Promise((resolve) => setTimeout(resolve, 300))
      const result = await this.downloadFrom(scope, id, file.path)
      if (!result.success) failedCount++
    }
    return { success: failedCount === 0, failedCount }
  }

  private saveBlob(body: Blob | string, filename: string): void {
    const blob = body instanceof Blob ? body : new Blob([body])
    const url = window.URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    anchor.style.display = 'none'
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
    window.URL.revokeObjectURL(url)
  }
}
