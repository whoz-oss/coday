import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
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
  WorkflowHumanInteraction,
} from '../../services/factory-workflow-projection.model'
import { FactoryApiService } from '../../services/factory-api.service'
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
  private readonly api = inject(FactoryApiService)
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
  protected readonly interactions = signal<WorkflowHumanInteraction[]>([])
  protected readonly interactionLoading = signal(false)
  protected readonly interactionError = signal<string | null>(null)
  protected readonly replyingInteractionId = signal<string | null>(null)
  protected readonly replyText = signal('')

  constructor() {
    effect(() => {
      const snapshot = this.snapshot(),
        namespaceId = this.namespaceId()
      if (snapshot.projection.steps.some((step) => step.status === 'waiting_human'))
        this.loadInteractions(namespaceId, snapshot.workflowId)
      else this.interactions.set([])
    })
  }

  private loadInteractions(namespaceId: string, workflowId: string): void {
    this.interactionLoading.set(true)
    this.interactionError.set(null)
    this.api.listWorkflowHumanInteractions(namespaceId, workflowId).subscribe({
      next: (response) => {
        this.interactions.set(response.data.items)
        this.interactionLoading.set(false)
      },
      error: () => {
        this.interactionError.set('Human interaction could not be loaded.')
        this.interactionLoading.set(false)
      },
    })
  }

  protected reply(interaction: WorkflowHumanInteraction, actionId: string): void {
    if (this.replyingInteractionId()) return
    this.replyingInteractionId.set(interaction.interactionId)
    this.interactionError.set(null)
    this.api
      .replyWorkflowHumanInteraction(this.namespaceId(), interaction.workflowId, interaction.interactionId, {
        expectedRevision: interaction.expectedRevision,
        actionId,
        ...(this.replyText().trim() ? { text: this.replyText().trim() } : {}),
      })
      .subscribe({
        next: () => {
          this.replyingInteractionId.set(null)
          this.replyText.set('')
          this.interactions.update((items) => items.filter((item) => item.interactionId !== interaction.interactionId))
        },
        error: (error) => {
          this.replyingInteractionId.set(null)
          this.interactionError.set(error?.error?.error?.message ?? 'The decision was rejected. Refresh and retry.')
        },
      })
  }

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
