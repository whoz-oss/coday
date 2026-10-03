import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute } from '@angular/router'
import { MatIconModule } from '@angular/material/icon'
import {
  ControllerHistoryDto,
  HumanActionRequiredDto,
  PlanChangeProposalDto,
  StepAttemptDto,
  StepLane,
  WorkflowBlockerDto,
  WorkflowDetailDto,
  WorkflowListDto,
  WorkstreamDto,
} from '../../core/models/workstream.model'
import { MOCK_AS_OF, WorkstreamMockService } from '../../core/services/workstream-mock.service'
import { WorkstreamAgentLinkComponent } from './agent-link-tile/workstream-agent-link.component'
import { ControllerHistoryComponent } from './controller-history/controller-history.component'
import { HumanInteractionsComponent, InteractionResponse } from './human-interactions/human-interactions.component'
import { PlanChangeDecision, PlanChangesComponent } from './plan-changes/plan-changes.component'
import { StepAttemptsComponent, StepRetryRequest } from './step-attempts/step-attempts.component'
import { WorkstreamSummaryComponent } from './summary-view/workstream-summary.component'
import { WorkflowDetailComponent } from './workflow-detail/workflow-detail.component'

const DEFAULT_WORKSTREAM_ID = 'ws-demo'

/**
 * Workstream Cockpit container (Phase 11 scaffold).
 *
 * Routes: `/workstream` and `/project/:projectName/workstream`.
 *
 * Backed exclusively by the injectable `WorkstreamMockService` (mock DTO data
 * matching the Phase 0 contracts). Phase 6 will swap the mock for real
 * `/api/factory/**` HTTP calls — see the TODO(Phase 6) markers in the service.
 *
 * Only retry / decision actions derived from DTO fields are exposed; the browser
 * never launches workers nor transitions workflows directly (§5 capability matrix).
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
  private readonly mock = inject(WorkstreamMockService)
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

  protected readonly selectedStepId = signal<string | null>(null)
  protected readonly stepAttempts = signal<StepAttemptDto[]>([])

  /** Freshness anchor for the mock dataset displayed in the top badge. */
  protected readonly asOf = MOCK_AS_OF

  /** Current revision shown by the freshness badge (selected workflow, else workstream). */
  protected readonly currentRevision = computed(
    () => this.workflowDetail()?.revision ?? this.workstream()?.revision ?? 0
  )

  constructor() {
    this.loadWorkstream()
  }

  protected onSelectWorkflow(workflowId: string): void {
    this.selectedWorkflowId.set(workflowId)
    this.selectedStepId.set(null)
    this.stepAttempts.set([])
    this.mock
      .getWorkflow(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((detail) => this.workflowDetail.set(detail))
    this.mock
      .getStepLanes(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((lanes) => this.stepLanes.set(lanes))
    this.mock
      .getBlockers(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((blockers) => this.blockers.set(blockers))
    this.mock
      .getRequiredHumanActions(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((interactions) => this.interactions.set(interactions))
    this.mock
      .getPlanChangeProposals(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((proposals) => this.proposals.set(proposals))
    this.mock
      .getControllerHistory(workflowId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((history) => this.controllerHistory.set(history))
  }

  protected onSelectStep(stepId: string): void {
    const workflowId = this.selectedWorkflowId()
    if (!workflowId) return
    this.selectedStepId.set(stepId)
    this.mock
      .getStepAttempts(workflowId, stepId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((attempts) => this.stepAttempts.set(attempts))
  }

  /** Retry intent — forwarded to the request_agent_retry command stub (mock). */
  protected onRetry(request: StepRetryRequest): void {
    this.mock
      .requestAgentRetry(request.workflowId, request.stepId, request.expectedRevision, 'COCKPIT_MANUAL_RETRY')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ack) => console.log('[WORKSTREAM-COCKPIT] retry acknowledgement (mock)', ack))
  }

  /** Human decision on an open interaction — forwarded to the reply command stub (mock). */
  protected onRespond(response: InteractionResponse): void {
    this.mock
      .respondToInteraction(response.workflowId, response.interactionId, response.actionId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ack) => console.log('[WORKSTREAM-COCKPIT] interaction reply acknowledgement (mock)', ack))
  }

  /** Plan-change decision — forwarded to the decision command stub (mock). */
  protected onDecide(decision: PlanChangeDecision): void {
    this.mock
      .decidePlanChange(decision.proposalId, decision.decision)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ack) => console.log('[WORKSTREAM-COCKPIT] plan-change decision acknowledgement (mock)', ack))
  }

  private loadWorkstream(): void {
    this.mock
      .getWorkstream(this.workstreamId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((workstream) => this.workstream.set(workstream))
    this.mock
      .listWorkflows(this.workstreamId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((list) => {
        this.workflowList.set(list)
        // Preload details + pending actions per workflow for the summary view.
        for (const item of list.items) {
          this.mock
            .getWorkflow(item.workflowId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((detail) => this.detailsById.update((byId) => ({ ...byId, [detail.workflowId]: detail })))
          this.mock
            .getRequiredHumanActions(item.workflowId)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((actions) => this.pendingActions.update((all) => [...all, ...actions]))
        }
        const first = list.items[0]
        if (first) this.onSelectWorkflow(first.workflowId)
      })
  }
}
