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
import { catchError, EMPTY, exhaustMap, forkJoin, Subscription, switchMap, timer } from 'rxjs'

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
  private readonly costs = inject(RunCostControllerService)
  private readonly usage = inject(UsageRecordControllerService)
  private readonly caseState = inject(CaseStateService)
  readonly run = signal<RunCostDto | null>(null)
  readonly total = signal<UsageAggregate | null>(null)
  readonly error = signal<string | null>(null)
  readonly saving = signal(false)

  constructor() {
    effect((cleanup) => {
      const caseId = this.caseId()
      this.run.set(null)
      this.total.set(null)
      this.error.set(null)
      this.saving.set(false)
      const actions = new Subscription()
      this.actions = actions
      const polling = timer(0, 3000)
        .pipe(
          exhaustMap(() =>
            forkJoin({
              run: this.costs.getRunCost(caseId),
              total: this.usage.aggregateByCaseTreeUsageRecord(caseId),
            }).pipe(
              catchError(() => {
                this.error.set('Usage could not be refreshed. Displayed values may be out of date.')
                return EMPTY
              })
            )
          )
        )
        .subscribe(({ run, total }) => {
          this.run.set(run)
          this.total.set(total)
          this.error.set(null)
        })
      cleanup(() => {
        polling.unsubscribe()
        actions.unsubscribe()
      })
    })
  }

  private actions = new Subscription()

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
