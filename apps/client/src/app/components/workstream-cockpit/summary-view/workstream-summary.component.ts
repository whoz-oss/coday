import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressBarModule } from '@angular/material/progress-bar'
import {
  HumanActionRequiredDto,
  WorkflowBlockerDto,
  WorkflowDetailDto,
  WorkflowSummaryDto,
} from '../../../core/models/workstream.model'
import { blockerBadgeClass, blockerLabel } from '../workstream-badges'

/**
 * Workstream summary view: active workflows list, per-workflow progress,
 * blockers with distinct badges, and pending human actions.
 * Presentational — all data flows from the cockpit container.
 */
@Component({
  selector: 'app-workstream-summary',
  standalone: true,
  imports: [MatIconModule, MatProgressBarModule],
  templateUrl: './workstream-summary.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './workstream-summary.component.scss',
})
export class WorkstreamSummaryComponent {
  @Input() workflows: WorkflowSummaryDto[] = []
  @Input() detailsById: Record<string, WorkflowDetailDto> = {}
  @Input() selectedWorkflowId: string | null = null
  @Input() pendingActions: HumanActionRequiredDto[] = []

  @Output() selectWorkflow = new EventEmitter<string>()

  protected readonly blockerBadgeClass = blockerBadgeClass
  protected readonly blockerLabel = blockerLabel

  protected blockersOf(workflowId: string): WorkflowBlockerDto[] {
    return this.detailsById[workflowId]?.blockers ?? []
  }

  protected progressOf(workflowId: string): number {
    const steps = this.detailsById[workflowId]?.steps ?? []
    if (steps.length === 0) return 0
    const completed = steps.filter((step) => step.status === 'completed').length
    return Math.round((completed / steps.length) * 100)
  }

  protected completedStepsOf(workflowId: string): string {
    const steps = this.detailsById[workflowId]?.steps ?? []
    const completed = steps.filter((step) => step.status === 'completed').length
    return `${completed}/${steps.length}`
  }

  protected onSelect(workflowId: string): void {
    this.selectWorkflow.emit(workflowId)
  }
}
