import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { deletedFilesInDirectory, indexGitChanges } from '../../services/exchange-git-tree.utils'
import { EnvironmentScope, ExchangeEnvironment } from '../../services/exchange-environment.service'
import { ExchangeEnvironmentComponent } from '../exchange-environment/exchange-environment.component'
import { ExchangeDirectoryEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangePathSegment } from '../../services/exchange-state.service'
import { EmptyStateComponent, IconButtonComponent, SpinnerComponent } from '@whoz-oss/design-system'
import { ExchangeFileRef, ExchangeScope, ExchangeScopeStatus } from '../../services/exchange-state.service'
import { ExchangeContentViewerComponent } from '../exchange-content-viewer/exchange-content-viewer.component'
import { ExchangeFileListComponent } from '../exchange-file-list/exchange-file-list.component'
import { ExchangeItemComponent } from '../exchange-item/exchange-item.component'

/**
 * ExchangeDrawerComponent — presentational panel content for the file-exchange drawer.
 *
 * Two scopes are rendered as sections:
 *   - Case files      — read + (when canWriteCase) upload / delete.
 *   - Namespace files — read + (when canWriteNamespace, i.e. namespace admin) upload / delete.
 *
 * Selecting a file swaps the list for the content viewer (narrow drawer); the viewer's back
 * button returns to the lists. A section is hidden entirely when not visible (forbidden →
 * zero disclosure). Per-section states: loading / preparing (spinner and explanation) / error (retry banner) / empty
 * (empty-state) / ready (list).
 *
 * Kept presentational, OnPush, I/O in signals (decision #8).
 */
@Component({
  selector: 'agentos-exchange-drawer',
  standalone: true,
  imports: [
    ExchangeEnvironmentComponent,
    IconButtonComponent,
    SpinnerComponent,
    EmptyStateComponent,
    ExchangeFileListComponent,
    ExchangeContentViewerComponent,
  ],
  templateUrl: './exchange-drawer.component.html',
  styleUrl: './exchange-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExchangeDrawerComponent {
  readonly caseId = input<string | null>(null)
  readonly environmentActive = input(false)
  // ── Case scope ──────────────────────────────────────────────────────────────
  readonly caseFiles = input<ExchangeDirectoryEntry[]>([])
  readonly caseFolders = input<ExchangeDirectoryEntry[]>([])
  readonly caseBreadcrumb = input<ExchangePathSegment[]>([])
  readonly caseHasMore = input<boolean>(false)
  readonly caseLoadingMore = input<boolean>(false)
  readonly caseStatus = input.required<ExchangeScopeStatus>()
  readonly caseSectionVisible = input<boolean>(false)
  readonly canWriteCase = input<boolean>(false)
  readonly caseUploading = input<boolean>(false)

  // ── Namespace scope ──────────────────────────────────────────────────────────
  readonly namespaceFiles = input<ExchangeDirectoryEntry[]>([])
  readonly namespaceFolders = input<ExchangeDirectoryEntry[]>([])
  readonly namespaceBreadcrumb = input<ExchangePathSegment[]>([])
  readonly namespaceHasMore = input<boolean>(false)
  readonly namespaceLoadingMore = input<boolean>(false)
  readonly namespaceStatus = input.required<ExchangeScopeStatus>()
  readonly namespaceSectionVisible = input<boolean>(false)
  readonly canWriteNamespace = input<boolean>(false)
  readonly namespaceUploading = input<boolean>(false)

  // ── Current selection / viewer ────────────────────────────────────────────────
  readonly activeFile = input<ExchangeFileRef | null>(null)
  readonly selectedContent = input<string | null>(null)
  readonly contentStatus = input<'loading' | 'ready' | 'error'>('loading')
  readonly canViewSelected = input<boolean>(true)

  readonly fileSelected = output<ExchangeFileRef>()
  /** A directory was opened, or a breadcrumb level was clicked. Empty path means the scope root. */
  readonly folderOpened = output<{ scope: ExchangeScope; path: string }>()
  readonly loadMoreRequested = output<ExchangeScope>()
  readonly uploadRequested = output<{ scope: ExchangeScope; files: File[] }>()
  readonly downloadRequested = output<ExchangeFileRef>()
  readonly downloadAllRequested = output<ExchangeScope>()
  readonly deleteRequested = output<ExchangeFileRef>()
  readonly retryRequested = output<void>()
  readonly closeRequested = output<void>()
  /** Emitted when the viewer's back button is pressed; the parent clears the active file. */
  readonly viewerClosed = output<void>()

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput')

  /** Scope enums exposed to the template. */
  protected readonly CASE = ExchangeFileEntryScopeEnum.CASE
  protected readonly NAMESPACE = ExchangeFileEntryScopeEnum.NAMESPACE

  private readonly environmentPanel = viewChild(ExchangeEnvironmentComponent)
  private readonly environment = signal<{ scope: EnvironmentScope; view: ExchangeEnvironment | null } | null>(null)
  protected onEnvironmentChanged(snapshot: { scope: EnvironmentScope; view: ExchangeEnvironment | null }): void {
    this.environment.set(snapshot)
  }
  private readonly changes = computed(() => {
    const snapshot = this.environment()
    if (snapshot?.scope.id !== this.caseId() || this.caseStatus() !== 'ready') return []
    const view = snapshot?.view
    return view?.equipped && view.status === 'READY' && !view.error ? (view.changes?.files ?? []) : []
  })
  protected readonly gitTree = computed(() => indexGitChanges(this.changes()))
  protected readonly caseRows = computed(() => {
    const rows = this.caseFiles().map((file) => ({
      ...ExchangeItemComponent.toRow(file),
      gitStatus: this.gitTree().files.get(file.path)?.status,
      missing: false,
    }))
    const directory = this.caseBreadcrumb().at(-1)?.path ?? ''
    const deleted = deletedFilesInDirectory(
      this.changes(),
      directory,
      this.caseFiles().map((f) => f.path),
      this.caseFolders().map((f) => f.path)
    ).map((file) => ({
      path: file.path,
      filename: file.name,
      meta: 'Deleted · view diff',
      icon: 'description',
      gitStatus: file.status,
      missing: true,
    }))
    return [...rows, ...deleted].sort((a, b) => a.filename.localeCompare(b.filename))
  })
  protected openFileDiff(ref: ExchangeFileRef): void {
    if (ref.scope === this.CASE && ref.path.startsWith('repo/')) {
      this.environmentPanel()?.openDiff(ref.path.slice('repo/'.length))
    }
  }

  protected onFolderOpen(scope: ExchangeScope, path: string): void {
    this.folderOpened.emit({ scope, path })
  }
  protected readonly namespaceRows = computed(() => this.namespaceFiles().map(ExchangeItemComponent.toRow))

  /** Path of the row that opened the viewer — focus returns to it on back (a11y). */
  private lastSelectedPath: string | null = null

  protected onFileSelected(ref: ExchangeFileRef): void {
    // Cache the path, not the element: opening the viewer destroys the list, so the original row
    // button becomes a detached node — focusing it on back is a no-op. Re-query the fresh row instead.
    this.lastSelectedPath = ref.path
    this.fileSelected.emit(ref)
  }

  protected onViewerClose(): void {
    this.viewerClosed.emit()
    const path = this.lastSelectedPath
    if (!path) return
    // Restore focus to the freshly re-rendered row once the list is back (matched by path).
    queueMicrotask(() => {
      const rows = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-exchange-path]')
      for (const el of Array.from(rows)) {
        if (el.getAttribute('data-exchange-path') === path) {
          el.focus()
          break
        }
      }
    })
  }

  /** Scope of the section whose upload button was last clicked (one hidden input is shared). */
  private pendingUploadScope: ExchangeScope = ExchangeFileEntryScopeEnum.CASE

  protected triggerUpload(scope: ExchangeScope): void {
    this.pendingUploadScope = scope
    this.fileInput()?.nativeElement.click()
  }

  protected onFileInputChange(event: Event): void {
    const input = event.target as HTMLInputElement
    const files = input.files ? Array.from(input.files) : []
    if (files.length > 0) {
      this.uploadRequested.emit({ scope: this.pendingUploadScope, files })
    }
    // Reset so re-selecting the same file fires (change) again.
    input.value = ''
  }
}
