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
type ConfirmationKind = 'remove' | 'purge'

@Component({
  selector: 'agentos-factory-workflow-projection',
  imports: [FormsModule, FactoryTemporalLanesComponent, DeliveryPanelComponent],
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
      if (snapshot.projection.steps.some((step) => step.status === 'waiting_human'))
        this.loadInteractions(namespaceId, snapshot.workflowId)
      else this.interactions.set([])
      const execution = snapshot.controllerExecution
      if (execution?.kind === 'agentos') {
        this.loadEnvironment(namespaceId, snapshot.workflowId, execution.caseId)
        this.loadDelivery(namespaceId, snapshot.workflowId, execution.caseId)
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

  protected onDeliveryAction(action: string): void {
    // Delivery actions are intentionally not implemented in the generic cockpit:
    // checkpoint/push/PR require claims (diff hash + file list) that must come from
    // the Factory control plane, not from the browser. Actions are surfaced here as
    // visibility only. Promote (human release approval) may be wired in a future pass
    // once the evidence route is confirmed operational.
    console.warn('[delivery-panel] Action requested but not yet wired in generic cockpit:', action)
  }

  protected reconcileEnvironment(): void {
    const execution = this.snapshot().controllerExecution
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
