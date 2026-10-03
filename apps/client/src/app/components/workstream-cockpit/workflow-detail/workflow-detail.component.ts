import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { StepLane, WorkflowDetailDto, WorkflowStepDto } from '../../../core/models/workstream.model'
import { stepBadgeClass } from '../workstream-badges'

/**
 * Workflow detail view: Human / Agent / Code lanes of steps, with status badges
 * and a revision / freshness indicator for the workflow projection.
 * Presentational — all data flows from the cockpit container.
 */
@Component({
  selector: 'app-workflow-detail',
  standalone: true,
  imports: [MatIconModule],
  templateUrl: './workflow-detail.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './workflow-detail.component.scss',
})
export class WorkflowDetailComponent {
  @Input() detail: WorkflowDetailDto | null = null
  @Input() lanes: Record<string, StepLane> = {}
  @Input() selectedStepId: string | null = null

  @Output() selectStep = new EventEmitter<string>()

  protected readonly stepBadgeClass = stepBadgeClass
  protected readonly laneOrder: StepLane[] = ['human', 'agent', 'code']
  protected readonly laneLabels: Record<StepLane, string> = {
    human: 'Human',
    agent: 'Agent',
    code: 'Code',
  }
  protected readonly laneIcons: Record<StepLane, string> = {
    human: 'person',
    agent: 'smart_toy',
    code: 'code',
  }

  protected stepsForLane(lane: StepLane): WorkflowStepDto[] {
    const steps = this.detail?.steps ?? []
    return steps.filter((step) => this.laneOf(step) === lane)
  }

  protected laneOf(step: WorkflowStepDto): StepLane {
    return this.lanes[step.stepId] ?? 'agent'
  }

  protected onSelectStep(stepId: string): void {
    this.selectStep.emit(stepId)
  }
}
