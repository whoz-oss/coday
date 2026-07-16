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
import { Case, CaseStatusEnum } from '@whoz-oss/agentos-api-client'
import { EntityListComponent } from '@whoz-oss/design-system'
import { CaseItemComponent, CaseListItem } from '../case-item/case-item.component'

export interface CaseTreeItem extends CaseListItem {
  children: CaseTreeItem[]
}

@Component({
  selector: 'agentos-case-drawer',
  imports: [EntityListComponent, NgTemplateOutlet],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent {
  protected readonly CaseStatusEnum = CaseStatusEnum

  readonly cases = input<Case[]>([])
  readonly activeCaseId = input<string | null>(null)
  readonly compact = input<boolean>(false)

  readonly caseSelected = output<string>()
  readonly createRequested = output<void>()
  readonly deleteRequested = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseTreeItem }>

  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  /**
   * Tree rebuilt automatically when cases() changes.
   * Favorited cases are promoted to a "Favorites" group at the TOP (tree/flat modes).
   */
  protected readonly rootItems = computed(() => promoteFavorites(buildTree(this.cases())))

  /**
   * Items for compact/icons mode.
   * Favorites FIRST, then non-favorites.
   * `isFirstFavorite` marks the first favorite so the template inserts a divider after it.
   */
  protected readonly compactItems = computed(() => {
    const items = this.rootItems()
    const favs = items.filter((item) => item.groupKey === 'favorites')
    const nonFavs = items.filter((item) => item.groupKey !== 'favorites')
    const ordered = [...favs, ...nonFavs]
    return ordered.map((item, index) => ({
      ...item,
      isFirstFavorite: favs.length > 0 && index === favs.length - 1,
    }))
  })

  protected readonly flatFilteredItems = computed((): CaseTreeItem[] => {
    const query = this.searchQuery().toLowerCase().trim()
    if (!query) return []
    return collectMatching(this.rootItems(), query)
  })

  protected readonly displayItems = computed((): CaseTreeItem[] =>
    this.isSearchActive() ? this.flatFilteredItems() : this.rootItems()
  )

  private readonly autoExpandedIds = computed(() => {
    const id = this.activeCaseId()
    if (!id) return new Set<string>()
    const expanded = new Set<string>()
    findAndCollectAncestors(this.rootItems(), id, expanded)
    return expanded
  })

  protected readonly expandOverrides = signal(new Map<string, boolean>())

  /** Id of the case currently awaiting delete confirmation, null if none. */
  protected readonly pendingDeleteId = signal<string | null>(null)

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
    this.pendingDeleteId.set(id)
  }

  protected onDeleteConfirmed(id: string): void {
    this.pendingDeleteId.set(null)
    this.deleteRequested.emit(id)
  }

  protected onDeleteCancelled(): void {
    this.pendingDeleteId.set(null)
  }

  protected onStarToggled(item: CaseListItem): void {
    this.starToggled.emit({ id: item.id, starred: !item.favorite })
  }
}

function buildTree(cases: Case[]): CaseTreeItem[] {
  const allIds = new Set(cases.map((c) => c.id ?? ''))
  const modifiedAt = new Map(cases.map((c) => [c.id ?? '', c.modified ?? c.created ?? '']))

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
