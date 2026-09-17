import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core'
import { FormsModule } from '@angular/forms'
import {
  agentOsControllerUrl,
  WorkflowProjectionSnapshotDto,
  WorkflowProjectionTimingState,
  WorkflowProjectionV2,
} from '../../services/factory-workflow-projection.model'
import { FactoryTemporalLanesComponent } from './factory-temporal-lanes.component'

export type WorkflowProjectionCardMode = 'active' | 'removed'
type ConfirmationKind = 'remove' | 'purge'

@Component({
  selector: 'agentos-factory-workflow-projection',
  imports: [FormsModule, FactoryTemporalLanesComponent],
  templateUrl: './factory-workflow-projection.component.html',
  styleUrl: './factory-workflow-projection.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryWorkflowProjectionComponent {
  readonly snapshot = input.required<WorkflowProjectionSnapshotDto>()
  readonly mode = input<WorkflowProjectionCardMode>('active')
  readonly pending = input(false)
  readonly timing = input<WorkflowProjectionTimingState>()
  readonly namespaceId = input.required<string>()
  protected readonly v2Projection = computed(() =>
    this.snapshot().projection.schemaVersion === '2' ? (this.snapshot().projection as WorkflowProjectionV2) : null
  )
  protected readonly selectedStepId = signal<string | null>(null)
  protected readonly selectedStep = computed(() => {
    const steps = this.snapshot().projection.steps
    return steps.find((step) => step.id === this.selectedStepId()) ?? steps[0] ?? null
  })
  protected readonly selectedStepTiming = computed(() =>
    this.timing()?.timing.steps.find((step) => step.stepId === this.selectedStep()?.id)
  )
  protected readonly controllerUrl = computed(() =>
    agentOsControllerUrl(this.namespaceId(), this.snapshot().controllerExecution)
  )
  readonly removeRequested = output<string>()
  readonly restoreRequested = output<string>()
  readonly purgeRequested = output<string>()
  protected readonly confirmation = signal<ConfirmationKind | null>(null)
  protected readonly purgeText = signal('')
  protected readonly purgeMatches = computed(() => {
    const expected = this.snapshot().projection.title || this.snapshot().workflowId
    return this.purgeText().trim() === expected
  })
  private readonly dialog = viewChild<ElementRef<HTMLDialogElement>>('confirmationDialog')

  protected selectStep(stepId: string): void {
    this.selectedStepId.set(stepId)
  }
  protected formatDuration(durationMs: number): string {
    const seconds = Math.round(durationMs / 1000)
    return seconds >= 60 ? `${Math.floor(seconds / 60)}m ${seconds % 60}s` : `${seconds}s`
  }
  protected statusClass(status: string): string {
    return `workflow-projection__status--${status.replace(/_/g, '-')}`
  }
  protected displayStatus(status: string): string {
    return status.replace(/_/g, ' ')
  }
  protected openConfirmation(kind: ConfirmationKind): void {
    this.confirmation.set(kind)
    this.purgeText.set('')
    this.dialog()?.nativeElement.showModal()
  }
  protected closeConfirmation(): void {
    this.dialog()?.nativeElement.close()
    this.confirmation.set(null)
    this.purgeText.set('')
  }
  protected confirm(): void {
    if (this.confirmation() === 'remove') this.removeRequested.emit(this.snapshot().workflowId)
    if (this.confirmation() === 'purge' && this.purgeMatches()) this.purgeRequested.emit(this.snapshot().workflowId)
    this.closeConfirmation()
  }
  protected restore(): void {
    this.restoreRequested.emit(this.snapshot().workflowId)
  }
}
