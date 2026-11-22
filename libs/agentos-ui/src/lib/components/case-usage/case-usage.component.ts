import { DecimalPipe } from '@angular/common'
import { CostPipe } from '../../pipes/cost.pipe'
import { TokenCountPipe } from '../../pipes/token-count.pipe'
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import {
  CasePromptCacheControllerService,
  PromptCacheStatus,
  RunCostControllerService,
  RunCostDto,
  PausedCostDto,
  UsageAggregate,
  UsageRecordControllerService,
} from '@whoz-oss/agentos-api-client'
import { CaseStateService } from '../../services/case-state.service'
import { UsageConfigurationService } from '../../services/usage-configuration.service'
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

/** What one refresh of the bar loaded; `undefined` for what it did not ask for. */
interface UsageLoad {
  run: RunCostDto
  total: UsageAggregate | undefined
  /** Null when the status could not be read: the cache figure is hidden. */
  promptCache: PromptCacheStatus | null | undefined
}

const MINUTE_MS = 60_000
/** How often the cache countdown is refreshed while the cache is warm. */
const CACHE_TICK_MS = 30_000

@Component({
  selector: 'agentos-case-usage',
  imports: [DecimalPipe, CostPipe, TokenCountPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './case-usage.component.html',
  styleUrl: './case-usage.component.scss',
})
export class CaseUsageComponent {
  readonly caseId = input.required<string>()
  readonly canWrite = input(false)
  readonly running = input(false)
  readonly configuration = inject(UsageConfigurationService)
  private readonly costs = inject(RunCostControllerService)
  private readonly usage = inject(UsageRecordControllerService)
  private readonly caseState = inject(CaseStateService)
  private readonly promptCaches = inject(CasePromptCacheControllerService)
  private readonly costPipe = new CostPipe()
  readonly run = signal<RunCostDto | null>(null)
  readonly total = signal<UsageAggregate | null>(null)
  readonly promptCache = signal<PromptCacheStatus | null>(null)
  /** The clock of the cache countdown. */
  private readonly now = signal(Date.now())

  /** `Warm (N min left)`, `Cold (~cost)` or `Cold`; null (hidden) when the cache lifetime is unknown. */
  readonly cacheLabel = computed((): string | null => {
    const status = this.promptCache()
    if (!status?.expiresAt) return null
    const left = Date.parse(status.expiresAt) - this.now()
    if (left > 0) return left < MINUTE_MS ? 'Warm (<1 min left)' : `Warm (${Math.ceil(left / MINUTE_MS)} min left)`
    const cost = status.coldCostEstimate
    return cost === null || cost === undefined ? 'Cold' : `Cold (~${this.costPipe.transform(cost)})`
  })
  readonly error = signal<string | null>(null)
  readonly saving = signal(false)

  constructor() {
    effect((cleanup) => {
      // Reset only on navigation, independently of activity changes.
      this.caseId()
      this.run.set(null)
      this.total.set(null)
      this.promptCache.set(null)
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

    // The countdown ticks only while the cache is warm, and once more at its expiry to turn it cold.
    effect((cleanup) => {
      const expiresAt = this.promptCache()?.expiresAt
      const left = expiresAt ? Date.parse(expiresAt) - Date.now() : 0
      if (left <= 0) return
      const refresh = (): void => this.now.set(Date.now())
      const tick = setInterval(refresh, CACHE_TICK_MS)
      const expiry = setTimeout(() => {
        clearInterval(tick)
        refresh()
      }, left)
      cleanup(() => {
        clearInterval(tick)
        clearTimeout(expiry)
      })
    })
  }

  private actions = new Subscription()

  private loadUsage(caseId: string, includeTotal: boolean): Observable<UsageLoad> {
    return forkJoin({
      run: this.costs.getRunCost(caseId),
      total: includeTotal ? this.usage.aggregateByCaseTreeUsageRecord(caseId) : of(undefined),
      // Refreshed with the totals (load, answers, run start and end), not by the run-cost polling.
      promptCache: includeTotal
        ? this.promptCaches.getPromptCacheStatusCasePromptCache(caseId).pipe(catchError(() => of(null)))
        : of(undefined),
    }).pipe(
      catchError(() => {
        this.error.set('Usage could not be refreshed. Displayed values may be out of date.')
        return EMPTY
      })
    )
  }

  private applyUsage(caseId: string, usage: UsageLoad): void {
    if (this.caseId() !== caseId || this.saving()) return
    this.run.set(usage.run)
    if (usage.total) this.total.set(usage.total)
    if (usage.promptCache !== undefined) {
      this.now.set(Date.now())
      this.promptCache.set(usage.promptCache)
    }
    this.error.set(null)
  }

  continue(limit: PausedCostDto): void {
    if (!this.configuration.enabled() || !limit.canContinue || this.saving()) return
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
    if (!this.configuration.enabled() || !this.canWrite() || this.saving()) return
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
