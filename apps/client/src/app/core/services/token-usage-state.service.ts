import { DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { forkJoin } from 'rxjs'
import {
  ModelUsageSummaryDto,
  TimeSeriesPointDto,
  TokenUsageAggregationDto,
  TokenUsageApiService,
  TokenUsageSeriesDto,
} from './token-usage-api.service'

export interface TokenUsageFilters {
  from: string | null // yyyy-MM-dd
  to: string | null // yyyy-MM-dd (exclusive)
}

function pad2(n: number): string {
  return `${n}`.padStart(2, '0')
}

function toYyyyMmDd(d: Date): string {
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`
}

function firstDayOfCurrentMonthUtc(now = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1))
}

function firstDayOfNextMonthUtc(now = new Date()): Date {
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() + 1, 1))
}

@Injectable({ providedIn: 'root' })
export class TokenUsageStateService {
  private readonly api = inject(TokenUsageApiService)
  private readonly destroyRef = inject(DestroyRef)

  private readonly defaultFrom = toYyyyMmDd(firstDayOfCurrentMonthUtc())
  private readonly defaultTo = toYyyyMmDd(firstDayOfNextMonthUtc())

  /** Draft filters: edited by the UI before Apply */
  readonly draftFilters = signal<TokenUsageFilters>({ from: this.defaultFrom, to: this.defaultTo })

  /** Applied filters: last used for fetch */
  readonly appliedFilters = signal<TokenUsageFilters>({ from: this.defaultFrom, to: this.defaultTo })

  readonly loading = signal(false)
  readonly error = signal<string | null>(null)

  readonly aggregation = signal<TokenUsageAggregationDto | null>(null)
  readonly seriesResponse = signal<TokenUsageSeriesDto | null>(null)
  /** Convenience: just the points array from the series response */
  readonly series = signal<TimeSeriesPointDto[] | null>(null)
  readonly tokenDataPartial = signal<boolean>(false)

  /** Aggregated view by underlying modelId (across agents/providers) */
  readonly aggregationByModelId = signal<ModelUsageSummaryDto[] | null>(null)

  /** Aggregated daily series by underlying modelId */
  readonly seriesByModelId = signal<TimeSeriesPointDto[] | null>(null)

  init(): void {
    this.reload()
  }

  setDraftFrom(value: string | null): void {
    this.draftFilters.update((prev) => ({ ...prev, from: value }))
  }

  setDraftTo(value: string | null): void {
    this.draftFilters.update((prev) => ({ ...prev, to: value }))
  }

  clearDraftFrom(): void {
    this.setDraftFrom(null)
  }

  clearDraftTo(): void {
    this.setDraftTo(null)
  }

  apply(): void {
    this.appliedFilters.set(this.draftFilters())
    this.reload()
  }

  private addNullableTokens(a: number | null, b: number | null): number | null {
    if (a === null && b === null) return null
    return (a ?? 0) + (b ?? 0)
  }

  private aggregateByModelId(rows: ModelUsageSummaryDto[]): ModelUsageSummaryDto[] {
    const map = new Map<string, ModelUsageSummaryDto>()

    for (const r of rows) {
      const key = r.modelId
      const prev = map.get(key)
      if (!prev) {
        map.set(key, {
          agentName: 'All agents',
          providerName: 'all',
          modelId: r.modelId,
          promptTokens: r.promptTokens,
          completionTokens: r.completionTokens,
          totalTokens: r.totalTokens,
          callCount: r.callCount ?? 0,
          cost: r.cost ?? 0,
        })
      } else {
        prev.promptTokens = this.addNullableTokens(prev.promptTokens, r.promptTokens)
        prev.completionTokens = this.addNullableTokens(prev.completionTokens, r.completionTokens)
        prev.totalTokens = this.addNullableTokens(prev.totalTokens, r.totalTokens)
        prev.callCount += r.callCount ?? 0
        prev.cost = (prev.cost ?? 0) + (r.cost ?? 0)
      }
    }

    return [...map.values()].sort((a, b) => (b.totalTokens ?? 0) - (a.totalTokens ?? 0))
  }

  private aggregateSeriesByModelId(points: TimeSeriesPointDto[]): TimeSeriesPointDto[] {
    const map = new Map<string, TimeSeriesPointDto>()

    for (const p of points) {
      const key = `${p.date}|${p.modelId}`
      const prev = map.get(key)
      if (!prev) {
        map.set(key, {
          date: p.date,
          agentName: 'All agents',
          providerName: 'all',
          modelId: p.modelId,
          promptTokens: p.promptTokens,
          completionTokens: p.completionTokens,
          totalTokens: p.totalTokens,
          callCount: p.callCount ?? 0,
          cost: p.cost ?? 0,
        })
      } else {
        prev.promptTokens = this.addNullableTokens(prev.promptTokens, p.promptTokens)
        prev.completionTokens = this.addNullableTokens(prev.completionTokens, p.completionTokens)
        prev.totalTokens = this.addNullableTokens(prev.totalTokens, p.totalTokens)
        prev.callCount += p.callCount ?? 0
        prev.cost = (prev.cost ?? 0) + (p.cost ?? 0)
      }
    }

    return [...map.values()].sort((a, b) => (a.date < b.date ? -1 : 1))
  }

  reload(): void {
    const { from, to } = this.appliedFilters()

    this.loading.set(true)
    this.error.set(null)

    forkJoin({
      aggregation: this.api.getTokenUsage({ from, to }),
      series: this.api.getTokenUsageSeries({ from, to }),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ aggregation, series }) => {
          this.aggregation.set(aggregation)
          this.seriesResponse.set(series)
          const points = series.points ?? []
          this.series.set(points)
          this.tokenDataPartial.set(aggregation.tokenDataPartial || series.tokenDataPartial)

          const byModelId = this.aggregateByModelId(aggregation.models ?? [])
          this.aggregationByModelId.set(byModelId)
          this.seriesByModelId.set(this.aggregateSeriesByModelId(points))

          this.loading.set(false)
        },
        error: (err: unknown) => {
          this.loading.set(false)
          this.aggregation.set(null)
          this.seriesResponse.set(null)
          this.series.set(null)
          this.tokenDataPartial.set(false)
          this.aggregationByModelId.set(null)
          this.seriesByModelId.set(null)
          this.error.set(err instanceof Error ? err.message : 'Failed to load token usage')
        },
      })
  }
}
