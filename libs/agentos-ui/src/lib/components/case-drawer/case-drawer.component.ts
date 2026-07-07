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
 * Only root nodes are passed to ds-entity-list; children are rendered
 * inline by the itemTemplate.
 */
export interface CaseTreeItem extends EntityListItem {
  children: CaseTreeItem[]
}

/**
 * CaseDrawerComponent — presentational drawer for the case list.
 *
 * Uses ds-entity-list for the chrome (toolbar, search, create button).
 * The itemTemplate handles hierarchical rendering: each root item renders
 * its sub-cases inline, collapsed by default.
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

  /**
   * Arbre reconstruit automatiquement quand cases() change.
   * Remplace OnChanges qui ne se déclenche pas avec les signal inputs.
   */
  protected readonly rootItems = computed(() => buildTree(this.cases()))

  /**
   * Ancêtres du case actif à auto-expanded, recalculés quand
   * rootItems() ou activeCaseId() change.
   */
  private readonly autoExpandedIds = computed(() => {
    const id = this.activeCaseId()
    if (!id) return new Set<string>()
    const expanded = new Set<string>()
    findAndCollectAncestors(this.rootItems(), id, expanded)
    return expanded
  })

  /** IDs des noeuds manuellement expand/collapse par l'utilisateur. */
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
