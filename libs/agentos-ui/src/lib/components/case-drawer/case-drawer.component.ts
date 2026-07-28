import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  TemplateRef,
  viewChild,
  ViewChild,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { CaseStatusGlyphComponent } from '../case-status-glyph/case-status-glyph.component'
import { Case, CaseStatusEventStatusEnum } from '@whoz-oss/agentos-api-client'
import { CaseItemComponent, CaseListItem } from '../case-item/case-item.component'

/**
 * A case list item extended with a recursive children list (parent/child cases).
 * Only root nodes are passed to ds-entity-list; children are rendered inline by the
 * itemTemplate. Inherits `favorite`, `canDelete` and `canRename` from [CaseListItem] so each
 * row can render the star toggle and the (ADMIN-gated) rename and delete actions.
 */
export interface CaseTreeItem extends CaseListItem {
  children: CaseTreeItem[]
  /** Raw case status — used for the compact-mode status glyph. */
  status: string
}

/**
 * CaseDrawerComponent — presentational drawer for the case list.
 *
 * Uses ds-entity-list for the chrome (toolbar, search, create button).
 *
 * Two display modes:
 * - **Tree mode** (no active search): root cases with expandable sub-cases; favorited
 *   cases are promoted to a "Favorites" group at the top.
 * - **Flat mode** (search active): all matching cases at every depth level are shown as a
 *   flat list. Matching is done on title AND case ID.
 *
 * Compact mode (compact input = true):
 * - Hides the ds-entity-list chrome entirely
 * - Shows only a vertical list of case-initials badges
 */
@Component({
  selector: 'agentos-case-drawer',
  imports: [NgTemplateOutlet, CaseStatusGlyphComponent],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)
  readonly compact = input<boolean>(false)
  /** External filter query (from the sidebar search input). Overrides internal search when provided. */
  readonly filterQuery = input<string>('')

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()
  readonly renameRequested = output<{ id: string; title: string }>()

  @ViewChild('caseRowTpl', { static: true }) caseRowTpl!: TemplateRef<{ node: CaseTreeItem; depth: number }>

  /** Current search query, driven by ds-entity-list's searchChanged output. */
  protected readonly searchQuery = signal('')

  /** Active query: external filterQuery takes priority over internal search. */
  protected readonly activeQuery = computed(() => this.filterQuery().trim() || this.searchQuery().trim())

  protected readonly isSearchActive = computed(() => this.activeQuery().length > 0)

  /**
   * Tree rebuilt automatically when cases() changes.
   * Favorited cases are promoted to a "Pinned" group at the top;
   * remaining cases are grouped by recency (Today / Yesterday / Previous 7 days / Older).
   */
  protected readonly rootItems = computed(() => {
    const cases = this.cases()
    const modifiedAt = new Map(cases.map((c) => [c.id ?? '', c.modified ?? c.created ?? '']))
    return promoteFavoritesAndGroup(buildTree(cases), modifiedAt)
  })

  /**
   * Root items enriched with initials for the compact rail.
   * When search is active: flat filtered results. Otherwise: date/favorites groups.
   */
  protected readonly compactItems = computed(() => {
    const source = this.isSearchActive() ? this.flatFilteredItems() : this.rootItems()
    return source.map((item, i, arr) => ({
      ...item,
      initials: getInitials(item.name),
      /** True if this item is the first of its group in the compact rail. */
      isFirstInCompactGroup: i === 0 || item.groupKey !== arr[i - 1]?.groupKey,
    }))
  })

  /**
   * Flat list of all cases matching the current search query.
   * Traverses the full tree at every depth level.
   * Matching is done on title AND case ID (case-insensitive).
   * Used in flat mode (search active).
   */
  protected readonly flatFilteredItems = computed((): CaseTreeItem[] => {
    const query = this.activeQuery().toLowerCase()
    if (!query) return []
    return collectMatching(this.rootItems(), query)
  })

  /**
   * Items passed to ds-entity-list:
   * - flat filtered list when search is active
   * - root tree nodes otherwise
   */
  protected readonly displayItems = computed((): CaseTreeItem[] =>
    this.isSearchActive() ? this.flatFilteredItems() : this.rootItems()
  )

  /**
   * Ancestors of the active case to auto-expand, recalculated when
   * rootItems() or activeCaseId() changes.
   */
  private readonly autoExpandedIds = computed(() => {
    const id = this.activeCaseId()
    if (!id) return new Set<string>()
    const expanded = new Set<string>()
    findAndCollectAncestors(this.rootItems(), id, expanded)
    return expanded
  })

  /**
   * Explicit user expand/collapse choices (true = expanded, false = collapsed). An entry here
   * overrides the auto-expanded default, so a user CAN collapse an ancestor of the active case
   * (which [autoExpandedIds] would otherwise force open).
   */
  // ---------------------------------------------------------------------------
  // Compact tooltip (position: fixed — escapes the rail's overflow:hidden)
  // ---------------------------------------------------------------------------

  /** Currently hovered item in compact mode — null when tooltip is hidden. */
  protected readonly hoveredCompactItem = signal<
    (typeof this.compactItems extends () => (infer T)[] ? T : never) | null
  >(null)

  /** Fixed tooltip position, computed from the hovered badge's bounding rect. */
  protected readonly tooltipPos = signal<{ top: number; left: number }>({ top: 0, left: 0 })

  /** Case id pending delete confirmation in the compact tooltip. */
  protected readonly compactConfirmingDeleteId = signal<string | null>(null)

  /** Case id pending delete confirmation in expanded mode. */
  protected readonly expandedConfirmingDeleteId = signal<string | null>(null)

  protected onCompactBadgeEnter(event: MouseEvent, item: ReturnType<typeof this.compactItems>[number]): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    this.tooltipPos.set({ top: rect.top + rect.height / 2, left: rect.right + 8 })
    this.hoveredCompactItem.set(item)
    // Reset confirmation when hovering a different case
    if (this.compactConfirmingDeleteId() !== item.id) {
      this.compactConfirmingDeleteId.set(null)
    }
  }

  protected onCompactBadgeLeave(event: MouseEvent): void {
    // The CSS bridge (::before) covers the gap — also check via relatedTarget
    const related = event.relatedTarget as HTMLElement | null
    if (related?.closest('.case-drawer-compact__tooltip-fixed')) return
    // Small delay to let the mouse cross the gap between badge and tooltip
    setTimeout(() => {
      if (!this._mouseOnTooltip) this.hoveredCompactItem.set(null)
    }, 80)
  }

  protected onTooltipLeave(event: MouseEvent): void {
    this._mouseOnTooltip = false
    const related = event.relatedTarget as HTMLElement | null
    if (related?.closest('.case-drawer-compact__badge')) return
    this.hoveredCompactItem.set(null)
  }

  protected onTooltipEnter(): void {
    this._mouseOnTooltip = true
  }

  private _mouseOnTooltip = false

  protected readonly expandOverrides = signal(new Map<string, boolean>())

  // ---------------------------------------------------------------------------
  // Inline rename
  // ---------------------------------------------------------------------------

  /**
   * Case currently renamed inline, keyed by ID rather than by node: [rootItems] rebuilds every
   * tree node whenever cases() changes (which the optimistic title patch does), so a node
   * reference would go stale mid-edit.
   */
  protected readonly editingCaseId = signal<string | null>(null)

  /** Draft title being typed. */
  protected readonly editingTitle = signal('')

  /** Validation message shown under the input, null while the draft is valid. */
  protected readonly renameError = signal<string | null>(null)

  /** Title the editor was seeded with, used to skip a no-op rename. */
  private renameOriginal = ''

  /**
   * A case appears on exactly one row, so one edited case means one input and a singular query
   * is enough. That holds only because the row template does not recurse into children while a
   * search is active, where the flattened list already contains every matching descendant.
   */
  private readonly renameInput = viewChild<ElementRef<HTMLInputElement>>('renameInput')

  /** Case whose draft has already been pre-selected, so a re-render does not re-select it. */
  private selectedForId: string | null = null

  constructor() {
    afterRenderEffect((onCleanup) => {
      const id = this.editingCaseId()
      if (!id) return

      const input = this.renameInput()?.nativeElement
      if (!input) {
        // The edited row is no longer rendered: the search filter hid it, compact mode replaced
        // the tree by the badge rail, the case was deleted, or an ancestor collapsed because the
        // active case changed. Angular destroys the view without firing (blur), so the draft
        // would otherwise survive and the editor would re-open on stale state later.
        this.cancelRename()
        return
      }

      // setTimeout rather than a microtask: the rename button lives in the row's hover actions,
      // and with zone.js the tick runs synchronously between the listeners of a single click
      // dispatch. Only a macrotask is guaranteed to land after the whole dispatch has settled.
      const timer = setTimeout(() => {
        input.focus()
        // Pre-select on a genuine open only. The row's view is recreated whenever the tree
        // regroups (starring another case moves rows between groups), and re-selecting there
        // would make the next keystroke wipe a half-typed draft.
        if (this.selectedForId !== id) {
          input.select()
          this.selectedForId = id
        }
      })
      onCleanup(() => clearTimeout(timer))
    })
  }

  protected isExpanded(id: string): boolean {
    return this.expandOverrides().get(id) ?? this.autoExpandedIds().has(id)
  }

  protected toggleExpand(event: Event, id: string): void {
    event.stopPropagation()
    const nextExpanded = !this.isExpanded(id)
    this.expandOverrides.update((map) => {
      const next = new Map(map)
      next.set(id, nextExpanded)
      return next
    })
  }

  /** Called from ShellSidebarComponent’s search input via the filterQuery input. */
  protected onSearchChanged(query: string): void {
    this.searchQuery.set(query)
  }

  /**
   * Returns true if this item is the first in its group (so we render the group label above it).
   * Compares groupKey with the previous item in the flat display list.
   */
  protected isFirstInGroup(item: CaseTreeItem, list: CaseTreeItem[]): boolean {
    const idx = list.indexOf(item)
    if (idx === 0) return !!item.groupKey
    return item.groupKey !== list[idx - 1]?.groupKey
  }

  protected onItemSelected(id: string): void {
    this.caseSelected.emit(id)
  }

  protected onCreateRequested(): void {
    this.createRequested.emit()
  }

  protected onDeleteRequested(id: string): void {
    this.deleteRequested.emit(id)
  }

  /** Open the inline editor on a row, seeded with its current label. */
  protected startRename(node: CaseTreeItem): void {
    this.renameOriginal = node.name
    this.editingTitle.set(node.name)
    this.renameError.set(null)
    this.selectedForId = null
    this.editingCaseId.set(node.id)
  }

  protected onRenameInput(value: string): void {
    this.editingTitle.set(value)
    if (this.renameError()) this.renameError.set(null)
  }

  protected onRenameKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') {
      event.preventDefault()
      event.stopPropagation()
      this.commitRename()
    } else if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      this.cancelRename()
    }
  }

  /**
   * Blur commits a valid draft and discards an invalid one: leaving the row stuck in edit mode,
   * showing an error under an unfocused input, would be worse. Enter is the path that surfaces
   * the error and keeps the focus so the user can fix the draft.
   *
   * Bails out when the blur came from the whole window being deactivated (alt-tab, browser
   * chrome), which would otherwise persist a half-typed name. That case is told apart by the
   * input still being document.activeElement: a real focus move updates it, a window
   * deactivation does not. The browser refocuses the input on return, so the edit carries on.
   */
  protected onRenameBlur(): void {
    if (!this.editingCaseId()) return
    if (this.renameInput()?.nativeElement === document.activeElement) return
    if (caseTitleError(this.editingTitle().trim())) this.cancelRename()
    else this.commitRename()
  }

  /**
   * Validate the draft and emit the rename. Idempotent by construction: the first call clears
   * [editingCaseId], so the blur that follows an Enter (or a second Enter) is a no-op.
   */
  protected commitRename(): void {
    const id = this.editingCaseId()
    if (!id) return
    const title = this.editingTitle().trim()
    const error = caseTitleError(title)
    if (error) {
      this.renameError.set(error)
      return
    }
    this.editingCaseId.set(null)
    this.renameError.set(null)
    // Unchanged title: nothing to persist, so no request.
    if (title === this.renameOriginal) return
    this.renameRequested.emit({ id, title })
  }

  protected cancelRename(): void {
    this.editingCaseId.set(null)
    this.editingTitle.set('')
    this.renameError.set(null)
  }

  /**
   * @internal Used by tests via component['menuItemsFor']
   * Kept for backward-compat with existing specs.
   */
  protected menuItemsFor(node: CaseTreeItem): Array<{ key: string; label: string; variant?: string }> {
    const items: Array<{ key: string; label: string; variant?: string }> = [
      { key: 'star', label: node.favorite ? 'Remove from favorites' : 'Add to favorites' },
    ]
    if (node.canDelete) {
      items.push({ key: 'delete', label: 'Delete', variant: 'danger' })
    }
    return items
  }

  /**
   * @internal Used by tests via component['onMenuAction']
   * Kept for backward-compat with existing specs.
   */
  protected onMenuAction(node: CaseTreeItem, action: string): void {
    if (action === 'star') this.onStarToggled(node)
    if (action === 'delete') this.onDeleteRequested(node.id)
  }

  protected onStarToggled(item: CaseListItem): void {
    // Emit the desired state; CaseStateService applies the optimistic flip on the list signal,
    // which rebuilds the tree (and re-groups favorites) reactively.
    this.starToggled.emit({ id: item.id, starred: !item.favorite })
  }

  // Exposed to the template for use in @switch / [class] bindings
  protected readonly Status = CaseStatusEventStatusEnum
}

/** Longest title a user may set, matching the legacy thread rename input. */
const MAX_CASE_TITLE_LENGTH = 200

/**
 * Validation message for an already-trimmed case title, or null when it is valid.
 *
 * Empty is rejected outright: the server accepts it (PUT /api/cases/{id} does
 * `resource.title ?: existing.title`, a null-guard only) and a blank title makes it re-trigger
 * LLM auto-naming on every subsequent message.
 */
function caseTitleError(title: string): string | null {
  if (!title) return 'Name cannot be empty'
  if (title.length > MAX_CASE_TITLE_LENGTH) return `Name is too long (max ${MAX_CASE_TITLE_LENGTH} characters)`
  return null
}

/**
 * Extract initials from a case title.
 * Takes the first letter of each of the first two words, uppercased.
 * Falls back to the first two characters of the string if only one word.
 * Examples:
 *   'Block deep work'   -> 'BD'
 *   'Q3 travel policy'  -> 'QT'
 *   'sync'              -> 'SY'
 */
function getInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  const first = words[0]?.[0] ?? ''
  const second = words[1]?.[0] ?? words[0]?.[1] ?? ''
  return (first + second).toUpperCase()
}

/**
 * Build a tree of CaseTreeItem from a flat Case list, sorted newest first at every level.
 * Uses `modified` (falling back to `created`) so the list reflects recent activity.
 */
function buildTree(cases: Case[]): CaseTreeItem[] {
  const allIds = new Set(cases.map((c) => c.id ?? ''))
  const modifiedAt = new Map(cases.map((c) => [c.id ?? '', c.modified ?? c.created ?? '']))

  // Reuse the shared mapping so each node carries name, favorite and canDelete; store the
  // case ID in description so search (and ds-entity-list's filter) also match on it.
  const toNode = (c: Case): CaseTreeItem => ({
    ...CaseItemComponent.toListItem(c),
    description: c.id ?? '',
    status: c.status ?? 'IDLE',
    children: [],
  })

  const nodeMap = new Map<string, CaseTreeItem>()
  for (const c of cases) {
    nodeMap.set(c.id ?? '', toNode(c))
  }

  const roots: CaseTreeItem[] = []
  for (const c of cases) {
    const node = nodeMap.get(c.id ?? '')!
    const parentId = c.parentCaseId
    if (parentId && allIds.has(parentId)) {
      nodeMap.get(parentId)!.children.push(node)
    } else {
      roots.push(node)
    }
  }

  const sortDesc = (items: CaseTreeItem[]): CaseTreeItem[] =>
    items
      .sort((a, b) => ((modifiedAt.get(b.id) ?? '') > (modifiedAt.get(a.id) ?? '') ? 1 : -1))
      .map((item) => ({ ...item, children: sortDesc(item.children) }))

  return sortDesc(roots)
}

/**
 * Promote favorited cases to a "Favorites" group at the top, then group remaining
 * cases by recency (only when favorites exist): Today / Yesterday / Previous 7 days / Older.
 */
function promoteFavoritesAndGroup(roots: CaseTreeItem[], modifiedAt: Map<string, string>): CaseTreeItem[] {
  const favorites: CaseTreeItem[] = []
  const remaining: CaseTreeItem[] = []
  for (const root of roots) {
    const kept = liftFavoriteSubtrees(root, favorites)
    if (kept) remaining.push(kept)
  }

  // Label favorites
  for (const node of favorites) {
    node.groupKey = 'favorites'
    node.groupLabel = 'Favorites'
  }

  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const startOfYesterday = new Date(startOfToday.getTime() - 86400000)
  const start7Days = new Date(startOfToday.getTime() - 6 * 86400000)

  for (const node of remaining) {
    const ts = modifiedAt.get(node.id) ?? ''
    const d = ts ? new Date(ts) : null
    if (d && d >= startOfToday) {
      node.groupKey = 'today'
      node.groupLabel = 'Today'
    } else if (d && d >= startOfYesterday) {
      node.groupKey = 'yesterday'
      node.groupLabel = 'Yesterday'
    } else if (d && d >= start7Days) {
      node.groupKey = 'week'
      node.groupLabel = 'Previous 7 days'
    } else {
      node.groupKey = 'older'
      node.groupLabel = 'Older'
    }
  }

  return [...favorites, ...remaining]
}

/**
 * Split [node] (assumed to have no favorited ancestor) between the Favorites group and the
 * remaining tree. A favorited node is lifted whole — with its entire subtree, so favorited
 * descendants stay nested under it instead of being promoted to the same level — and removed
 * from the remaining tree (returns null). A non-favorited node stays in the remaining tree,
 * with any favorited sub-branches lifted out of it.
 */
function liftFavoriteSubtrees(node: CaseTreeItem, favorites: CaseTreeItem[]): CaseTreeItem | null {
  if (node.favorite) {
    favorites.push({ ...node })
    return null
  }
  const remainingChildren: CaseTreeItem[] = []
  for (const child of node.children) {
    const kept = liftFavoriteSubtrees(child, favorites)
    if (kept) remainingChildren.push(kept)
  }
  return { ...node, children: remainingChildren }
}

/**
 * Recursively collect all tree nodes (at any depth) whose name or id
 * contains the given query string (case-insensitive).
 * Returns a flat list — hierarchy is not preserved in search results.
 */
function collectMatching(nodes: CaseTreeItem[], query: string): CaseTreeItem[] {
  return nodes.reduce<CaseTreeItem[]>((acc, node) => {
    const matches = node.name.toLowerCase().includes(query) || node.id.toLowerCase().includes(query)
    if (matches) acc.push(node)
    if (node.children.length) acc.push(...collectMatching(node.children, query))
    return acc
  }, [])
}

function findAndCollectAncestors(nodes: CaseTreeItem[], targetId: string, acc: Set<string>): boolean {
  for (const node of nodes) {
    if (node.id === targetId) return true
    if (node.children.length && findAndCollectAncestors(node.children, targetId, acc)) {
      acc.add(node.id)
      return true
    }
  }
  return false
}
