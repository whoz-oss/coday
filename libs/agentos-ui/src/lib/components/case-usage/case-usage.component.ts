import { DecimalPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core'
import {
  RunCostControllerService,
  RunCostDto,
  PausedCostDto,
  UsageAggregate,
  UsageRecordControllerService,
} from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
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

@Component({
  selector: 'agentos-case-usage',
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './case-usage.component.html',
  styleUrl: './case-usage.component.scss',
})
export class CaseUsageComponent {
  readonly caseId = input.required<string>()
  readonly canWrite = input(false)
  readonly running = input(false)
  private readonly costs = inject(RunCostControllerService)
  private readonly usage = inject(UsageRecordControllerService)
  private readonly caseState = inject(CaseStateService)
  readonly run = signal<RunCostDto | null>(null)
  readonly total = signal<UsageAggregate | null>(null)
  readonly error = signal<string | null>(null)
  readonly saving = signal(false)

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
      const caseId = this.caseId()
      const running = this.running()
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

  private actions = new Subscription()

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
    if (!this.canWrite() || this.saving()) return
    this.saving.set(true)
    this.actions.add(
      this.costs
        .continueCostRunRunCost(limit.caseId, { expectedThreshold: limit.threshold })
        .pipe(switchMap(() => this.costs.getRunCost(this.caseId())))
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
    if (!this.canWrite() || this.saving()) return
    this.saving.set(true)
    this.actions.add(
      this.costs.stopCostRunRunCost(this.caseId()).subscribe({
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
