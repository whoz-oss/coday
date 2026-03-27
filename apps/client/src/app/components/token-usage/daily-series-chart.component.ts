import { DecimalPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, computed, Input, signal } from '@angular/core'

export interface DailySeriesChartPoint {
  date: string // yyyy-MM-dd
  /** null when token counts were not collected for this day */
  promptTokens: number | null
  /** null when token counts were not collected for this day */
  completionTokens: number | null
  /** optional: backend may not provide it */
  cost?: number
  /** optional: backend may not provide it */
  callCount?: number
}

interface TooltipData {
  date: string
  cost: number
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  callCount: number
}

@Component({
  selector: 'app-daily-series-chart',
  standalone: true,
  templateUrl: './daily-series-chart.component.html',
  styleUrl: './daily-series-chart.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DailySeriesChartComponent {
  /**
   * Points must be sorted by date ascending.
   * - Cost is expected to be largely available historically.
   * - Token values can be null (not collected yet) and will be rendered as a dashed "stub".
   */
  @Input() points: DailySeriesChartPoint[] = []

  /** SVG viewBox geometry */
  private readonly width = 720
  private readonly height = 240
  private readonly paddingX = 12
  private readonly paddingY = 16
  private readonly bottomAxisH = 48

  readonly viewBox = `${0} ${0} ${this.width} ${this.height}`

  private readonly innerW = this.width - this.paddingX * 2
  private readonly innerH = this.height - this.paddingY * 2 - this.bottomAxisH

  private readonly barGap = 6
  private readonly groupGap = 14

  readonly safePoints = computed(() => (this.points ?? []).filter((p) => !!p.date))

  readonly maxCost = computed(() => Math.max(0, ...this.safePoints().map((p) => p.cost ?? 0)))

  readonly maxTokens = computed(() => {
    const vals = this.safePoints()
      .map((p) => {
        const prompt = p.promptTokens ?? null
        const completion = p.completionTokens ?? null
        if (prompt === null && completion === null) return null
        return (prompt ?? 0) + (completion ?? 0)
      })
      .filter((v): v is number => v !== null)

    return Math.max(0, ...vals)
  })

  readonly tokenCoverageLabel = computed(() => {
    const pts = this.safePoints()
    if (pts.length === 0) return ''
    const have = pts.filter((p) => p.promptTokens !== null || p.completionTokens !== null).length
    return `Tokens available for ${have}/${pts.length} days`
  })

  readonly maxCostLabel = computed(() => '$' + (new DecimalPipe('en-US').transform(this.maxCost(), '1.2-2') ?? '0.00'))

  readonly maxTokensLabel = computed(() => new DecimalPipe('en-US').transform(this.maxTokens(), '1.0-2') ?? '0')

  /** Tooltip state (HTML overlay, not SVG <title>) */
  readonly tooltip = signal<{
    visible: boolean
    x: number
    y: number
    data: TooltipData | null
  }>({ visible: false, x: 0, y: 0, data: null })

  private toTooltipData(p: DailySeriesChartPoint): TooltipData {
    const prompt = p.promptTokens
    const completion = p.completionTokens
    const total = prompt === null && completion === null ? null : (prompt ?? 0) + (completion ?? 0)

    return {
      date: p.date,
      cost: p.cost ?? 0,
      promptTokens: prompt,
      completionTokens: completion,
      totalTokens: total,
      callCount: p.callCount ?? 0,
    }
  }

  showTooltip(evt: MouseEvent, p: DailySeriesChartPoint): void {
    // Position relative to the chart container (closest .chart)
    const host = (evt.currentTarget as Element | null)?.closest('.chart') as HTMLElement | null
    const rect = host?.getBoundingClientRect()

    // Fallback to viewport coords if we can't resolve container.
    const rawX = rect ? evt.clientX - rect.left : evt.clientX
    const rawY = rect ? evt.clientY - rect.top : evt.clientY

    // Clamp so the tooltip stays within the chart container.
    // (We approximate tooltip size; good enough and avoids layout reads on every mousemove.)
    const approxW = 320
    const approxH = 170
    const pad = 12

    const maxX = rect ? Math.max(0, rect.width - approxW - pad) : rawX
    const maxY = rect ? Math.max(0, rect.height - approxH - pad) : rawY

    const x = rect ? Math.min(Math.max(0, rawX), maxX) : rawX
    const y = rect ? Math.min(Math.max(0, rawY), maxY) : rawY

    this.tooltip.set({ visible: true, x, y, data: this.toTooltipData(p) })
  }

  hideTooltip(): void {
    this.tooltip.update((t) => ({ ...t, visible: false }))
  }

  private formatTokens(value: number | null): string {
    return value === null ? '—' : new DecimalPipe('en-US').transform(value, '1.0-2') ?? '0'
  }

  private formatCost(value: number | null | undefined): string {
    return '$' + (new DecimalPipe('en-US').transform(value ?? 0, '1.2-2') ?? '0.00')
  }

  tooltipLines = computed(() => {
    const t = this.tooltip()
    const d = t.data
    if (!t.visible || !d) return [] as Array<{ label: string; value: string }>

    return [
      { label: 'Cost', value: this.formatCost(d.cost) },
      { label: 'Prompt tokens', value: this.formatTokens(d.promptTokens) },
      { label: 'Completion tokens', value: this.formatTokens(d.completionTokens) },
      { label: 'Total tokens', value: this.formatTokens(d.totalTokens) },
      { label: 'Calls', value: `${d.callCount}` },
    ]
  })

  readonly bars = computed(() => {
    const pts = this.safePoints()
    const n = pts.length
    if (n === 0)
      return [] as Array<{
        date: string
        point: DailySeriesChartPoint
        xGroup: number
        groupW: number
        xCost: number
        w: number
        costH: number
        xTokens: number
        promptH: number | null
        completionH: number | null
        tokensStubH: number | null
      }>

    // When the period is long, the naive group width can become negative.
    // Clamp widths so SVG rects never get a negative width.
    const groupWRaw = (this.innerW - (n - 1) * this.groupGap) / n
    const groupW = Math.max(2, groupWRaw)
    const barWRaw = (groupW - this.barGap) / 2
    const barW = Math.max(1, barWRaw)

    const maxCost = this.maxCost() || 1
    const maxTokens = this.maxTokens() || 1

    const stubH = 45 // px: minimal dashed bar for "tokens missing"

    return pts.map((p, i) => {
      const xGroup = this.paddingX + i * (groupW + this.groupGap)

      const cost = p.cost ?? 0
      const costH = (cost / maxCost) * this.innerH

      const prompt = p.promptTokens
      const completion = p.completionTokens

      const hasTokens = prompt !== null || completion !== null

      const promptVal = prompt ?? 0
      const completionVal = completion ?? 0
      const promptH = hasTokens ? (promptVal / maxTokens) * this.innerH : null
      const completionH = hasTokens ? (completionVal / maxTokens) * this.innerH : null

      return {
        date: p.date,
        point: p,
        xGroup,
        groupW,
        xCost: xGroup,
        w: barW,
        costH,
        xTokens: xGroup + barW + this.barGap,
        promptH,
        completionH,
        tokensStubH: hasTokens ? null : stubH,
      }
    })
  })

  readonly axisLabels = computed(() => {
    const pts = this.safePoints()
    const n = pts.length
    if (n === 0) return [] as Array<{ x: number; label: string }>

    // Avoid overcrowding: aim for ~8 labels max.
    const maxLabels = 8
    const step = Math.max(1, Math.ceil(n / maxLabels))

    const groupWRaw = (this.innerW - (n - 1) * this.groupGap) / n
    const groupW = Math.max(2, groupWRaw)

    return pts
      .map((p, i) => ({ p, i }))
      .filter(({ i }) => i % step === 0 || i === n - 1)
      .map(({ p, i }) => {
        const xGroup = this.paddingX + i * (groupW + this.groupGap)
        const x = xGroup + groupW / 2
        // Show MM-dd
        const label = p.date.slice(5)
        return { x, label }
      })
  })

  /** Top of plot area (y origin). */
  readonly yTop = this.paddingY
  /** Baseline for bars. */
  readonly yBase = this.paddingY + this.innerH
  /** Axis label baseline. */
  readonly yAxis = this.yBase + 28
}
