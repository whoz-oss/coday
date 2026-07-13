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
import { Case } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'
import { CaseItemComponent, CaseListItem } from '../case-item/case-item.component'

/**
 * A case list item extended with a recursive children list (parent/child cases).
 * Only root nodes are passed to ds-entity-list; children are rendered inline by
 * the itemTemplate. Inherits `favorite` + `canDelete` from [CaseListItem] so each
 * row can render the star toggle and the (ADMIN-gated) delete button.
 */
export interface CaseTreeItem extends CaseListItem {
  children: CaseTreeItem[]
}

/**
 * CaseDrawerComponent — presentational drawer for the case list.
 *
 * Uses ds-entity-list for the chrome (toolbar, search, create button).
 *
 * Two display modes:
 * - **Tree mode** (no active search): root cases are shown with expandable sub-cases.
 * - **Flat mode** (search active): all matching cases at every depth level are shown
 *   as a flat list, including sub-cases. Matching is done on title AND case ID.
 */
@Component({
  selector: 'agentos-case-drawer',
  imports: [EntityListComponent, KebabMenuComponent, NgTemplateOutlet],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseTreeItem }>

  /** Current search query, driven by ds-entity-list's searchChanged output. */
  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  /**
   * Tree rebuilt automatically when cases() changes.
   * Favorites are promoted to the top; remaining roots are bucketed by creation date.
   */
  protected readonly rootItems = computed(() => {
    const { roots, createdAt } = buildTree(this.cases())
    return groupFavorites(roots, createdAt)
  })

  /**
   * Flat list of all cases matching the current search query.
   * Traverses the full tree at every depth level.
   * Matching is done on title AND case ID (case-insensitive).
   * Used in flat mode (search active).
   */
  protected readonly flatFilteredItems = computed((): CaseTreeItem[] => {
    const query = this.searchQuery().toLowerCase().trim()
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

  /** IDs of nodes manually expanded/collapsed by the user. */
  protected readonly expandedIds = signal(new Set<string>())

  protected isExpanded(id: string): boolean {
    return this.expandedIds().has(id) || this.autoExpandedIds().has(id)
  }

  protected toggleExpand(event: Event, id: string): void {
    event.stopPropagation()
    this.expandedIds.update((set) => {
      const next = new Set(set)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  protected onSearchChanged(query: string): void {
    this.searchQuery.set(query)
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

  protected onStarToggled(item: CaseListItem): void {
    // Emit the desired state; CaseStateService applies the optimistic flip on the list signal,
    // which rebuilds the tree (and re-groups favorites) reactively.
    this.starToggled.emit({ id: item.id, starred: !item.favorite })
  }

  /** Overflow-menu entries for a row: star toggle, plus delete when the caller may delete. */
  protected menuItemsFor(node: CaseTreeItem): KebabMenuItem[] {
    const items: KebabMenuItem[] = [
      {
        key: 'star',
        label: node.favorite ? 'Remove from favorites' : 'Add to favorites',
        icon: node.favorite ? 'star' : 'star_border',
      },
    ]
    if (node.canDelete) {
      items.push({ key: 'delete', label: 'Delete', icon: 'delete', variant: 'danger' })
    }
    return items
  }

  protected onMenuAction(node: CaseTreeItem, key: string): void {
    switch (key) {
      case 'star':
        this.onStarToggled(node)
        break
      case 'delete':
        this.onDeleteRequested(node.id)
        break
    }
  }
}

// ── Time-bucket helpers ──────────────────────────────────────────────────────

type TimeBucket = 'today' | 'this-week' | 'last-month' | 'older'

const BUCKET_ORDER: TimeBucket[] = ['today', 'this-week', 'last-month', 'older']

const BUCKET_LABEL: Record<TimeBucket, string> = {
  today: 'Today',
  'this-week': 'This week',
  'last-month': 'Last month',
  older: 'Older',
}

function timeBucket(isoDate: string | undefined, now: Date): TimeBucket {
  if (!isoDate) return 'older'
  const d = new Date(isoDate)
  if (isNaN(d.getTime())) return 'older'

  const startOfToday = new Date(now)
  startOfToday.setHours(0, 0, 0, 0)

  const startOfWeek = new Date(startOfToday)
  startOfWeek.setDate(startOfToday.getDate() - startOfToday.getDay())

  const startOfLastMonth = new Date(now.getFullYear(), now.getMonth() - 1, 1)

  if (d >= startOfToday) return 'today'
  if (d >= startOfWeek) return 'this-week'
  if (d >= startOfLastMonth) return 'last-month'
  return 'older'
}

/**
 * Group nodes into "Favorites" + time-bucket sections.
 *
 * Favorited nodes (at any depth) are extracted from the tree and promoted to the
 * top of the list under a "Favorites" group. Their children follow them.
 *
 * Non-favorited root nodes are then distributed into time-based buckets
 * (Today / This week / Last month / Older) based on their `created` date.
 * Empty buckets are omitted.
 */
function groupFavorites(roots: CaseTreeItem[], createdAt: Map<string, string>): CaseTreeItem[] {
  // Extract favorites from the entire tree.
  const promoted: CaseTreeItem[] = []
  const prunedRoots = extractFavorites(roots, promoted)

  for (const node of promoted) {
    node.groupKey = 'favorites'
    node.groupLabel = 'Favorites'
  }

  // Distribute remaining roots into time buckets.
  const now = new Date()
  const buckets = new Map<TimeBucket, CaseTreeItem[]>()
  for (const node of prunedRoots) {
    const bucket = timeBucket(createdAt.get(node.id), now)
    if (!buckets.has(bucket)) buckets.set(bucket, [])
    buckets.get(bucket)!.push(node)
  }

  const bucketed: CaseTreeItem[] = []
  for (const key of BUCKET_ORDER) {
    const group = buckets.get(key)
    if (!group?.length) continue
    for (const node of group) {
      node.groupKey = key
      node.groupLabel = BUCKET_LABEL[key]
    }
    bucketed.push(...group)
  }

  // If there are no favorites and only one non-empty bucket, skip grouping entirely
  // to avoid a single section header wrapping the whole list.
  const activeBuckets = BUCKET_ORDER.filter((k) => buckets.has(k))
  if (!promoted.length && activeBuckets.length <= 1) {
    return roots
  }

  return [...promoted, ...bucketed]
}

/**
 * Recursively walk [nodes], collect favorited nodes into [favorites], and return
 * the remaining (non-favorited) nodes with their children similarly pruned.
 * The order within each group is preserved (newest-first from buildTree).
 */
function extractFavorites(nodes: CaseTreeItem[], favorites: CaseTreeItem[]): CaseTreeItem[] {
  const remaining: CaseTreeItem[] = []
  for (const node of nodes) {
    // Recurse first so a favorited grandchild is extracted before we inspect the parent.
    const prunedChildren = extractFavorites(node.children, favorites)
    if (node.favorite) {
      // Lift this node to favorites; keep its (already-pruned) children with it.
      favorites.push({ ...node, children: prunedChildren, groupKey: undefined, groupLabel: undefined })
    } else {
      remaining.push({ ...node, children: prunedChildren })
    }
  }
  return remaining
}

/**
 * Build a tree of CaseTreeItem from a flat Case list, sorted newest first at every level.
 * Returns both the root nodes and the createdAt map (needed by groupFavorites for bucketing).
 */
function buildTree(cases: Case[]): { roots: CaseTreeItem[]; createdAt: Map<string, string> } {
  const allIds = new Set(cases.map((c) => c.id ?? ''))
  const createdAt = new Map(cases.map((c) => [c.id ?? '', c.created ?? '']))

  // Reuse the shared mapping so each node carries name, favorite and canDelete.
  const toNode = (c: Case): CaseTreeItem => ({
    ...CaseItemComponent.toListItem(c),
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
      .sort((a, b) => ((createdAt.get(b.id) ?? '') > (createdAt.get(a.id) ?? '') ? 1 : -1))
      .map((item) => ({ ...item, children: sortDesc(item.children) }))

  return { roots: sortDesc(roots), createdAt }
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
