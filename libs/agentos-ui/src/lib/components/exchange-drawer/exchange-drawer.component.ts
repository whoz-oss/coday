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
import { deletedFilesInDirectory, indexGitChanges } from '../../services/exchange-git-tree.utils'
import { EnvironmentScope, ExchangeEnvironment } from '../../services/exchange-environment.service'
import { ExchangeEnvironmentComponent } from '../exchange-environment/exchange-environment.component'
import { ExchangeDirectoryEntry, ExchangeFileEntry, ExchangeFileEntryScopeEnum } from '@whoz-oss/agentos-api-client'
import { filterFileTree, FileTreeRow, MAX_FILTER_MATCHES } from '../../services/exchange-file-filter.utils'
import { ExchangePathSegment } from '../../services/exchange-state.service'
import { EmptyStateComponent, IconButtonComponent, SpinnerComponent } from '@whoz-oss/design-system'
import { ExchangeFileRef, ExchangeScope, ExchangeScopeStatus } from '../../services/exchange-state.service'
import { ExchangeContentViewerComponent } from '../exchange-content-viewer/exchange-content-viewer.component'
import { ExchangeFileListComponent } from '../exchange-file-list/exchange-file-list.component'
import { ExchangeFolderNavComponent } from '../exchange-folder-nav/exchange-folder-nav.component'
import { ExchangeItemComponent, ExchangeFileRow } from '../exchange-item/exchange-item.component'

/** Pause after the last keystroke before the filter is applied, so typing does not re-render the tree each time. */
const FILTER_DEBOUNCE_MS = 150

/** Load status of the recursive namespace manifest that feeds the filter. `idle` while no filter is set. */
export type ExchangeManifestStatus = 'idle' | 'loading' | 'ready' | 'error'

/** A file the user checked (`referenced: true`) or unchecked (`false`) to put it in, or take it out of, the message. */
export interface ExchangeReferenceToggle {
  ref: ExchangeFileRef
  referenced: boolean
}

/** One line of the filtered namespace tree; files carry the row view-model of `agentos-exchange-item`. */
export interface FilteredTreeRow extends FileTreeRow {
  row?: ExchangeFileRow
}

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
 *
 * The namespace section can be filtered by name or path. Browsing lists one directory at a time, so the
 * filter works on the recursive manifest, which the parent loads on demand (`namespaceManifest`) when
 * `namespaceFilterChanged` reports a non-empty query. The query lives here, not in the parent, so it
 * survives opening the viewer (same component, swapped by @if) and coming back.
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
  /** Every namespace file at any depth, once loaded for the filter; null until then. */
  readonly namespaceManifest = input<ExchangeFileEntry[] | null>(null)
  readonly namespaceManifestStatus = input<ExchangeManifestStatus>('idle')

  // ── Current selection / viewer ────────────────────────────────────────────────
  readonly activeFile = input<ExchangeFileRef | null>(null)
  readonly selectedContent = input<string | null>(null)
  /** Object URL of the previewed image, when the selected file is one. */
  readonly imageUrl = input<string | null>(null)
  readonly contentStatus = input<'loading' | 'ready' | 'error'>('loading')
  readonly canViewSelected = input<boolean>(true)
  /** Offers a checkbox on the file rows and the "in message" bar. */
  readonly selectable = input<boolean>(false)
  /** Files the message being written references: a checkbox is checked exactly when its file is in this list. */
  readonly referenced = input<ExchangeFileRef[]>([])

  readonly fileSelected = output<ExchangeFileRef>()
  /** The user checked or unchecked a file; the parent owns the list and answers through `referenced`. */
  readonly referenceToggled = output<ExchangeReferenceToggle>()
  /** A directory was opened, or a breadcrumb level was clicked. Empty path means the scope root. */
  readonly folderOpened = output<{ scope: ExchangeScope; path: string }>()
  readonly loadMoreRequested = output<ExchangeScope>()
  readonly uploadRequested = output<{ scope: ExchangeScope; files: File[] }>()
  readonly downloadRequested = output<ExchangeFileRef>()
  readonly downloadAllRequested = output<ExchangeScope>()
  readonly deleteRequested = output<ExchangeFileRef>()
  readonly retryRequested = output<void>()
  /** The debounced namespace filter query changed (trimmed; empty when cleared). */
  readonly namespaceFilterChanged = output<string>()
  /** Loading the manifest for the filter failed and the user asked to try again. */
  readonly namespaceFilterRetry = output<void>()
  readonly closeRequested = output<void>()
  /** Emitted when the viewer's back button is pressed; the parent clears the active file. */
  readonly viewerClosed = output<void>()

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef)
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput')
  private filterTimer: ReturnType<typeof setTimeout> | undefined

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.filterTimer))
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

  // ── Namespace filter ────────────────────────────────────────────────────────────────────────────────
  /** What the input shows right now. */
  protected readonly filterInput = signal('')
  /** Debounced, trimmed query that actually drives the tree; empty means "browse". */
  protected readonly filterQuery = signal('')
  protected readonly filterActive = computed(() => this.filterQuery() !== '')
  protected readonly maxFilterMatches = MAX_FILTER_MATCHES

  /** Shown while the section has something to filter, and as long as a query is set (never trap the user in it). */
  protected readonly showNamespaceFilter = computed(
    () =>
      this.filterInput() !== '' ||
      this.namespaceFiles().length > 0 ||
      this.namespaceFolders().length > 0 ||
      this.namespaceBreadcrumb().length > 0
  )

  private readonly filteredTree = computed(() =>
    filterFileTree(
      (this.namespaceManifest() ?? []).map((file) => file.path),
      this.filterQuery()
    )
  )
  protected readonly filteredTruncated = computed(() => this.filteredTree().truncated)
  protected readonly noMatchTitle = computed(() => `No files match "${this.filterQuery()}"`)
  protected readonly filteredRows = computed<FilteredTreeRow[]>(() => {
    const byPath = new Map((this.namespaceManifest() ?? []).map((file) => [file.path, file]))
    return this.filteredTree().rows.map((node) => {
      if (node.kind === 'folder') return node
      const entry = byPath.get(node.path)
      return {
        ...node,
        row: ExchangeItemComponent.toRow({
          path: node.path,
          name: node.name,
          directory: false,
          size: entry?.size,
          lastModified: entry?.lastModified,
        }),
      }
    })
  })

  protected onFilterInput(event: Event): void {
    const value = (event.target as HTMLInputElement).value
    this.filterInput.set(value)
    clearTimeout(this.filterTimer)
    this.filterTimer = setTimeout(() => this.applyFilter(value), FILTER_DEBOUNCE_MS)
  }

  protected onFilterKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || this.filterInput() === '') return
    // Escape first clears the filter; it must not also close the panel behind it.
    event.stopPropagation()
    this.clearFilter()
  }

  /** Clearing is immediate (no debounce) and leaves the browse state exactly as it was. */
  protected clearFilter(): void {
    clearTimeout(this.filterTimer)
    this.filterInput.set('')
    this.applyFilter('')
  }

  /** A folder of the filtered tree was chosen: leave the filter and browse into it. */
  protected onFilteredFolderOpen(path: string): void {
    this.clearFilter()
    this.onFolderOpen(this.NAMESPACE, path)
  }

  // ── Referenced files (owned by the parent: the checkboxes only mirror them) ─────────────────────────────
  protected readonly referencedCount = computed(() => this.referenced().length)
  protected readonly referencedCasePaths = computed(() => this.referencedPathsOf(this.CASE))
  protected readonly referencedNamespacePaths = computed(() => this.referencedPathsOf(this.NAMESPACE))

  protected toggleReference(ref: ExchangeFileRef): void {
    const referenced = !this.referencedPathsOf(ref.scope).has(ref.path)
    this.referenceToggled.emit({ ref: { scope: ref.scope, path: ref.path }, referenced })
  }

  /** Asks the parent to take every referenced file out of the message. */
  protected clearReferences(): void {
    for (const ref of this.referenced()) this.referenceToggled.emit({ ref, referenced: false })
  }

  private referencedPathsOf(scope: ExchangeScope): ReadonlySet<string> {
    const paths = new Set<string>()
    for (const ref of this.referenced()) if (ref.scope === scope) paths.add(ref.path)
    return paths
  }

  protected isNamespaceFileActive(path: string): boolean {
    const active = this.activeFile()
    return active !== null && active.scope === this.NAMESPACE && active.path === path
  }

  private applyFilter(value: string): void {
    const query = value.trim()
    if (query === this.filterQuery()) return
    this.filterQuery.set(query)
    this.namespaceFilterChanged.emit(query)
  }

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
