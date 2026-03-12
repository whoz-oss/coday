import { DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { forkJoin } from 'rxjs'
import {
  ModelUsageSummaryDto,
  TimeSeriesPointDto,
  TokenUsageAggregationDto,
  TokenUsageApiService,
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
  readonly series = signal<TimeSeriesPointDto[] | null>(null)

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

  private aggregateByModelId(rows: ModelUsageSummaryDto[]): ModelUsageSummaryDto[] {
    const map = new Map<string, ModelUsageSummaryDto>()

    for (const r of rows) {
      const key = r.modelId
      const prev = map.get(key)
      if (!prev) {
        map.set(key, {
          modelName: 'All agents',
          providerName: 'all',
          modelId: r.modelId,
          promptTokens: r.promptTokens ?? 0,
          completionTokens: r.completionTokens ?? 0,
          totalTokens: r.totalTokens ?? 0,
          callCount: r.callCount ?? 0,
          cost: r.cost ?? 0,
        })
      } else {
        prev.promptTokens += r.promptTokens ?? 0
        prev.completionTokens += r.completionTokens ?? 0
        prev.totalTokens += r.totalTokens ?? 0
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
          modelName: 'All agents',
          providerName: 'all',
          modelId: p.modelId,
          promptTokens: p.promptTokens ?? 0,
          completionTokens: p.completionTokens ?? 0,
          totalTokens: p.totalTokens ?? 0,
          callCount: p.callCount ?? 0,
          cost: p.cost ?? 0,
        })
      } else {
        prev.promptTokens += p.promptTokens ?? 0
        prev.completionTokens += p.completionTokens ?? 0
        prev.totalTokens += p.totalTokens ?? 0
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
          this.series.set(series)

          const byModelId = this.aggregateByModelId(aggregation.models ?? [])
          this.aggregationByModelId.set(byModelId)
          this.seriesByModelId.set(this.aggregateSeriesByModelId(series))

          this.loading.set(false)
        },
        error: (err: unknown) => {
          this.loading.set(false)
          this.aggregation.set(null)
          this.series.set(null)
          this.aggregationByModelId.set(null)
          this.seriesByModelId.set(null)
          this.error.set(err instanceof Error ? err.message : 'Failed to load token usage')
        },
      })
  }
}
