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
import { Case } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { NgTemplateOutlet } from '@angular/common'

/**
 * EntityListItem extended with a recursive children list.
 * Only root nodes are passed to ds-entity-list in tree mode; children are rendered
 * inline by the itemTemplate.
 */
export interface CaseTreeItem extends EntityListItem {
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
  imports: [EntityListComponent, NgTemplateOutlet],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseTreeItem }>

  /** Current search query, driven by ds-entity-list's searchChanged output. */
  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  /**
   * Tree rebuilt automatically when cases() changes.
   * Used in tree mode (no active search).
   */
  protected readonly rootItems = computed(() => buildTree(this.cases()))

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
}

/** Build a tree of CaseTreeItem from a flat Case list, sorted newest first at every level. */
function buildTree(cases: Case[]): CaseTreeItem[] {
  const allIds = new Set(cases.map((c) => c.id ?? ''))
  const createdAt = new Map(cases.map((c) => [c.id ?? '', c.created ?? '']))

  const toNode = (c: Case): CaseTreeItem => ({
    id: c.id ?? '',
    name: c.title ?? c.id ?? '—',
    // Store the case ID in description so ds-entity-list's built-in filter also matches on it
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
  const results: CaseTreeItem[] = []
  for (const node of nodes) {
    const matchesName = node.name.toLowerCase().includes(query)
    const matchesId = node.id.toLowerCase().includes(query)
    if (matchesName || matchesId) {
      results.push(node)
    }
    if (node.children.length) {
      results.push(...collectMatching(node.children, query))
    }
  }
  return results
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
