import { computed, inject, Injectable, effect, signal } from '@angular/core'
import {
  PausedCostDto,
  RunCostControllerService,
  RunCostDto,
  UsageAggregate,
  UsageRecordControllerService,
} from '@whoz-oss/agentos-api-client'
import {
  catchError,
  distinct,
  EMPTY,
  exhaustMap,
  filter,
  forkJoin,
  map,
  merge,
  Observable,
  of,
  Subscription,
  switchMap,
  timer,
} from 'rxjs'
import { CaseStateService } from './case-state.service'
import { UsageConfigurationService } from './usage-configuration.service'

/**
 * Run cost of the case on display: the live figures, and the decision to take when the run reaches its cost threshold
 * (continue with a doubled threshold, or stop).
 *
 * Component-scoped, like the composer services: provided by the chat that owns the case, so the poll exists once per
 * chat and is shared by the usage strip (the readout) and the cost pause card (the decision). The host feeds it
 * through {@link bind}; both views only read it.
 */
@Injectable()
export class CaseRunCostStateService {
  readonly configuration = inject(UsageConfigurationService)
  private readonly costs = inject(RunCostControllerService)
  private readonly usage = inject(UsageRecordControllerService)
  private readonly caseState = inject(CaseStateService)

  /** The case on display; null until the host binds one, and nothing is loaded meanwhile. */
  readonly caseId = signal<string | null>(null)
  /** Whether the viewer may edit the case: only then can the run be stopped. */
  readonly canWrite = signal(false)
  /** Whether the case runs, which is what makes the run cost worth polling. */
  readonly running = signal(false)

  readonly run = signal<RunCostDto | null>(null)
  readonly total = signal<UsageAggregate | null>(null)
  readonly error = signal<string | null>(null)
  readonly saving = signal(false)

  /** The run waits for a decision about its cost threshold (here, or in a linked conversation). */
  readonly paused = computed(() => {
    const run = this.run()
    return this.configuration.enabled() && !!run && (run.paused || run.pausedCases.length > 0)
  })

  private actions = new Subscription()

  constructor() {
    effect((cleanup) => {
      // Reset only on navigation, independently of activity changes.
      this.caseId()
      this.run.set(null)
      this.total.set(null)
      this.error.set(null)
      this.saving.set(false)
      const actions = new Subscription()
      this.actions = actions
      cleanup(() => actions.unsubscribe())
    })

    effect((cleanup) => {
      if (!this.configuration.enabled()) return
      const caseId = this.caseId()
      const running = this.running()
      if (!caseId) return
      // A decision owns its refresh; cancel polling so older snapshots cannot restore the pause.
      if (this.saving()) return
      const answers = this.caseState.agentMessageEvent$.pipe(
        filter((event) => event.caseId === caseId),
        map((event) => event.id),
        distinct(),
        map(() => true)
      )
      // Only run-cost is polled. Aggregate history refreshes on mount, activity changes and answers.
      const refresh = merge(of(true), answers, running ? timer(2000, 2000).pipe(map(() => false)) : EMPTY)
        .pipe(exhaustMap((includeTotal) => this.loadUsage(caseId, includeTotal)))
        .subscribe((usage) => this.applyUsage(caseId, usage))
      cleanup(() => refresh.unsubscribe())
    })
  }

  /** Points the state at a case; called by the owner of the service whenever one of the three changes. */
  bind(caseId: string | null, canWrite: boolean, running: boolean): void {
    this.caseId.set(caseId)
    this.canWrite.set(canWrite)
    this.running.set(running)
  }

  private loadUsage(
    caseId: string,
    includeTotal: boolean
  ): Observable<{ run: RunCostDto; total: UsageAggregate | undefined }> {
    return forkJoin({
      run: this.costs.getRunCost(caseId),
      total: includeTotal ? this.usage.aggregateByCaseTreeUsageRecord(caseId) : of(undefined),
    }).pipe(
      catchError(() => {
        this.error.set('Usage could not be refreshed. Displayed values may be out of date.')
        return EMPTY
      })
    )
  }

  private applyUsage(caseId: string, usage: { run: RunCostDto; total: UsageAggregate | undefined }): void {
    if (this.caseId() !== caseId || this.saving()) return
    this.run.set(usage.run)
    if (usage.total) this.total.set(usage.total)
    this.error.set(null)
  }

  continue(limit: PausedCostDto): void {
    const caseId = this.caseId()
    if (!caseId || !this.configuration.enabled() || !limit.canContinue || this.saving()) return
    this.saving.set(true)
    this.actions.add(
      this.costs
        .continueCostRunRunCost(limit.caseId, { expectedThreshold: limit.threshold })
        .pipe(switchMap(() => this.costs.getRunCost(caseId)))
        .subscribe({
          next: (updated) => {
            this.run.set(updated)
            this.caseState.refreshCaseThreshold(limit.caseId)
            this.saving.set(false)
            this.error.set(null)
          },
          error: () => {
            this.saving.set(false)
            this.error.set('Could not continue. Refresh and check the threshold and your edit permission.')
          },
        })
    )
  }

  stop(): void {
    const caseId = this.caseId()
    if (!caseId || !this.configuration.enabled() || !this.canWrite() || this.saving()) return
    this.saving.set(true)
    this.actions.add(
      this.costs.stopCostRunRunCost(caseId).subscribe({
        next: () => {
          this.run.update((run) => (run ? { ...run, paused: false, pausedCases: [] } : null))
          this.saving.set(false)
        },
        error: () => {
          this.saving.set(false)
          this.error.set('Could not stop the execution. Please try again.')
        },
      })
    )
  }
}
