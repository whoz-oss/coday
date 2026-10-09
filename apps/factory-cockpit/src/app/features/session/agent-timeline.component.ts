import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatTooltipModule } from '@angular/material/tooltip'
import { TimelineBlock, TimelineLane } from '../../core/models'
import { DurationPipe } from '../../shared/pipes/format.pipes'

/** Agent timeline with one lane per actor (human, workspace, agents). */
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
  /** True only while execution is actively progressing, never while waiting for a human. */
  readonly activelyRunning = input(false)
  /** ISO instant the run started from, used to derive the live elapsed time. */
  readonly startedAt = input<string>()
  readonly tickEverySec = input(120)
  readonly selected = input<string>()
  readonly blockSelect = output<TimelineBlock>()

  /** Local display clock. Its accumulated delta excludes every suspended interval. */
  private readonly liveNowMs = signal(Date.now())
  private readonly activeDeltaSec = signal(0)

  constructor() {
    // The projection supplies the last known active position. We only add local
    // deltas while execution is active, so human wait time is never invented as
    // active duration. A refresh/resume resets the local anchor to the new base.
    effect((onCleanup) => {
      this.nowSec()
      this.activeDeltaSec.set(0)
      if (!this.activelyRunning()) return
      let previous = Date.now()
      const handle = setInterval(() => {
        const current = Date.now()
        this.activeDeltaSec.update((elapsed) => elapsed + Math.max(current - previous, 0) / 1000)
        previous = current
        this.liveNowMs.set(current)
      }, 1000)
      onCleanup(() => clearInterval(handle))
    })
  }

  /**
   * Effective "now" in seconds.
   *
   * The value advances from the persisted projection position only during
   * active execution. `startedAt` remains available for wall-clock metadata but
   * is deliberately not used as active duration while timing aggregates are
   * incomplete.
   */
  protected readonly effectiveNowSec = computed(() => this.nowSec() + this.activeDeltaSec())

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

  /** Axis end: now + ~5%, rounded up to the next tick */
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
        return 'waiting'
      case 'ready':
        return 'ready, waiting to be picked up'
      case 'running':
        return 'running'
      case 'waiting_human':
        return 'waiting for human intervention'
      case 'completed':
        return 'completed'
      case 'failed':
        return 'failed'
      case 'indeterminate':
        return 'indeterminate state'
      case 'cancelled':
        return 'cancelled'
    }
  }
}
