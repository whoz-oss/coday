import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  OnChanges,
  OnDestroy,
  output,
  signal,
  SimpleChanges,
} from '@angular/core'
import { FormsModule } from '@angular/forms'
import { toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { interval, EMPTY, switchMap } from 'rxjs'
import { map } from 'rxjs/operators'
import { FactoryRunDetail } from '../../services/factory-api.service'
import { FactoryStateService } from '../../services/factory-state.service'
import {
  FactoryReviewGateService,
  ReviewGateDecision,
  ReviewGateState,
} from '../../services/factory-review-gate.service'
import { FactoryRunTimelineComponent } from '../factory-run-timeline/factory-run-timeline.component'
import { formatTimelineDuration, timelineStatus } from '../factory-run-timeline/factory-run-timeline.models'
import { FactoryPhasePanelComponent } from '../factory-phase-panel/factory-phase-panel.component'

@Component({
  selector: 'agentos-factory-run-detail',
  imports: [FactoryRunTimelineComponent, FactoryPhasePanelComponent, FormsModule],
  templateUrl: './factory-run-detail.component.html',
  styleUrl: './factory-run-detail.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryRunDetailComponent implements OnChanges, OnDestroy {
  readonly run = input.required<FactoryRunDetail>()
  readonly selectedPhaseIndex = input.required<number>()
  readonly phaseSelected = output<number>()

  private readonly clockTick = signal(0)
  protected readonly factoryState = inject(FactoryStateService)
  protected readonly reviewGate = inject(FactoryReviewGateService)

  protected gateMessage = ''
  protected submitting = false

  constructor() {
    toObservable(this.run)
      .pipe(
        switchMap((run) => (run.status === 'running' ? interval(1000).pipe(map(() => run)) : EMPTY)),
        takeUntilDestroyed()
      )
      .subscribe(() => this.clockTick.update((n) => n + 1))
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['run']) {
      const runId = this.run().runId
      // startPolling is idempotent for the same runId
      this.reviewGate.startPolling(runId)
    }
  }

  ngOnDestroy(): void {
    this.reviewGate.stopPolling()
  }

  protected readonly runningElapsedMs = computed(() => {
    if (this.run().status !== 'running' || !this.run().startedAt) return null
    const startedAtValue = this.run().startedAt
    if (!startedAtValue) return null
    const startedAt = Date.parse(startedAtValue)
    void this.clockTick()
    return Number.isFinite(startedAt) ? Math.max(Date.now() - startedAt, this.run().durationMs ?? 0) : null
  })

  protected readonly stats = computed(() => {
    const run = this.run()
    const phases = run.phases
    return [
      { label: 'Duration', value: formatTimelineDuration(this.runningElapsedMs() ?? run.durationMs ?? 0) },
      { label: 'Phases', value: `${phases.length}` },
      {
        label: 'Failures',
        value: `${phases.filter((phase) => timelineStatus(phase.status) === 'failed').length}`,
        alert: true,
      },
      { label: 'Status', value: run.status },
    ]
  })

  protected formatDuration(durationMs: number | null): string {
    return durationMs == null ? '\u2014' : formatTimelineDuration(durationMs)
  }

  protected onStopClick(): void {
    this.factoryState.stopSelectedRun()
  }

  /** Type-safe helper for template narrowing of pending gate. */
  protected pendingGate(gate: ReviewGateState): Extract<ReviewGateState, { status: 'pending' }> | null {
    return gate?.status === 'pending' ? (gate as Extract<ReviewGateState, { status: 'pending' }>) : null
  }

  /** Type-safe helper for template narrowing of terminal gate. */
  protected terminalGate(gate: ReviewGateState): Extract<ReviewGateState, { status: 'terminal' }> | null {
    return gate?.status === 'terminal' ? (gate as Extract<ReviewGateState, { status: 'terminal' }>) : null
  }

  protected sendDecision(decision: ReviewGateDecision): void {
    if (this.submitting) return
    this.submitting = true
    this.reviewGate.submitError.set(null)

    this.reviewGate.sendDecision(decision, this.gateMessage).subscribe({
      next: () => {
        this.submitting = false
        this.gateMessage = ''
        // Refresh run and gate state after successful decision
        const run = this.run()
        this.factoryState.loadDetail(run.runId, run.namespaceId ?? '')
        this.reviewGate.startPolling(run.runId)
      },
      error: () => {
        this.submitting = false
        this.reviewGate.submitError.set('Failed to send decision. Please try again.')
      },
    })
  }

  protected decisionAllowed(decision: ReviewGateDecision): boolean {
    const gate = this.reviewGate.gate()
    if (gate?.status !== 'pending') return false
    return (gate as Extract<ReviewGateState, { status: 'pending' }>).allowedDecisions.includes(decision)
  }
}
