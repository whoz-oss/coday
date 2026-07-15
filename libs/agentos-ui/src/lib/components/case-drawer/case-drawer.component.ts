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
 * Only root nodes are passed to ds-entity-list; children are rendered inline by the
 * itemTemplate. Inherits `favorite` + `canDelete` from [CaseListItem] so each row can
 * render the star toggle and the (ADMIN-gated) delete action.
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
  imports: [EntityListComponent, KebabMenuComponent, NgTemplateOutlet],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)
  readonly compact = input<boolean>(false)

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseTreeItem }>

  /** Current search query, driven by ds-entity-list's searchChanged output. */
  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  /**
   * Tree rebuilt automatically when cases() changes. Favorited cases (at any depth) are
   * promoted to a "Favorites" group at the top; the rest keep their newest-first order.
   */
  protected readonly rootItems = computed(() => promoteFavorites(buildTree(this.cases())))

  /**
   * Root items enriched with initials, used in compact/icons mode.
   * Initials = first letter of the first two words of the title, uppercased.
   */
  protected readonly compactItems = computed(() =>
    this.rootItems().map((item) => ({
      ...item,
      initials: getInitials(item.name),
    }))
  )

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

  /**
   * Explicit user expand/collapse choices (true = expanded, false = collapsed). An entry here
   * overrides the auto-expanded default, so a user CAN collapse an ancestor of the active case
   * (which [autoExpandedIds] would otherwise force open).
   */
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
 * Promote favorited cases (at any depth) to a "Favorites" group at the top of the list.
 * The remaining cases keep their newest-first order and render ungrouped below. When there
 * are no favorites the list is returned unchanged (no empty section header).
 */
function promoteFavorites(roots: CaseTreeItem[]): CaseTreeItem[] {
  const promoted: CaseTreeItem[] = []
  const remaining = extractFavorites(roots, promoted)
  if (!promoted.length) return roots
  for (const node of promoted) {
    node.groupKey = 'favorites'
    node.groupLabel = 'Favorites'
  }
  return [...promoted, ...remaining]
}

/**
 * Recursively walk [nodes], collect favorited nodes into [favorites], and return the
 * remaining (non-favorited) nodes with their children similarly pruned. A favorited node
 * keeps its (already-pruned) children with it. Order within each group is preserved.
 */
function extractFavorites(nodes: CaseTreeItem[], favorites: CaseTreeItem[]): CaseTreeItem[] {
  const remaining: CaseTreeItem[] = []
  for (const node of nodes) {
    const prunedChildren = extractFavorites(node.children, favorites)
    if (node.favorite) {
      favorites.push({ ...node, children: prunedChildren, groupKey: undefined, groupLabel: undefined })
    } else {
      remaining.push({ ...node, children: prunedChildren })
    }
  }
  return remaining
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
