import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatTooltipModule } from '@angular/material/tooltip'
import { RunStatus, TimelineBlock, TimelineLane } from '../../core/models'
import { DurationPipe } from '../../shared/pipes/format.pipes'

/** Chronologie en couloirs : un couloir par acteur (humain, workspace, agents). */
@Component({
  selector: 'sf-agent-timeline',
  imports: [MatIconModule, MatTooltipModule, DurationPipe],
  templateUrl: './agent-timeline.component.html',
  styleUrl: './agent-timeline.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentTimelineComponent {
  readonly lanes = input.required<TimelineLane[]>()
  readonly nowSec = input.required<number>()
  /** Run status; the live clock only advances while it is `running`. */
  readonly status = input<RunStatus>('running')
  /** ISO instant the run started from, used to derive the live elapsed time. */
  readonly startedAt = input<string>()
  readonly tickEverySec = input(120)
  readonly selected = input<string>()
  readonly blockSelect = output<TimelineBlock>()

  /** Wall-clock instant, refreshed once per second while the run is running. */
  private readonly liveNowMs = signal(Date.now())
  /** Instant the component was created, used as the fallback clock anchor. */
  private readonly createdMs = Date.now()

  constructor() {
    // Local clock: start a 1s interval only while running, and tear it down both
    // when the status becomes terminal and when the component is destroyed
    // (`onCleanup` runs on every re-run and on destroy — no leaked timer).
    effect((onCleanup) => {
      if (this.status() !== 'running') return
      const handle = setInterval(() => this.liveNowMs.set(Date.now()), 1000)
      onCleanup(() => clearInterval(handle))
    })
  }

  /**
   * Effective "now" in seconds.
   *
   * While running the local clock advances each second. Once the status is
   * terminal the interval is torn down, so `liveNowMs` stops changing and the
   * value freezes on the last tick (never retreating below the input value).
   * A `queued` run ignores wall-clock elapsed time entirely.
   */
  protected readonly effectiveNowSec = computed(() => {
    const base = this.nowSec()
    if (this.status() === 'queued') return base
    const started = this.startedAt()
    const startedMs = started ? Date.parse(started) : Number.NaN
    const elapsedSec = Number.isFinite(startedMs)
      ? (this.liveNowMs() - startedMs) / 1000
      : base + (this.liveNowMs() - this.createdMs) / 1000
    return Math.max(base, elapsedSec)
  })

  /** Visible execution lanes, with any running block stretched to the effective now. */
  protected readonly effectiveLanes = computed(() => {
    const now = this.effectiveNowSec()
    const isVisible = (block: TimelineBlock): boolean => block.status !== 'pending' && block.status !== 'ready'
    const extend = (block: TimelineBlock): TimelineBlock =>
      block.status === 'running' && now > block.endSec ? { ...block, endSec: now } : block
    return this.lanes().map((lane) => ({
      ...lane,
      request: lane.request && isVisible(lane.request) ? extend(lane.request) : undefined,
      blocks: lane.blocks.filter(isVisible).map(extend),
    }))
  })

  /** Fin d'axe : maintenant + ~5 %, arrondi au tick suivant */
  protected readonly axisEnd = computed(() => {
    const step = this.tickEverySec()
    return Math.ceil((this.effectiveNowSec() * 1.04) / step) * step
  })

  protected readonly ticks = computed(() => {
    const out: { sec: number; label: string }[] = []
    for (let s = 0; s < this.axisEnd(); s += this.tickEverySec()) {
      out.push({ sec: s, label: s === 0 ? '0s' : `${s / 60}m` })
    }
    return out
  })

  protected pct(sec: number): number {
    return (sec / this.axisEnd()) * 100
  }

  protected widthPct(b: TimelineBlock): number {
    return Math.max(this.pct(b.endSec - b.startSec), 3.5)
  }

  protected iconFor(lane: TimelineLane): string {
    return lane.kind === 'human' ? 'person' : lane.kind === 'workspace' ? 'terminal' : 'smart_toy'
  }

  protected statusIcon(block: TimelineBlock): string | null {
    switch (block.status) {
      case 'completed':
        return 'check'
      case 'failed':
        return 'error'
      case 'indeterminate':
        return 'help'
      case 'cancelled':
        return 'cancel'
      case 'waiting_human':
        return 'person_alert'
      default:
        return null
    }
  }

  protected statusLabel(block: TimelineBlock): string {
    switch (block.status) {
      case 'pending':
        return 'en attente'
      case 'ready':
        return 'prêt, en attente de prise en charge'
      case 'running':
        return 'en cours'
      case 'waiting_human':
        return 'en attente d’une intervention humaine'
      case 'completed':
        return 'terminé'
      case 'failed':
        return 'échoué'
      case 'indeterminate':
        return 'état indéterminé'
      case 'cancelled':
        return 'annulé'
    }
  }
}
