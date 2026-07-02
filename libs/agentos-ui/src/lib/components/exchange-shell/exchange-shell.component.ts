import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  OnDestroy,
  OnInit,
  output,
  signal,
} from '@angular/core'
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
 * Owns the ExchangeStateService lifecycle (initializeForCase / clear from route params),
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
export class ExchangeShellComponent implements OnInit, OnDestroy {
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

  ngOnInit(): void {
    this.route.params.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      const { namespaceId, caseId } = this.resolveRouteIds()
      if (namespaceId && caseId) {
        this.resetSelection()
        this.state.initializeForCase(namespaceId, caseId)
      } else {
        this.state.clear()
      }
    })
  }

  /**
   * Resolve namespaceId + caseId by walking up the route hierarchy.
   * `namespaceId` is declared on a parent route (`:namespaceId/cases`) while
   * `caseId` is on the `:caseId` child. Under the router's default
   * `paramsInheritanceStrategy: 'emptyOnly'`, the `:caseId` route does NOT
   * inherit the parent's `namespaceId`, so reading `this.route.params` alone
   * misses it. Walking up the `parent` chain collects both reliably.
   */
  private resolveRouteIds(): { namespaceId?: string; caseId?: string } {
    let r: ActivatedRoute | null = this.route
    let namespaceId: string | undefined
    let caseId: string | undefined
    while (r) {
      namespaceId = namespaceId ?? r.snapshot.paramMap.get('namespaceId') ?? undefined
      caseId = caseId ?? r.snapshot.paramMap.get('caseId') ?? undefined
      r = r.parent
    }
    return { namespaceId, caseId }
  }

  ngOnDestroy(): void {
    this.state.clear()
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
    for (const file of payload.files) {
      const result = await this.state.uploadFile(payload.scope, file)
      if (!result.success) {
        this.actionError.set(result.error ?? 'Upload failed')
        break
      }
    }
  }

  // ── Download ──────────────────────────────────────────────────────────────────
  protected onDownload(ref: ExchangeFileRef): void {
    this.state.downloadFile(ref.scope, ref.path)
  }

  protected onDownloadAll(scope: ExchangeScope): void {
    this.state.downloadAll(scope)
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
