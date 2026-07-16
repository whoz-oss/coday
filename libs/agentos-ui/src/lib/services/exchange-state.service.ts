import { computed, inject, Injectable, Signal, signal } from '@angular/core'
import { toSignal } from '@angular/core/rxjs-interop'
import {
  ExchangeControllerService,
  ExchangeFileContent,
  ExchangeFileEntry,
  ExchangeFileEntryScopeEnum,
  ExchangeManifest,
  ExchangeManifestCapabilityEnum,
} from '@whoz-oss/agentos-api-client'
import { BehaviorSubject, catchError, map, Observable, of, shareReplay, switchMap, throwError } from 'rxjs'

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
  files: ExchangeFileEntry[]
  capability: ExchangeManifestCapabilityEnum
}

const NEUTRAL_LOADING: ExchangeScopeView = {
  status: 'loading',
  files: [],
  capability: ExchangeManifestCapabilityEnum.NONE,
}

function sortByDateDesc(files: ExchangeFileEntry[]): ExchangeFileEntry[] {
  return [...files].sort((a, b) => new Date(b.lastModified).getTime() - new Date(a.lastModified).getTime())
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
 * Never injects HttpClient for the listing — only the generated `ExchangeControllerService`.
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

  private readonly caseView = this.buildScopeView(
    this.caseRefresh$,
    () => this.caseId,
    (id) => this.controller.getCaseFilesManifestExchange(id)
  )
  private readonly namespaceView = this.buildScopeView(
    this.namespaceRefresh$,
    () => this.namespaceId,
    (id) => this.controller.getNamespaceFilesManifestExchange(id)
  )

  // ── Public derived state (always from the manifest, never inferred from raw roles) ──
  readonly caseStatus = computed(() => this.caseView().status)
  readonly namespaceStatus = computed(() => this.namespaceView().status)
  readonly caseFiles = computed(() => this.caseView().files)
  readonly namespaceFiles = computed(() => this.namespaceView().files)
  readonly caseFileCount = computed(() => this.caseFiles().length)
  readonly namespaceFileCount = computed(() => this.namespaceFiles().length)
  readonly fileCount = computed(() => this.caseFileCount() + this.namespaceFileCount())
  readonly canWriteCase = computed(() => this.caseView().capability === ExchangeManifestCapabilityEnum.READ_WRITE)
  readonly canWriteNamespace = computed(
    () => this.namespaceView().capability === ExchangeManifestCapabilityEnum.READ_WRITE
  )
  readonly caseSectionVisible = computed(() => this.caseView().status !== 'forbidden')
  readonly namespaceSectionVisible = computed(() => this.namespaceView().status !== 'forbidden')
  // Tracked per scope: an in-flight upload in one scope must not disable the other scope's upload.
  readonly caseUploading = signal(false)
  readonly namespaceUploading = signal(false)

  private loadScope(loader: () => Observable<ExchangeManifest>): Observable<ExchangeScopeView> {
    return loader().pipe(
      map((manifest) => ({
        status: 'ready' as const,
        files: sortByDateDesc(manifest.files ?? []),
        capability: manifest.capability ?? ExchangeManifestCapabilityEnum.NONE,
      })),
      catchError((err: { status?: number }) => {
        const forbidden = err?.status === 403 || err?.status === 404
        return of({
          status: forbidden ? ('forbidden' as const) : ('error' as const),
          files: [] as ExchangeFileEntry[],
          // fail-closed: no write action is ever derived from an error state.
          capability: ExchangeManifestCapabilityEnum.NONE,
        })
      })
    )
  }

  /**
   * Build the reactive view signal for one scope: re-fetch its manifest on every `refresh$`,
   * map to the fail-closed [ExchangeScopeView] via [loadScope], expose as a signal.
   */
  private buildScopeView(
    refresh$: Observable<void>,
    getId: () => string | null,
    load: (id: string) => Observable<ExchangeManifest>
  ): Signal<ExchangeScopeView> {
    const view$ = refresh$.pipe(
      switchMap(() => {
        const id = getId()
        return id ? this.loadScope(() => load(id)) : of(NEUTRAL_LOADING)
      }),
      shareReplay({ bufferSize: 1, refCount: true })
    )
    return toSignal(view$, { initialValue: NEUTRAL_LOADING })
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────────
  initializeForCase(namespaceId: string, caseId: string): void {
    this.namespaceId = namespaceId
    this.caseId = caseId
    this.refreshManifest()
  }

  clear(): void {
    this.namespaceId = null
    this.caseId = null
    this.refreshManifest()
  }

  /** Refresh both scope manifests (user-driven actions, init). */
  refreshManifest(): void {
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
          console.warn('[exchange] download failed', err)
          resolve({ success: false, error: err?.message ?? 'Download failed' })
        },
      })
    })
  }

  /**
   * Download every file of a scope, staggered ~300 ms apart (ported from legacy).
   * Returns the count of files that failed so the caller can surface a single summary error.
   */
  async downloadAll(scope: ExchangeScope): Promise<{ success: boolean; failedCount: number }> {
    const files = scope === ExchangeFileEntryScopeEnum.CASE ? this.caseFiles() : this.namespaceFiles()
    let failedCount = 0
    let index = 0
    for (const file of files) {
      if (index++ > 0) await new Promise((resolve) => setTimeout(resolve, 300))
      const result = await this.downloadFile(scope, file.path)
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
