import { DecimalPipe } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { ModelUsageSummaryDto, TimeSeriesPointDto } from '../../core/services/token-usage-api.service'
import { DailySeriesChartComponent, DailySeriesChartPoint } from './daily-series-chart.component'
import { Router } from '@angular/router'
import { TokenUsageStateService } from '../../core/services/token-usage-state.service'
import { ProjectStateService } from '../../core/services/project-state.service'
import { ApplyPipe, CallPipe } from '../../pipes/call-apply'

const EM_DASH = '\u2014'

@Component({
  selector: 'app-token-usage',
  standalone: true,
  imports: [FormsModule, CallPipe, ApplyPipe, DailySeriesChartComponent],
  templateUrl: './token-usage.component.html',
  styleUrl: './token-usage.component.scss',
})
export class TokenUsageComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly projectState = inject(ProjectStateService)
  protected readonly state = inject(TokenUsageStateService)

  protected readonly view = signal<'byAgent' | 'byModel'>('byModel')

  protected readonly rowsByAgent = computed(() => this.state.aggregation()?.models ?? [])
  protected readonly totalRow = computed(() => this.state.aggregation()?.total ?? null)

  protected readonly rowsByModel = computed(() => this.state.aggregationByModelId() ?? [])

  protected readonly chartPointsByAgent = computed<DailySeriesChartPoint[]>(() => {
    const points = this.state.series() ?? []
    return this.aggregateDailyPoints(points)
  })

  protected readonly chartPointsByModel = computed<DailySeriesChartPoint[]>(() => {
    const points = this.state.seriesByModelId() ?? []
    return this.aggregateDailyPoints(points)
  })

  protected readonly chartPoints = computed(() => {
    return this.view() === 'byAgent' ? this.chartPointsByAgent() : this.chartPointsByModel()
  })

  protected readonly rows = computed(() => {
    return this.view() === 'byAgent' ? this.rowsByAgent() : this.rowsByModel()
  })

  protected readonly isEmpty = computed(() => {
    const agg = this.state.aggregation()
    if (!agg) return false
    return (agg.models?.length ?? 0) === 0 && (agg.total?.callCount ?? 0) === 0
  })

  ngOnInit(): void {
    this.state.init()
  }

  protected onFromChange(value: string): void {
    this.state.setDraftFrom(value || null)
  }

  protected onToChange(value: string): void {
    this.state.setDraftTo(value || null)
  }

  protected clearFrom(): void {
    this.state.clearDraftFrom()
  }

  protected clearTo(): void {
    this.state.clearDraftTo()
  }

  protected apply(): void {
    this.state.apply()
  }

  private aggregateDailyPoints(points: TimeSeriesPointDto[]): DailySeriesChartPoint[] {
    const map = new Map<string, DailySeriesChartPoint>()

    for (const p of points) {
      const prev = map.get(p.date)
      if (!prev) {
        map.set(p.date, {
          date: p.date,
          promptTokens: p.promptTokens,
          completionTokens: p.completionTokens,
          cost: p.cost ?? 0,
          callCount: p.callCount ?? 0,
        })
      } else {
        // tokens: keep null if all are null; otherwise sum
        const add = (a: number | null, b: number | null) => {
          if (a === null && b === null) return null
          return (a ?? 0) + (b ?? 0)
        }
        prev.promptTokens = add(prev.promptTokens, p.promptTokens)
        prev.completionTokens = add(prev.completionTokens, p.completionTokens)
        prev.cost = (prev.cost ?? 0) + (p.cost ?? 0)
        prev.callCount = (prev.callCount ?? 0) + (p.callCount ?? 0)
      }
    }

    return [...map.values()].sort((a, b) => (a.date < b.date ? -1 : 1))
  }

  protected trackRow(_index: number, row: ModelUsageSummaryDto): string {
    return `${row.agentName}|${row.providerName}|${row.modelId}`
  }

  /** True when the whole period has no token data at all (total row has null tokens AND no cost). */
  protected readonly isMissingPeriod = computed(() => {
    if (this.state.loading()) return false
    const total = this.state.aggregation()?.total
    if (!total) return false

    // Token counts may be null for legacy periods where token tracking was disabled,
    // but cost can still be available. Only consider the period "missing" when both are absent.
    return total.totalTokens === null && (total.cost === null || total.cost === undefined)
  })

  /** True when token data is partial (some events had missing token counts, but not all). */
  protected readonly isPartialPeriod = computed(() => !this.isMissingPeriod() && this.state.tokenDataPartial())

  protected navigateBackToProject(): void {
    const projectName = this.projectState.getSelectedProjectId()
    void this.router.navigate(projectName ? ['project', projectName] : ['/'])
  }

  /** Pure helper: format a nullable token count as a localised string or dash. */
  protected formatTokenValue(value: number | null): string {
    return value === null ? EM_DASH : new DecimalPipe('en-US').transform(value, '1.0-2') ?? '0'
  }

  /** Pure helper: format a cost value (with $ prefix) or dash when the whole period is missing. */
  protected formatCostValue(isMissing: boolean, value: number | null | undefined): string {
    return isMissing ? EM_DASH : '$' + (new DecimalPipe('en-US').transform(value ?? 0, '1.2-2') ?? '0.00')
  }
}
