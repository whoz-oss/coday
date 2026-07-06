import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
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
  standalone: true,
  imports: [EntityListComponent, NgTemplateOutlet],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent implements OnChanges {
  @Input() cases: Case[] = []
  @Input() activeCaseId: string | null = null

  @Output() caseSelected = new EventEmitter<string>()
  @Output() createRequested = new EventEmitter<void>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseTreeItem }>

  /** Only root nodes — ds-entity-list iterates over these. */
  protected rootItems: CaseTreeItem[] = []

  /** IDs of nodes whose children are currently visible. */
  protected expandedIds = new Set<string>()

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['cases']) {
      this.rootItems = buildTree(this.cases)
    }
    if (changes['cases'] || changes['activeCaseId']) {
      this.autoExpandAncestors()
    }
  }

  /** Expand all ancestors of the active case so it is visible. */
  private autoExpandAncestors(): void {
    if (!this.activeCaseId) return
    const findAndExpand = (nodes: CaseTreeItem[], targetId: string): boolean => {
      for (const node of nodes) {
        if (node.id === targetId) return true
        if (node.children.length && findAndExpand(node.children, targetId)) {
          this.expandedIds.add(node.id)
          return true
        }
      }
      return false
    }
    findAndExpand(this.rootItems, this.activeCaseId)
  }

  protected isExpanded(id: string): boolean {
    return this.expandedIds.has(id)
  }

  protected toggleExpand(event: Event, id: string): void {
    event.stopPropagation()
    if (this.expandedIds.has(id)) {
      this.expandedIds.delete(id)
    } else {
      this.expandedIds.add(id)
    }
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

  // Sort newest first at every level of the tree
  const sortDesc = (items: CaseTreeItem[]): CaseTreeItem[] =>
    items
      .sort((a, b) => ((createdAt.get(b.id) ?? '') > (createdAt.get(a.id) ?? '') ? 1 : -1))
      .map((item) => ({ ...item, children: sortDesc(item.children) }))

  return sortDesc(roots)
}
