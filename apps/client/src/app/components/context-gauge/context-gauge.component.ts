import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core'
import { ContextGaugeStateService } from '../../core/services/context-gauge-state.service'

const MAX_ABOVE = 30
const MAX_INSIDE = 27
const MAX_FOAM = 40

function seeded(i: number, salt: number, range: number, min = 0): number {
  return min + ((i * 137 + salt * 31) % range)
}

@Component({
  selector: 'app-context-gauge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './context-gauge.component.html',
  styleUrl: './context-gauge.component.scss',
})
export class ContextGaugeComponent {
  protected readonly gaugeState = inject(ContextGaugeStateService)

  readonly sidenavOpen = input(false)

  readonly aboveBubbles = Array.from({ length: MAX_ABOVE }, (_, i) => ({
    delay: seeded(i, 1, 30) / 10,
    left: seeded(i, 2, 82, 5),
    dur: 2.5 + seeded(i, 3, 20) / 10,
    size: 1.5 + seeded(i, 4, 40) / 10,
    rise: 150 + seeded(i, 5, 150),
  }))

  readonly insideBubbles = Array.from({ length: MAX_INSIDE }, (_, i) => ({
    delay: seeded(i, 6, 20) / 10,
    left: seeded(i, 7, 80, 5),
    dur: 1.2 + seeded(i, 8, 15) / 10,
    size: 2 + seeded(i, 9, 35) / 10,
  }))

  readonly foamBubbles = Array.from({ length: MAX_FOAM }, (_, i) => ({
    delay: seeded(i, 10, 25) / 10,
    left: seeded(i, 11, 85, 3),
    dur: 1.5 + seeded(i, 12, 10) / 10,
    size: (3 + seeded(i, 13, 80) / 10) * 0.75,
    top: seeded(i, 14, 4) + 8,
  }))

  protected readonly displayRatio = computed(() => Math.min(100, this.gaugeState.ratio() * 100))

  protected readonly liquidColor = computed(() => {
    const r = this.gaugeState.ratio()
    if (r <= 0.5) {
      return `rgb(${Math.round(r * 2 * 255)}, 255, 0)`
    } else {
      return `rgb(255, ${Math.round((1 - (r - 0.5) * 2) * 255)}, 0)`
    }
  })

  protected readonly aboveBubbleCount = computed(() => Math.round(this.gaugeState.ratio() * MAX_ABOVE))

  protected readonly insideBubbleCount = computed(() => {
    const r = this.gaugeState.ratio()
    if (r < 0.5) return 0
    if (r < 0.65) return 5
    if (r < 0.8) return 13
    if (r < 0.9) return 20
    return MAX_INSIDE
  })

  protected readonly foamActive = computed(() => this.gaugeState.ratio() >= 0.5)
  protected readonly overflowing = computed(() => this.gaugeState.ratio() >= 0.9)

  protected readonly tooltipText = computed(() => {
    const pct = Math.round(this.gaugeState.ratio() * 100)
    const price = this.gaugeState.price().toFixed(4)
    return [
      `${this.gaugeState.inputTokens().toLocaleString()} / ${this.gaugeState.contextWindow().toLocaleString()} tokens (${pct}%)`,
      `Cost: $${price}`,
      `Iterations: ${this.gaugeState.iterations()}`,
      `Cache read: ${this.gaugeState.cacheReadTokens().toLocaleString()}`,
    ].join('\n')
  })
}
