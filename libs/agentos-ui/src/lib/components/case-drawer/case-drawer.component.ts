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
   * Grouped by favorites when at least one root is favorited.
   */
  protected readonly rootItems = computed(() => groupFavorites(buildTree(this.cases())))

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

/**
 * Group the root nodes into "Favorites" / "Others" accordion sections (favorites first)
 * when at least one root is favorited; otherwise leave them ungrouped (flat tree).
 *
 * Grouping is applied at the root level only — a favorited sub-case stays nested under its
 * parent (its favorite state is reachable via the row's "⋯" menu), rather than being lifted
 * out of the hierarchy.
 */
function groupFavorites(roots: CaseTreeItem[]): CaseTreeItem[] {
  if (!roots.some((r) => r.favorite)) return roots
  // Stable sort keeps the newest-first order within each section.
  const sorted = [...roots].sort((a, b) => Number(b.favorite) - Number(a.favorite))
  for (const root of sorted) {
    root.groupKey = root.favorite ? 'favorites' : 'others'
    root.groupLabel = root.favorite ? 'Favorites' : 'Others'
  }
  return sorted
}

/** Build a tree of CaseTreeItem from a flat Case list, sorted newest first at every level. */
function buildTree(cases: Case[]): CaseTreeItem[] {
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

  return sortDesc(roots)
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
