import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { FactoryRunPhase } from '../../services/factory-api.service'
import {
  barGeometry,
  buildTimeline,
  compactBarGeometry,
  effectiveSpan,
  formatTimelineDuration,
  partitionTimelineIntervals,
  PositionedTimelinePhase,
} from './factory-run-timeline.models'

const BAR_HEIGHT = 30
const LEVEL_GAP = 8
const TRACK_PADDING = 16
const FIXED_SCALE = 'sqrt' as const
/** Percentage from the right edge at which a callout is considered near the boundary and should flip. */
const CALLOUT_FLIP_THRESHOLD = 82
/** Maximum left% at which a non-flipped callout label starts, so it stays inside the track. */
const CALLOUT_MAX_LEFT = 95

interface TimelineBar {
  item: PositionedTimelinePhase
  left: string
  width: string
  duration: string
  top: number
  wide: boolean
  /** True when the bar is rendered as a compact fixed-pixel marker (no percentage width). */
  compact: boolean
  /** CSS left% for compact marker anchor. Only meaningful when compact=true. */
  compactAnchor: string
  /** Grow direction for compact marker. Only meaningful when compact=true. */
  compactGrow: 'right' | 'center' | 'left'
  calloutTop: number
  calloutFlipped: boolean
  calloutOffset: string
}

@Component({
  selector: 'agentos-factory-run-timeline',
  imports: [],
  templateUrl: './factory-run-timeline.component.html',
  styleUrl: './factory-run-timeline.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FactoryRunTimelineComponent {
  readonly phases = input.required<FactoryRunPhase[]>()
  readonly selectedPhaseIndex = input.required<number>()
  readonly runningElapsedMs = input<number | null>(null)
  readonly phaseSelected = output<number>()

  protected readonly model = computed(() => buildTimeline(this.phases()))

  /**
   * Effective span accounts for both the recorded phase span and the current
   * elapsed time for a running run, so phases and the playhead share the same
   * coordinate system.
   */
  protected readonly span = computed(() => effectiveSpan(this.model().span, this.runningElapsedMs()))

  /** Playhead position in ms from origin — capped at effective span so it never escapes the canvas. */
  protected readonly nowOffset = computed(() => Math.min(this.runningElapsedMs() ?? this.model().span, this.span()))

  protected readonly nowLeft = computed(() => `${(this.scaledPosition(this.nowOffset()) * 100).toFixed(2)}%`)
  protected readonly nowLabel = computed(() => formatTimelineDuration(this.nowOffset()))

  protected readonly ticks = computed(() => {
    const ticks = this.model().ticks
    return ticks.map((tick, index) => {
      const tickMs = (tick.offset / 100) * this.model().span
      const pct = this.scaledPosition(tickMs) * 100
      return { ...tick, left: `${pct.toFixed(2)}%`, isLast: index === ticks.length - 1 }
    })
  })

  protected readonly lanes = computed(() =>
    this.model().lanes.map((lane) => {
      const partition = partitionTimelineIntervals(lane.phases, this.span(), FIXED_SCALE)
      const bars = partition.phases.map((item) => this.bar(item))
      return {
        ...lane,
        bars,
        height:
          TRACK_PADDING * 2 + partition.levelCount * BAR_HEIGHT + Math.max(0, partition.levelCount - 1) * LEVEL_GAP,
        spineTop: TRACK_PADDING + BAR_HEIGHT / 2,
      }
    })
  )

  protected select(phaseIndex: number): void {
    this.phaseSelected.emit(phaseIndex)
  }
  protected trackLane = (_: number, lane: { actor: string }) => lane.actor
  protected trackBar = (_: number, bar: TimelineBar) => bar.item.phaseIndex

  private bar(item: PositionedTimelinePhase): TimelineBar {
    // For the genuinely active phase (no phase_end yet), use the current playhead
    // position as its live end so the bar grows each tick and terminates at the
    // playhead.  Finished phases always use their persisted durationMs.
    const isActiveLive = this.model().activePhaseIndex === item.phaseIndex && this.runningElapsedMs() != null
    const liveEndMs = isActiveLive
      ? Math.max(this.nowOffset(), item.startOffsetMs)
      : item.startOffsetMs + item.durationMs
    const liveDurationMs = isActiveLive ? liveEndMs - item.startOffsetMs : item.durationMs

    const geomResult = compactBarGeometry(item.startOffsetMs, liveEndMs, this.span(), FIXED_SCALE)
    const top = TRACK_PADDING + item.level * (BAR_HEIGHT + LEVEL_GAP)

    if (geomResult.kind === 'compact') {
      // Compact fixed-pixel marker: no percentage width, positioned by anchor.
      // Callout position is based on the anchor percent.
      const anchor = geomResult.anchorPercent
      const flipped = anchor > CALLOUT_FLIP_THRESHOLD
      const calloutOffset = flipped
        ? `${(100 - anchor).toFixed(2)}%`
        : `${Math.min(anchor, CALLOUT_MAX_LEFT).toFixed(2)}%`
      return {
        item,
        left: `${anchor.toFixed(2)}%`, // used for percentage fallback
        width: '0%', // overridden by CSS compact class
        duration: formatTimelineDuration(liveDurationMs),
        top,
        wide: false,
        compact: true,
        compactAnchor: `${anchor.toFixed(2)}%`,
        compactGrow: geomResult.growDirection,
        calloutTop: top - 14,
        calloutFlipped: flipped,
        calloutOffset,
      }
    }

    // Normal bar
    const geometry = { left: geomResult.left, width: geomResult.width }
    const wide = geometry.width > 10
    const rightEdge = geometry.left + geometry.width
    const flipped = rightEdge > CALLOUT_FLIP_THRESHOLD

    // Non-flipped: label starts to the right of the bar, clamped so it starts ≤ CALLOUT_MAX_LEFT%.
    // Flipped: label ends to the left of the bar (right-anchored), always inside.
    const calloutOffset = flipped
      ? `${(100 - geometry.left).toFixed(2)}%`
      : `${Math.min(rightEdge, CALLOUT_MAX_LEFT).toFixed(2)}%`

    return {
      item,
      left: `${geometry.left.toFixed(2)}%`,
      width: `${geometry.width.toFixed(2)}%`,
      duration: formatTimelineDuration(liveDurationMs),
      top,
      wide,
      compact: false,
      compactAnchor: '0%',
      compactGrow: 'right',
      calloutTop: top - 14,
      calloutFlipped: flipped,
      calloutOffset,
    }
  }

  /** Converts a millisecond offset (from run origin) to a [0, 1] scaled position. */
  private scaledPosition(offsetMs: number): number {
    return this.span() ? barGeometry(offsetMs, offsetMs, this.span(), FIXED_SCALE, 0).left / 100 : 0
  }
}
