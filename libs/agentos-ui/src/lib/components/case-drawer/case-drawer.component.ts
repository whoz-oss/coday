import {
  ChangeDetectionStrategy,
  Component,
  computed,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  signal,
  SimpleChanges,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { CaseNode } from './case-node'

/**
 * CaseDrawerComponent — presentational drawer content for the case tree.
 *
 * Receives a pre-built CaseNode tree as @Input and renders it as a
 * collapsible tree (collapsed by default).
 * The active case and all its ancestors are auto-expanded on input change.
 * A search input filters nodes by title; matching subtrees are kept intact
 * and their ancestors auto-expanded.
 *
 * Kept presentational: no Router, no HTTP, no state services.
 */
@Component({
  selector: 'agentos-case-drawer',
  standalone: true,
  imports: [NgTemplateOutlet, FormsModule, IconButtonComponent],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent implements OnChanges {
  @Input() caseTree: CaseNode[] = []
  @Input() activeCaseId: string | null = null

  @Output() caseSelected = new EventEmitter<string>()
  @Output() createRequested = new EventEmitter<void>()
  @Output() closeRequested = new EventEmitter<void>()

  protected readonly searchQuery = signal('')
  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  /** Set of node ids whose children are currently visible. */
  protected expandedIds = new Set<string>()

  /**
   * Filtered tree: a node is kept if its title matches OR if any descendant matches.
   * When the query is empty the full tree is returned as-is.
   */
  protected readonly filteredTree = computed(() => {
    const query = this.searchQuery().trim().toLowerCase()
    if (!query) return this.caseTree
    return filterTree(this.caseTree, query)
  })

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['activeCaseId'] || changes['caseTree']) {
      this.autoExpandAncestors()
    }
  }

  /**
   * Walk the tree to find the active case and expand all its ancestors.
   * Called whenever the tree or the active id changes.
   */
  private autoExpandAncestors(): void {
    if (!this.activeCaseId || !this.caseTree.length) return

    const findAndExpand = (nodes: CaseNode[], targetId: string): boolean => {
      for (const node of nodes) {
        if (node.id === targetId) return true
        if (node.children.length && findAndExpand(node.children, targetId)) {
          this.expandedIds.add(node.id)
          return true
        }
      }
      return false
    }

    findAndExpand(this.caseTree, this.activeCaseId)
  }

  protected isExpanded(nodeId: string): boolean {
    // When a search is active, always expand to show matching descendants
    return this.isSearchActive() || this.expandedIds.has(nodeId)
  }

  protected toggleExpand(event: Event, nodeId: string): void {
    event.stopPropagation()
    if (this.expandedIds.has(nodeId)) {
      this.expandedIds.delete(nodeId)
    } else {
      this.expandedIds.add(nodeId)
    }
  }

  protected clearSearch(): void {
    this.searchQuery.set('')
  }

  protected onCaseSelected(caseId: string): void {
    this.caseSelected.emit(caseId)
  }
}

/**
 * Recursively filter a CaseNode tree.
 * A node is kept if its own title matches OR if at least one descendant matches.
 * Children are filtered recursively — the returned tree only contains matching subtrees.
 */
function filterTree(nodes: CaseNode[], query: string): CaseNode[] {
  const result: CaseNode[] = []
  for (const node of nodes) {
    const filteredChildren = filterTree(node.children, query)
    const selfMatches = node.title.toLowerCase().includes(query)
    if (selfMatches || filteredChildren.length > 0) {
      result.push({ ...node, children: filteredChildren })
    }
  }
  return result
}
