import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  output,
  signal,
  TemplateRef,
  ViewChild,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { CaseStatusGlyphComponent } from '../case-status-glyph/case-status-glyph.component'
import { Case, CaseStatusEventStatusEnum } from '@whoz-oss/agentos-api-client'
import { CaseItemComponent, CaseListItem } from '../case-item/case-item.component'

/**
 * A case list item extended with a recursive children list (parent/child cases).
 * Only root nodes are passed to ds-entity-list; children are rendered inline by the
 * itemTemplate. Inherits `favorite` + `canDelete` from [CaseListItem] so each row can
 * render the star toggle and the (ADMIN-gated) delete action.
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
   * Root items enriched with initials and status, used in compact/icons mode.
   * Initials = first letter of the first two words of the title, uppercased.
   */
  /**
   * Items affichés dans le rail compact :
   * - Si une recherche est active : liste plate des résultats filtrés (tous les niveaux)
   * - Sinon : racines groupées par date/favoris
   * Chaque item est enrichi des initiales et d'un flag de premier groupe.
   */
  protected readonly compactItems = computed(() => {
    const source = this.isSearchActive() ? this.flatFilteredItems() : this.rootItems()
    return source.map((item, i, arr) => ({
      ...item,
      initials: getInitials(item.name),
      /** Vrai si cet item est le premier de son groupe dans le rail compact. */
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
  // Compact tooltip (position: fixed — échappe au overflow:hidden du rail)
  // ---------------------------------------------------------------------------

  /** Item survolé en mode compact — null = tooltip masqué. */
  protected readonly hoveredCompactItem = signal<
    (typeof this.compactItems extends () => (infer T)[] ? T : never) | null
  >(null)

  /** Position fixe du tooltip calculée depuis le badge survolé. */
  protected readonly tooltipPos = signal<{ top: number; left: number }>({ top: 0, left: 0 })

  /** Id du case en attente de confirmation de suppression dans le tooltip compact. */
  protected readonly compactConfirmingDeleteId = signal<string | null>(null)

  /** Id du case en attente de confirmation de suppression en mode expanded. */
  protected readonly expandedConfirmingDeleteId = signal<string | null>(null)

  protected onCompactBadgeEnter(event: MouseEvent, item: ReturnType<typeof this.compactItems>[number]): void {
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
    this.tooltipPos.set({ top: rect.top + rect.height / 2, left: rect.right + 8 })
    this.hoveredCompactItem.set(item)
    // Réinitialise la confirmation si on survole un autre case
    if (this.compactConfirmingDeleteId() !== item.id) {
      this.compactConfirmingDeleteId.set(null)
    }
  }

  protected onCompactBadgeLeave(event: MouseEvent): void {
    // Le pont CSS (::before) couvre le gap — mais on vérifie aussi via relatedTarget
    const related = event.relatedTarget as HTMLElement | null
    if (related?.closest('.case-drawer-compact__tooltip-fixed')) return
    // Petit délai pour laisser le temps à la souris de traverser le gap
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
 * Promote favorited cases to a "Pinned" group at the top, then group remaining
 * cases by recency: Today / Yesterday / Previous 7 days / Older.
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

  // Group remaining by date — only when there are favorites to separate from
  if (favorites.length > 0) {
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
