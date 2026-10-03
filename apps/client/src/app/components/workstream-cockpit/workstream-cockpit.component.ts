import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import { MatIconModule } from '@angular/material/icon'
import { catchError, of } from 'rxjs'
import {
  AllowedActionDto,
  ControllerHistoryDto,
  HumanActionRequiredDto,
  PlanChangeProposalDto,
  StepAttemptDto,
  StepLane,
  WorkflowActionsResponseDto,
  WorkflowBlockerDto,
  WorkflowDetailDto,
  WorkflowListDto,
  WorkstreamDto,
} from '../../core/models/workstream.model'
import { FactoryApiError, FactoryWorkstreamService } from '../../core/services/factory-workstream.service'
import { MOCK_AS_OF } from '../../core/services/workstream-mock.service'
import { WorkstreamAgentLinkComponent } from './agent-link-tile/workstream-agent-link.component'
import { ControllerHistoryComponent } from './controller-history/controller-history.component'
import { HumanInteractionsComponent, InteractionResponse } from './human-interactions/human-interactions.component'
import { PlanChangeDecision, PlanChangesComponent } from './plan-changes/plan-changes.component'
import { StepAttemptsComponent, StepRetryRequest } from './step-attempts/step-attempts.component'
import { WorkstreamSummaryComponent } from './summary-view/workstream-summary.component'
import { WorkflowDetailComponent } from './workflow-detail/workflow-detail.component'

const DEFAULT_WORKSTREAM_ID = 'ws-demo'

/**
 * Workstream Cockpit container (Phase 11 — real HTTP wiring).
 *
 * Routes: `/workstream` and `/project/:projectName/workstream`.
 *
 * Data flows exclusively through {@link FactoryWorkstreamService}, which either calls the
 * real `/api/factory/**` endpoints (default) or the in-memory `WorkstreamMockService`
 * (dev/test toggle). Actions are derived strictly from the backend `allowedActions`
 * read — the browser never launches workers nor transitions workflows directly (§5
 * capability matrix).
 */
@Component({
  selector: 'app-workstream-cockpit',
  standalone: true,
  imports: [
    MatIconModule,
    WorkstreamSummaryComponent,
    WorkstreamAgentLinkComponent,
    WorkflowDetailComponent,
    StepAttemptsComponent,
    HumanInteractionsComponent,
    PlanChangesComponent,
    ControllerHistoryComponent,
  ],
  templateUrl: './workstream-cockpit.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './workstream-cockpit.component.scss',
})
export class WorkstreamCockpitComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly factory = inject(FactoryWorkstreamService)
  private readonly destroyRef = inject(DestroyRef)

  protected readonly projectName = this.route.snapshot.paramMap.get('projectName')
  protected readonly workstreamId = this.route.snapshot.paramMap.get('workstreamId') ?? DEFAULT_WORKSTREAM_ID

  protected readonly workstream = signal<WorkstreamDto | null>(null)
  protected readonly workflowList = signal<WorkflowListDto>({ items: [], nextCursor: null })
  protected readonly detailsById = signal<Record<string, WorkflowDetailDto>>({})
  protected readonly pendingActions = signal<HumanActionRequiredDto[]>([])

  protected readonly selectedWorkflowId = signal<string | null>(null)
  protected readonly workflowDetail = signal<WorkflowDetailDto | null>(null)
  protected readonly stepLanes = signal<Record<string, StepLane>>({})
  protected readonly blockers = signal<WorkflowBlockerDto[]>([])
  protected readonly interactions = signal<HumanActionRequiredDto[]>([])
  protected readonly proposals = signal<PlanChangeProposalDto[]>([])
  protected readonly controllerHistory = signal<ControllerHistoryDto | null>(null)

  /** Authoritative actions the backend authorizes for the selected workflow. */
  protected readonly allowedActions = signal<AllowedActionDto[]>([])

  protected readonly selectedStepId = signal<string | null>(null)
  protected readonly stepAttempts = signal<StepAttemptDto[]>([])

  /** UI state. */
  protected readonly isLoading = signal<boolean>(false)
  protected readonly errorMessage = signal<string | null>(null)

  /** True when the cockpit is backed by the in-memory mock rather than real HTTP. */
  protected readonly isMock = this.factory.useMock

  /** Freshness anchor: last real HTTP sync, falling back to the mock anchor. */
  protected readonly asOf = computed(() => this.factory.lastSyncAsOf() ?? MOCK_AS_OF)

  /** Current revision shown by the freshness badge (selected workflow, else workstream). */
  protected readonly currentRevision = computed(
    () => this.workflowDetail()?.revision ?? this.workstream()?.revision ?? this.factory.lastRevision() ?? 0
  )

  constructor() {
    this.loadWorkstream()
  }

  protected onSelectWorkflow(workflowId: string): void {
    this.selectedWorkflowId.set(workflowId)
    this.selectedStepId.set(null)
    this.stepAttempts.set([])
    this.isLoading.set(true)
    this.errorMessage.set(null)

    this.factory
      .getWorkflow(workflowId)
      .pipe(
        catchError((error) => {
          this.errorMessage.set(this.messageOf(error))
          return of<WorkflowDetailDto | null>(null)
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((detail) => {
        this.workflowDetail.set(detail)
        this.isLoading.set(false)
      })

    this.factory
      .getStepLanes(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((lanes) => this.stepLanes.set(lanes))

    this.factory
      .getAllowedActions(workflowId)
      .pipe(
        catchError(() => of<WorkflowActionsResponseDto>({ allowedActions: [], blockers: [] })),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((actions) => {
        this.allowedActions.set(actions.allowedActions)
        this.blockers.set(actions.blockers)
      })

    this.factory
      .getRequiredHumanActions(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((interactions) => this.interactions.set(interactions))

    this.factory
      .getPlanChangeProposals(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((proposals) => this.proposals.set(proposals))

    this.factory
      .getControllerHistory(workflowId, this.workstreamId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((history) => this.controllerHistory.set(history))
  }

  protected onSelectStep(stepId: string): void {
    const workflowId = this.selectedWorkflowId()
    if (!workflowId) return
    this.selectedStepId.set(stepId)
    this.stepAttempts.set([])
    this.factory
      .getStepAttempts(workflowId, stepId)
      .pipe(
        catchError(() => of([] as StepAttemptDto[])),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((attempts) => this.stepAttempts.set(attempts))
  }

  /** Retry intent — forwarded to the request_agent_retry command. */
  protected onRetry(request: StepRetryRequest): void {
    this.factory
      .requestAgentRetry(request.workflowId, request.stepId, request.expectedRevision, 'COCKPIT_MANUAL_RETRY')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ack) => {
          if (ack.allowedActions) this.allowedActions.set(ack.allowedActions)
          this.refreshSelectedWorkflow()
        },
        error: (error) => this.errorMessage.set(this.messageOf(error)),
      })
  }

  /** Human decision on an open interaction — forwarded to the reply command. */
  protected onRespond(response: InteractionResponse): void {
    this.factory
      .respondToInteraction(response.workflowId, response.interactionId, response.actionId, response.expectedRevision)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ack) => {
          if (ack.allowedActions) this.allowedActions.set(ack.allowedActions)
          this.refreshSelectedWorkflow()
        },
        error: (error) => this.errorMessage.set(this.messageOf(error)),
      })
  }

  /** Plan-change decision — forwarded to the decision command. */
  protected onDecide(decision: PlanChangeDecision): void {
    this.factory
      .decidePlanChange(decision.proposalId, decision.decision, {
        workflowId: decision.workflowId,
        expectedRevision: decision.expectedRevision,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ack) => {
          if (ack.allowedActions) this.allowedActions.set(ack.allowedActions)
          this.refreshSelectedWorkflow()
        },
        error: (error) => this.errorMessage.set(this.messageOf(error)),
      })
  }

  private loadWorkstream(): void {
    this.isLoading.set(true)
    this.errorMessage.set(null)

    this.factory
      .getWorkstream(this.workstreamId)
      .pipe(
        catchError((error) => {
          this.errorMessage.set(this.messageOf(error))
          return of<WorkstreamDto | null>(null)
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((workstream) => this.workstream.set(workstream))

    this.factory
      .listWorkflows(this.workstreamId)
      .pipe(
        catchError((error) => {
          this.errorMessage.set(this.messageOf(error))
          return of<WorkflowListDto>({ items: [], nextCursor: null })
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((list) => {
        this.workflowList.set(list)
        this.isLoading.set(false)
        // Preload details + pending actions per workflow for the summary view.
        for (const item of list.items) {
          this.factory
            .getWorkflow(item.workflowId)
            .pipe(
              catchError(() => of<WorkflowDetailDto | null>(null)),
              takeUntilDestroyed(this.destroyRef)
            )
            .subscribe((detail) => {
              if (!detail) return
              this.detailsById.update((byId) => ({ ...byId, [detail.workflowId]: detail }))
            })
          this.factory
            .getRequiredHumanActions(item.workflowId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((actions) => this.pendingActions.update((all) => [...all, ...actions]))
        }
        const first = list.items[0]
        if (first) this.onSelectWorkflow(first.workflowId)
      })
  }

  /** Re-read the selected workflow's authoritative state after a command. */
  private refreshSelectedWorkflow(): void {
    const workflowId = this.selectedWorkflowId()
    if (workflowId) this.onSelectWorkflow(workflowId)
  }

  private messageOf(error: unknown): string {
    const factoryError = error as FactoryApiError | undefined
    if (factoryError?.message) {
      return factoryError.code && factoryError.code !== `HTTP_${factoryError.status}`
        ? `${factoryError.code}: ${factoryError.message}`
        : factoryError.message
    }
    return error instanceof Error ? error.message : 'Factory request failed'
  }
}
