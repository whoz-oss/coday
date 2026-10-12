import { Injectable, NgZone, OnDestroy, inject, signal, computed } from '@angular/core'
import { HttpClient, HttpHeaders } from '@angular/common/http'
import { Subject, catchError, of, takeUntil } from 'rxjs'
import { AGENTOS_API_ORIGIN } from './case-event-state.service'
import { generateCorrelationId } from './factory-api.service'
import { AgentAttempt } from './models'

/** Subset of the AgentOS run-cost response used by the cockpit. */
export interface RunCostDto {
  caseId: string
  since: string
  cost: number
  unknownCostCount: number
  active: boolean
  liveTokens: number
  paused: boolean
  runCostThreshold?: number | null
}

/** Run cost from AgentOS and attempt duration from Factory timestamps. */
export interface AttemptMetrics {
  caseId: string
  /** Cost reported by run-cost, not the total workflow cost. */
  runCost: number | null
  /** A partial cost is a lower bound. */
  runCostPartial: boolean
  /** Completed attempt duration, not total case duration. */
  attemptDurationSec: number | null
  attemptStartedAt: string | null
  attemptCompletedAt: string | null
  attemptNumber: number
}

export type MetricsLoadState = 'idle' | 'loading' | 'ready' | 'error'

/** Missing, invalid or reversed timestamps do not imply a zero duration. */
export function computeAttemptDuration(attempt: AgentAttempt): number | null {
  const { startedAt, completedAt } = attempt
  if (!startedAt || !completedAt) return null
  const start = Date.parse(startedAt)
  const end = Date.parse(completedAt)
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null
  const sec = (end - start) / 1000
  return sec >= 0 ? Math.round(sec) : null
}

export function mapToAttemptMetrics(
  caseId: string,
  run: RunCostDto | null,
  attempt: AgentAttempt | null
): AttemptMetrics {
  return {
    caseId,
    runCost: run?.cost ?? null,
    runCostPartial: (run?.unknownCostCount ?? 0) > 0,
    attemptDurationSec: attempt ? computeAttemptDuration(attempt) : null,
    attemptStartedAt: attempt?.startedAt ?? null,
    attemptCompletedAt: attempt?.completedAt ?? null,
    attemptNumber: attempt?.attemptNumber ?? 0,
  }
}

/** Component-scoped state for the selected attempt's metrics. */
@Injectable()
export class CaseMetricsStateService implements OnDestroy {
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)
  private readonly apiOrigin = inject(AGENTOS_API_ORIGIN)
  private readonly cancel$ = new Subject<void>()

  readonly state = signal<MetricsLoadState>('idle')
  readonly metrics = signal<AttemptMetrics | null>(null)
  readonly isLoading = computed(() => this.state() === 'loading')
  readonly hasMetrics = computed(() => this.state() === 'ready' && this.metrics() !== null)

  /** Cancel the previous request before loading the selected case. */
  activate(caseId: string, attempt: AgentAttempt | null): void {
    this.cancel$.next()
    this.state.set('loading')
    this.metrics.set(null)

    const url = `${this.apiOrigin}/api/cases/${encodeURIComponent(caseId)}/run-cost`
    const headers = new HttpHeaders().set('X-Correlation-Id', generateCorrelationId())

    this.http
      .get<RunCostDto>(url, { headers })
      .pipe(
        takeUntil(this.cancel$),
        // Preserve the attempt duration even when the cost is unavailable.
        catchError(() => of(null as RunCostDto | null))
      )
      .subscribe({
        next: (run) => {
          this.zone.run(() => {
            this.metrics.set(mapToAttemptMetrics(caseId, run, attempt))
            this.state.set('ready')
          })
        },
      })
  }

  clear(): void {
    this.cancel$.next()
    this.state.set('idle')
    this.metrics.set(null)
  }

  ngOnDestroy(): void {
    this.cancel$.next()
    this.cancel$.complete()
  }
}
