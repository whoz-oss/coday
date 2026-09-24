import { FactoryRunPhase } from '../../services/factory-api.service'

export type TimelineScale = 'linear' | 'sqrt' | 'log'
export type TimelineStatus = 'success' | 'running' | 'failed'
export type CompactGrowDirection = 'right' | 'center' | 'left'

export type BarGeometryResult =
  | { kind: 'normal'; left: number; width: number }
  | { kind: 'compact'; anchorPercent: number; growDirection: CompactGrowDirection }

/** Percentage width below which a bar is rendered as a compact fixed-pixel marker. */
export const COMPACT_THRESHOLD_WIDTH = 2

export interface TimelinePhase {
  phase: FactoryRunPhase
  phaseIndex: number
  offset: number
  width: number
  startOffsetMs: number
  durationMs: number
  status: TimelineStatus
}

export interface TimelineLane {
  actor: string
  kind: 'agent' | 'orchestrator'
  phases: TimelinePhase[]
}

export interface PositionedTimelinePhase extends TimelinePhase {
  level: number
}
export interface IntervalPartition {
  phases: PositionedTimelinePhase[]
  levelCount: number
}
export interface TimelineTick {
  offset: number
  label: string
}
export interface TimelineModel {
  origin: number
  end: number
  span: number
  lanes: TimelineLane[]
  ticks: TimelineTick[]
  activePhaseIndex: number | null
}
export interface BarGeometry {
  left: number
  width: number
}

export function formatTimelineDuration(milliseconds: number): string {
  if (milliseconds < 1000) return `${Math.round(milliseconds)}ms`
  if (milliseconds < 60000) {
    const seconds = milliseconds / 1000
    return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`
  }
  const minutes = Math.floor(milliseconds / 60000)
  const seconds = Math.round((milliseconds % 60000) / 1000)
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

export function scalePosition(time: number, total: number, scale: TimelineScale): number {
  const ratio = Math.min(1, Math.max(0, time / Math.max(total, 1)))
  if (scale === 'sqrt') return Math.sqrt(ratio)
  if (scale === 'log') return Math.log1p(ratio * 99) / Math.log(100)
  return ratio
}

/**
 * Computes bar geometry as percentages [0, 100] within the canvas.
 * `left` is clamped to [0, 100 - minimumWidth] so there is always room for at
 * least `minimumWidth` before the right edge.  `width` is then clamped so
 * left + width ≤ 100.
 */
/**
 * Determines whether a bar should render as a compact fixed-pixel marker or a normal
 * percentage-width bar, and computes its anchor/direction for the compact case.
 *
 * A bar is compact when its natural percentage width (after scaling) would be below
 * COMPACT_THRESHOLD_WIDTH (2%). Compact bars use a fixed 8px CSS width and are anchored
 * to avoid overflowing the plot boundaries.
 *
 * Anchor direction:
 * - left < 15%: grow right (anchor at left edge)
 * - left > 85%: grow left (anchor at right edge, shift left by 100%)
 * - otherwise: centered (shift left by 50%)
 */
export function compactBarGeometry(start: number, end: number, total: number, scale: TimelineScale): BarGeometryResult {
  const rawLeft = scalePosition(start, total, scale) * 100
  const rawRight = scalePosition(end, total, scale) * 100
  const naturalWidth = rawRight - rawLeft

  if (naturalWidth >= COMPACT_THRESHOLD_WIDTH) {
    // Normal bar — fall through to barGeometry for clamping
    const geometry = barGeometry(start, end, total, scale)
    return { kind: 'normal', left: geometry.left, width: geometry.width }
  }

  // Compact marker: anchor based on position within the plot
  const anchorPercent = Math.min(Math.max(rawLeft, 0), 100)
  let growDirection: CompactGrowDirection
  if (anchorPercent < 15) {
    growDirection = 'right'
  } else if (anchorPercent > 85) {
    growDirection = 'left'
  } else {
    growDirection = 'center'
  }

  return { kind: 'compact', anchorPercent, growDirection }
}

export function barGeometry(
  start: number,
  end: number,
  total: number,
  scale: TimelineScale,
  minimumWidth = 0.45
): BarGeometry {
  const rawLeft = scalePosition(start, total, scale) * 100
  const rawRight = scalePosition(end, total, scale) * 100
  const left = Math.min(Math.max(rawLeft, 0), 100 - minimumWidth)
  const width = Math.min(Math.max(rawRight - rawLeft, minimumWidth), 100 - left)
  return { left, width }
}

/**
 * Returns the effective temporal span for a run, taking into account the
 * recorded phase span from the timeline model AND any live elapsed time for a
 * running run.  Phases and the ELAPSED playhead must share the same coordinate
 * system, so both are scaled against this single value.
 */
export function effectiveSpan(modelSpan: number, runningElapsedMs: number | null): number {
  return runningElapsedMs != null ? Math.max(modelSpan, runningElapsedMs) : modelSpan
}

/**
 * Greedily partitions visual bar intervals into the first non-overlapping level.
 * `visualGapPercent` reserves a visible 0.8% gap, so touching source intervals
 * remain distinguishable after minimum-width expansion and at every scale.
 */
export function partitionTimelineIntervals(
  phases: TimelinePhase[],
  total: number,
  scale: TimelineScale,
  visualGapPercent = 0.8
): IntervalPartition {
  const levels: number[] = []
  const positioned = [...phases]
    .sort((left, right) => left.startOffsetMs - right.startOffsetMs || left.phaseIndex - right.phaseIndex)
    .map((phase) => {
      const geometry = barGeometry(phase.startOffsetMs, phase.startOffsetMs + phase.durationMs, total, scale)
      const end = geometry.left + geometry.width
      let level = levels.findIndex((occupiedUntil) => geometry.left >= occupiedUntil + visualGapPercent)
      if (level === -1) {
        level = levels.length
        levels.push(end)
      } else {
        levels[level] = end
      }
      return { ...phase, level }
    })
  return { phases: positioned, levelCount: Math.max(levels.length, 1) }
}

export function buildTimelineTicks(span: number): TimelineTick[] {
  const rawStep = Math.max(span / 6, 1)
  const step =
    [100, 500, 1000, 5000, 10000, 30000, 60000, 300000, 600000, 1800000].find((candidate) => candidate >= rawStep) ??
    rawStep
  return Array.from({ length: Math.floor(span / step) + 1 }, (_, index) => ({
    offset: ((index * step) / span) * 100,
    label: formatTimelineDuration(index * step),
  }))
}

export function phaseActor(phase: FactoryRunPhase): string {
  if (phase.phaseKind !== 'agent') return 'orchestrator'
  const selected = phase.facts?.['agentsSelected']
  if (Array.isArray(selected) && typeof selected[0] === 'string' && selected[0]) return selected[0]
  return typeof phase.facts?.['agentName'] === 'string' && phase.facts['agentName'] ? phase.facts['agentName'] : 'agent'
}

export function timelineStatus(status: string): TimelineStatus {
  if (status === 'running') return 'running'
  return status === 'fail' || status === 'failed' || status === 'crashed' ? 'failed' : 'success'
}

function phaseTime(phase: FactoryRunPhase): number | null {
  const time = phase.startedAt ? Date.parse(phase.startedAt) : NaN
  return Number.isFinite(time) ? time : null
}

export function buildTimeline(phases: FactoryRunPhase[]): TimelineModel {
  const timed = phases
    .map((phase, phaseIndex) => ({ phase, phaseIndex, start: phaseTime(phase) }))
    .filter((item): item is { phase: FactoryRunPhase; phaseIndex: number; start: number } => item.start !== null)
  if (!timed.length) return { origin: 0, end: 0, span: 0, lanes: [], ticks: [], activePhaseIndex: null }

  const origin = Math.min(...timed.map((item) => item.start))
  const end = Math.max(...timed.map((item) => item.start + Math.max(item.phase.durationMs ?? 0, 0)), origin + 1)
  const span = end - origin
  const groups = new Map<string, TimelineLane>()
  for (const item of timed) {
    const actor = phaseActor(item.phase)
    const lane = groups.get(actor) ?? {
      actor,
      kind: item.phase.phaseKind === 'agent' ? 'agent' : 'orchestrator',
      phases: [],
    }
    const durationMs = Math.max(item.phase.durationMs ?? 0, 0)
    const offset = ((item.start - origin) / span) * 100
    lane.phases.push({
      phase: item.phase,
      phaseIndex: item.phaseIndex,
      offset,
      width: Math.min(Math.max((durationMs / span) * 100, 0.45), 100 - offset),
      startOffsetMs: item.start - origin,
      durationMs,
      status: timelineStatus(item.phase.status),
    })
    groups.set(actor, lane)
  }
  const lanes = [...groups.values()].sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'orchestrator' ? -1 : 1
    return left.actor.localeCompare(right.actor)
  })
  const activePhaseIndex = timed.find((item) => item.phase.status === 'running')?.phaseIndex ?? null
  return { origin, end, span, lanes, ticks: buildTimelineTicks(span), activePhaseIndex }
}
