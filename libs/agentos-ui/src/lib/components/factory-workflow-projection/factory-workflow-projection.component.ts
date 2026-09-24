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
import { MatButtonModule } from '@angular/material/button'
import {
  agentOsControllerUrl,
  durableControllerExecution,
  latestNegativeAgentResultReason,
  WorkflowEvidenceDto,
  WorkflowProjectionSnapshotDto,
  WorkflowProjectionTimingState,
  WorkflowProjectionV2,
  WorkflowHumanInteraction,
  WorkUnitEnvironmentDto,
} from '../../services/factory-workflow-projection.model'
import { FactoryApiService } from '../../services/factory-api.service'
import { FactoryTemporalLanesComponent } from './factory-temporal-lanes.component'
import { DeliveryPanelComponent } from '../factory-forge-runs/delivery-panel/delivery-panel.component'
import { FactoryDeliverySnapshotDto } from '../../services/factory-delivery.model'
import {
  FactoryMetricAvailability,
  FactoryMetricsScope,
  FactoryOperationalMetricsResponseDto,
} from '../../services/factory-operational-metrics.model'

export type WorkflowProjectionCardMode = 'active' | 'removed'
export interface GovernedActionCompleted {
  workflowId: string
  stepId: string
  revision: number
}
type ConfirmationKind = 'remove' | 'purge'

@Component({
  selector: 'agentos-factory-workflow-projection',
  imports: [FormsModule, MatButtonModule, FactoryTemporalLanesComponent, DeliveryPanelComponent],
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
  readonly selectedStepId = input<string | null>(null)
  readonly selectedStepIdChange = output<string>()
  protected readonly v2Projection = computed(() =>
    this.snapshot().projection.schemaVersion === '2' ? (this.snapshot().projection as WorkflowProjectionV2) : null
  )
  protected readonly selectedStep = computed(() => {
    const steps = this.snapshot().projection.steps
    return steps.find((step) => step.id === this.selectedStepId()) ?? steps[0] ?? null
  })
  protected readonly selectedStepTiming = computed(() =>
    this.timing()?.timing.steps.find((step) => step.stepId === this.selectedStep()?.id)
  )
  private readonly selectedV2Step = computed(
    () => this.v2Projection()?.steps.find((step) => step.id === this.selectedStep()?.id) ?? null
  )
  protected readonly durableController = computed(() => durableControllerExecution(this.snapshot()))
  protected readonly controllerUrl = computed(() => agentOsControllerUrl(this.namespaceId(), this.durableController()))
  readonly removeRequested = output<string>()
  readonly restoreRequested = output<string>()
  readonly purgeRequested = output<string>()
  readonly authoritativeRefreshRequested = output<void>()
  readonly governedActionCompleted = output<GovernedActionCompleted>()
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
  protected readonly evidence = signal<WorkflowEvidenceDto[]>([])
  protected readonly evidenceLoading = signal(false)
  protected readonly evidenceError = signal<string | null>(null)
  protected readonly retryPending = signal(false)
  protected readonly retryError = signal<string | null>(null)
  protected readonly continuePending = signal(false)
  protected readonly continueError = signal<string | null>(null)
  protected readonly retryReasonCode = computed(() => {
    const step = this.selectedStep()
    return step ? latestNegativeAgentResultReason(this.evidence(), step.id) : null
  })
  protected readonly blockedAgentStep = computed(() => {
    const step = this.selectedV2Step()
    return step?.status === 'blocked' && step.responsibility.kind === 'agent'
  })
  protected readonly selectedOpenInteraction = computed(() => {
    const stepId = this.selectedStep()?.id
    return (
      this.interactions().find((interaction) => interaction.stepId === stepId && interaction.status === 'open') ?? null
    )
  })
  protected readonly environment = signal<WorkUnitEnvironmentDto | null>(null)
  protected readonly environmentLoading = signal(false)
  protected readonly environmentError = signal<string | null>(null)
  protected readonly delivery = signal<FactoryDeliverySnapshotDto | null>(null)
  protected readonly deliveryLoading = signal(false)
  protected readonly deliveryError = signal<string | null>(null)
  protected readonly metrics = signal<FactoryOperationalMetricsResponseDto | null>(null)
  protected readonly metricsScope = signal<FactoryMetricsScope>('self')
  protected readonly metricsLoading = signal(false)
  protected readonly metricsError = signal<string | null>(null)

  constructor() {
    effect(() => {
      const snapshot = this.snapshot(),
        namespaceId = this.namespaceId()
      this.loadMetrics(namespaceId, snapshot.workflowId, this.metricsScope())
      const selectedStep = this.selectedV2Step()
      if (snapshot.projection.steps.some((step) => step.status === 'waiting_human' || step.status === 'blocked'))
        this.loadInteractions(namespaceId, snapshot.workflowId)
      else this.interactions.set([])
      if (selectedStep?.status === 'blocked' && selectedStep.responsibility.kind === 'agent')
        this.loadEvidence(namespaceId, snapshot.workflowId, selectedStep.id)
      else {
        this.evidence.set([])
        this.evidenceError.set(null)
      }
      const execution = durableControllerExecution(snapshot)
      if (execution?.kind === 'agentos') {
        this.loadEnvironment(namespaceId, snapshot.workflowId, execution.caseId)
        if (snapshot.instance?.deliveryRef) this.loadDelivery(namespaceId, snapshot.workflowId, execution.caseId)
        else this.clearDelivery()
      } else {
        this.environment.set(null)
        this.environmentError.set('Environment binding is only available for AgentOS-controlled workflows.')
        this.delivery.set(null)
        this.deliveryError.set('Delivery tracking is only available for AgentOS-controlled workflows.')
      }
    })
  }

  protected selectMetricsScope(scope: FactoryMetricsScope): void {
    this.metricsScope.set(scope)
  }

  private loadMetrics(namespaceId: string, workflowId: string, scope: FactoryMetricsScope): void {
    this.metricsLoading.set(true)
    this.metricsError.set(null)
    this.api.getWorkflowOperationalMetrics(namespaceId, workflowId, scope).subscribe({
      next: ({ data }) => {
        this.metrics.set(data)
        this.metricsLoading.set(false)
      },
      error: (error) => {
        this.metrics.set(null)
        this.metricsError.set(error?.error?.error?.message ?? 'Operational metrics are unavailable.')
        this.metricsLoading.set(false)
      },
    })
  }

  protected metricDuration(
    metric: FactoryMetricAvailability<unknown>,
    kind: 'cycle' | 'review' | 'delivery'
  ): number | null {
    if (!metric.available || !metric.value) return null
    const value = metric.value as Record<string, unknown>
    if (kind === 'cycle') {
      if (typeof value['durationMs'] === 'number') return value['durationMs']
      const envelope = value['envelope'] as { durationMs?: unknown } | undefined
      return typeof envelope?.durationMs === 'number' ? envelope.durationMs : null
    }
    if (kind === 'review') return typeof value['durationMs'] === 'number' ? value['durationMs'] : null
    const intervals = value['intervals'] as Array<{ durationMs?: unknown }> | undefined
    if (!intervals?.length) return null
    const duration = intervals.reduce(
      (sum, interval) => sum + (typeof interval.durationMs === 'number' ? interval.durationMs : 0),
      0
    )
    return duration
  }

  protected metricState(metric: FactoryMetricAvailability<unknown>): string {
    if (!metric.available) return 'Unavailable'
    return metric.complete ? 'Complete' : 'Incomplete'
  }

  private loadEnvironment(namespaceId: string, workflowId: string, caseId: string): void {
    this.environmentLoading.set(true)
    this.environmentError.set(null)
    this.api.getWorkflowEnvironment(namespaceId, workflowId, caseId).subscribe({
      next: (response) => {
        this.environment.set(response.data)
        this.environmentLoading.set(false)
      },
      error: (error) => {
        this.environment.set(null)
        this.environmentError.set(error?.error?.error?.code ?? 'ENVIRONMENT_NOT_BOUND')
        this.environmentLoading.set(false)
      },
    })
  }

  private clearDelivery(): void {
    this.delivery.set(null)
    this.deliveryLoading.set(false)
    this.deliveryError.set(null)
  }

  private loadDelivery(namespaceId: string, workflowId: string, caseId: string): void {
    this.deliveryLoading.set(true)
    this.deliveryError.set(null)
    this.api.getDelivery(namespaceId, caseId, workflowId).subscribe({
      next: (response) => {
        this.delivery.set(response.data ?? null)
        this.deliveryLoading.set(false)
      },
      error: (error) => {
        this.delivery.set(null)
        const code = error?.error?.error?.code
        // DELIVERY_BINDING_UNAVAILABLE means no delivery yet — not an error to display prominently.
        this.deliveryError.set(code === 'DELIVERY_BINDING_UNAVAILABLE' ? null : (code ?? 'DELIVERY_UNAVAILABLE'))
        this.deliveryLoading.set(false)
      },
    })
  }

  protected reconcileEnvironment(): void {
    const execution = this.durableController()
    if (execution?.kind !== 'agentos') return
    this.environmentLoading.set(true)
    this.api.reconcileWorkflowEnvironment(this.namespaceId(), this.snapshot().workflowId, execution.caseId).subscribe({
      next: (response) => {
        this.environment.set(response.data)
        this.environmentLoading.set(false)
        this.environmentError.set(null)
      },
      error: (error) => {
        this.environmentLoading.set(false)
        this.environmentError.set(error?.error?.error?.code ?? 'OWNERSHIP_UNCERTAIN')
      },
    })
  }

  private loadEvidence(namespaceId: string, workflowId: string, stepId: string): void {
    this.evidenceLoading.set(true)
    this.evidenceError.set(null)
    this.api.listWorkflowEvidence(namespaceId, workflowId, stepId).subscribe({
      next: ({ data }) => {
        this.evidence.set(data.items)
        this.evidenceLoading.set(false)
      },
      error: (error) => {
        this.evidence.set([])
        this.evidenceError.set(this.apiError(error, 'Retry evidence is unavailable.'))
        this.evidenceLoading.set(false)
      },
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
        expectedRevision: interaction.revision,
        actionId,
        ...(this.replyText().trim() ? { text: this.replyText().trim() } : {}),
      })
      .subscribe({
        next: () => {
          this.replyingInteractionId.set(null)
          this.replyText.set('')
          this.loadInteractions(this.namespaceId(), interaction.workflowId)
          this.emitGovernedActionCompleted(interaction.stepId)
        },
        error: (error) => {
          this.replyingInteractionId.set(null)
          this.interactionError.set(error?.error?.error?.message ?? 'The decision was rejected. Refresh and retry.')
        },
      })
  }

  protected requestRetry(): void {
    const step = this.selectedStep(),
      reasonCode = this.retryReasonCode()
    if (!this.blockedAgentStep() || !step || !reasonCode || this.retryPending()) return
    this.retryPending.set(true)
    this.retryError.set(null)
    this.api
      .requestWorkflowRetry(this.snapshot().workflowId, {
        namespaceId: this.namespaceId(),
        stepId: step.id,
        expectedRevision: this.snapshot().revision,
        reasonCode,
      })
      .subscribe({
        next: () => {
          this.retryPending.set(false)
          this.loadInteractions(this.namespaceId(), this.snapshot().workflowId)
          this.emitGovernedActionCompleted(step.id)
        },
        error: (error) => {
          this.retryPending.set(false)
          this.retryError.set(this.apiError(error, 'Retry request failed.'))
        },
      })
  }

  protected continueRun(): void {
    if (this.continuePending()) return
    this.continuePending.set(true)
    this.continueError.set(null)
    this.api.continueWorkflow(this.namespaceId(), this.snapshot().workflowId).subscribe({
      next: ({ data }) => {
        this.continuePending.set(false)
        if (data.status === 'BLOCKED' || data.status === 'FAILED') {
          this.continueError.set(this.boundedBusinessError(data.code, data.details, 'Continue request was blocked.'))
          return
        }
        const stepId = this.selectedStep()?.id
        if (stepId) this.emitGovernedActionCompleted(stepId)
        else this.authoritativeRefreshRequested.emit()
      },
      error: (error) => {
        this.continuePending.set(false)
        this.continueError.set(this.apiError(error, 'Continue request failed.'))
      },
    })
  }

  private emitGovernedActionCompleted(stepId: string): void {
    const snapshot = this.snapshot()
    this.governedActionCompleted.emit({ workflowId: snapshot.workflowId, stepId, revision: snapshot.revision })
  }

  private boundedBusinessError(code: string | undefined, details: string | undefined, fallback: string): string {
    return (
      [code, details]
        .filter(Boolean)
        .join(': ')
        .replace(/[\r\n\t]+/g, ' ')
        .replace(/\s+/g, ' ')
        .slice(0, 300) || fallback
    )
  }

  private apiError(error: unknown, fallback: string): string {
    const value = error as { error?: { error?: { code?: string; message?: string } } }
    const code = value?.error?.error?.code
    const message = value?.error?.error?.message
    return [code, message].filter(Boolean).join(': ').slice(0, 300) || fallback
  }

  protected selectStep(stepId: string): void {
    this.selectedStepIdChange.emit(stepId)
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
