import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatTooltipModule } from '@angular/material/tooltip'
import { BlockSelection, TimelineBlock, TimelineLane } from '../../../core/models'
import { DurationPipe } from '../../../shared/pipes/format.pipes'

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
  /** Canonical ISO origin shared with the lane mapper. */
  readonly timelineOriginAt = input<string>()
  readonly tickEverySec = input(120)
  /** Exact timeline selection: attempt/execution wins over stable logical step. */
  readonly selected = input<BlockSelection | null>(null)
  readonly blockSelect = output<TimelineBlock>()

  /** Local wall clock; read by effectiveNowSec so signal writes refresh OnPush views. */
  private readonly clockMs = signal(Date.now())

  constructor() {
    effect((onCleanup) => {
      if (!this.activelyRunning()) return
      this.clockMs.set(Date.now())
      const handle = setInterval(() => this.clockMs.set(Date.now()), 1000)
      onCleanup(() => clearInterval(handle))
    })
  }

  /** Active runs never regress behind their persisted snapshot; terminal runs freeze on it. */
  protected readonly effectiveNowSec = computed(() => {
    const snapshotNow = Math.max(this.nowSec(), 0)
    if (!this.activelyRunning()) return snapshotNow
    const originMs = Date.parse(this.timelineOriginAt() ?? '')
    if (!Number.isFinite(originMs)) return snapshotNow
    const wallNow = Math.max((this.clockMs() - originMs) / 1000, 0)
    return Math.max(snapshotNow, wallNow)
  })

  /** Visible execution lanes, with any running block stretched to the effective now. */
  protected readonly effectiveLanes = computed(() => {
    const now = this.effectiveNowSec()
    // Ordinary planned steps remain absent. A durable outcome execution is a
    // real historical/current generation even before it starts, so keep it selectable.
    const isVisible = (block: TimelineBlock): boolean =>
      block.status !== 'pending' && block.status !== 'ready' || block.executionId !== undefined || block.attemptId !== undefined
    const extend = (block: TimelineBlock): TimelineBlock => {
      if (block.status !== 'running' || now <= block.endSec) return block
      const deltaSec = now - block.endSec
      return {
        ...block,
        endSec: now,
        ...(block.activeDurationSec !== undefined ? { activeDurationSec: block.activeDurationSec + deltaSec } : {}),
      }
    }
    return this.lanes().map((lane) => ({
      ...lane,
      request: lane.request && isVisible(lane.request) ? extend(lane.request) : undefined,
      blocks: lane.blocks.filter(isVisible).map(extend),
    }))
  })

  /** Axis end: now + ~5%, rounded up to the next tick */
  protected readonly axisEnd = computed(() => {
    const step = Math.max(this.tickEverySec(), 1)
    return Math.max(Math.ceil((this.effectiveNowSec() * 1.04) / step) * step, step)
  })

  protected readonly ticks = computed(() => {
    const out: { sec: number; label: string }[] = []
    const step = Math.max(this.tickEverySec(), 1)
    for (let s = 0; s < this.axisEnd(); s += step) {
      out.push({ sec: s, label: s === 0 ? '0s' : `${s / 60}m` })
    }
    return out
  })

  protected pct(sec: number): number {
    return (sec / this.axisEnd()) * 100
  }

  protected widthPct(b: TimelineBlock): number {
    return Math.min(Math.max(this.pct(b.endSec - b.startSec), 3.5), 100)
  }

  protected trackBlock(block: TimelineBlock): string {
    // The durable outcome execution is the unique history unit. An attempt is
    // subordinate metadata and must not collapse two projected executions.
    return block.executionId ?? block.attemptId ?? block.stepId ?? block.label
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
      case 'skipped':
        return 'skipped, not executed'
    }
  }

  /**
   * Rich tooltip for a timeline block, combining step/agent/attempt/case/failure
   * metadata so operators can identify any block without opening the detail panel.
   */
  protected blockTooltip(block: TimelineBlock): string {
    const parts: string[] = [block.label, this.statusLabel(block)]
    if (block.stepId && block.stepId !== block.label) parts.push(`step: ${block.stepId}`)
    if (block.agentName) parts.push(`agent: ${block.agentName}`)
    if (block.attemptNumber !== undefined) parts.push(`attempt #${block.attemptNumber}`)
    if (block.caseId) parts.push(`case: ${block.caseId}`)
    if (block.failureCode) parts.push(`failure: ${block.failureCode}`)
    if (block.cycleNumber !== undefined) parts.push(`correction cycle ${block.cycleNumber}`)
    if (block.triggeringExecutionId) parts.push(`triggered by execution: ${block.triggeringExecutionId}`)
    return parts.join(' \u00b7 ')
  }

  /** Exact attempt/execution highlighting prevents a historical click from selecting every retry bar. */
  protected isSelected(block: TimelineBlock): boolean {
    const selection = this.selected()
    if (!selection) return false
    if (selection.kind === 'launch_request') return block.kind === 'launch_request'
    // Outcome executions are the authoritative generation identity; an attempt
    // is a linked agent detail, not a reason to broaden this highlight.
    if (selection.executionId) return block.executionId === selection.executionId
    if (selection.attemptId) return block.attemptId === selection.attemptId
    return (block.stepId ?? block.label) === selection.stepId
  }
}
