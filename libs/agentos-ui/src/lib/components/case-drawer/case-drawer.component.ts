import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { IconButtonComponent } from '@whoz-oss/design-system'
import { CaseNode } from './case-node'

/**
 * CaseDrawerComponent — presentational drawer content for the case tree.
 *
 * Receives a pre-built CaseNode tree as @Input and renders it as a
 * collapsible tree (roots collapsed by default).
 * The active case and all its ancestors are auto-expanded on input change.
 *
 * Kept presentational: no Router, no HTTP, no state services.
 */
@Component({
  selector: 'agentos-case-drawer',
  standalone: true,
  imports: [NgTemplateOutlet, IconButtonComponent],
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

  /** Set of node ids whose children are currently visible. */
  protected expandedIds = new Set<string>()

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
    return this.expandedIds.has(nodeId)
  }

  protected toggleExpand(event: Event, nodeId: string): void {
    event.stopPropagation()
    if (this.expandedIds.has(nodeId)) {
      this.expandedIds.delete(nodeId)
    } else {
      this.expandedIds.add(nodeId)
    }
  }

  protected onCaseSelected(caseId: string): void {
    this.caseSelected.emit(caseId)
  }
}
