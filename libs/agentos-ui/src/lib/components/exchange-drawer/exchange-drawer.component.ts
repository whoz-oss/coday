import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { filterFileTree, MAX_FILTER_MATCHES } from '../../services/exchange-file-filter.utils'
import { deletedFilesInDirectory, indexGitChanges } from '../../services/exchange-git-tree.utils'
import { EnvironmentScope, ExchangeEnvironment } from '../../services/exchange-environment.service'
import { ExchangeEnvironmentComponent } from '../exchange-environment/exchange-environment.component'
import { ExchangeDirectoryEntry, ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { ExchangePathSegment } from '../../services/exchange-state.service'
import { EmptyStateComponent, IconButtonComponent, SpinnerComponent } from '@whoz-oss/design-system'
import { ExchangeFileRef, ExchangeScope, ExchangeScopeStatus } from '../../services/exchange-state.service'
import { ExchangeContentViewerComponent } from '../exchange-content-viewer/exchange-content-viewer.component'
import { ExchangeFileListComponent } from '../exchange-file-list/exchange-file-list.component'
import { ExchangeFolderNavComponent } from '../exchange-folder-nav/exchange-folder-nav.component'
import { ExchangeFileRow, ExchangeItemComponent } from '../exchange-item/exchange-item.component'
import { FilteredFileTree } from '../../services/exchange-file-filter.utils'

export type ExchangeManifestStatus = 'idle' | 'loading' | 'ready' | 'error'

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
    ExchangeFolderNavComponent,
    ExchangeContentViewerComponent,
    ExchangeItemComponent,
    NgTemplateOutlet,
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
  readonly caseManifest = input<ExchangeFileEntry[] | null>(null)
  readonly namespaceManifest = input<ExchangeFileEntry[] | null>(null)
  readonly caseManifestStatus = input<ExchangeManifestStatus>('idle')
  readonly namespaceManifestStatus = input<ExchangeManifestStatus>('idle')

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
  readonly filterChanged = output<{ scope: ExchangeScope; query: string }>()
  readonly filterRetry = output<ExchangeScope>()
  readonly closeRequested = output<void>()
  /** Emitted when the viewer's back button is pressed; the parent clears the active file. */
  readonly viewerClosed = output<void>()

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput')
  private readonly timers = new Map<ExchangeScope, ReturnType<typeof setTimeout>>()
  protected readonly caseInput = signal('')
  protected readonly namespaceInput = signal('')
  protected readonly caseQuery = signal('')
  protected readonly namespaceQuery = signal('')
  protected readonly maxFilterMatches = MAX_FILTER_MATCHES
  protected readonly caseTree = computed(() => this.tree(this.caseManifest(), this.caseQuery(), true))
  protected readonly namespaceTree = computed(() => this.tree(this.namespaceManifest(), this.namespaceQuery(), false))

  constructor() {
    inject(DestroyRef).onDestroy(() => this.timers.forEach((timer) => clearTimeout(timer)))
  }

  private tree(
    files: ExchangeFileEntry[] | null,
    query: string,
    isCase: boolean
  ): Omit<FilteredFileTree, 'rows'> & { rows: Array<FilteredFileTree['rows'][number] & { row?: ExchangeFileRow }> } {
    const byPath = new Map((files ?? []).map((file) => [file.path.replace(/^\/+/, ''), file]))
    const tree = filterFileTree([...byPath.keys()], query)
    return {
      ...tree,
      rows: tree.rows.map((node) => {
        if (node.kind === 'folder') return { ...node, row: undefined }
        const file = byPath.get(node.path)
        return {
          ...node,
          row: {
            ...ExchangeItemComponent.toRow({
              path: node.path,
              name: node.name,
              directory: false,
              size: file?.size,
              lastModified: file?.lastModified,
            }),
            gitStatus: isCase ? this.gitTree().files.get(node.path)?.status : undefined,
          },
        }
      }),
    }
  }

  protected onFilterInput(scope: ExchangeScope, event: Event): void {
    const value = (event.target as HTMLInputElement).value
    const current = scope === this.CASE ? this.caseInput : this.namespaceInput
    current.set(value)
    clearTimeout(this.timers.get(scope))
    if (!value.trim()) {
      this.clearFilter(scope)
      return
    }
    this.timers.set(scope, setTimeout(() => this.applyFilter(scope, value), 150))
  }

  protected onFilterKeydown(scope: ExchangeScope, event: KeyboardEvent): void {
    if (event.key !== 'Escape' || !(scope === this.CASE ? this.caseInput() : this.namespaceInput())) return
    event.stopPropagation()
    event.preventDefault()
    this.clearFilter(scope)
  }

  protected clearFilter(scope: ExchangeScope): void {
    clearTimeout(this.timers.get(scope))
    ;(scope === this.CASE ? this.caseInput : this.namespaceInput).set('')
    this.applyFilter(scope, '')
  }

  private applyFilter(scope: ExchangeScope, value: string): void {
    const query = value.trim()
    const current = scope === this.CASE ? this.caseQuery : this.namespaceQuery
    if (query === current()) return
    current.set(query)
    this.filterChanged.emit({ scope, query })
  }

  protected openFilteredFolder(scope: ExchangeScope, path: string): void {
    this.clearFilter(scope)
    this.onFolderOpen(scope, path)
  }

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
    // Keep the server's order unless deleted files have to be placed among the others.
    return deleted.length ? [...rows, ...deleted].sort((a, b) => a.filename.localeCompare(b.filename)) : rows
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
