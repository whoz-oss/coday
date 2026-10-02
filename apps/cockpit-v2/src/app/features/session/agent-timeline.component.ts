import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatTooltipModule } from '@angular/material/tooltip'
import { TimelineBlock, TimelineLane } from '../../core/models'
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
  readonly tickEverySec = input(120)
  readonly selected = input<string>()
  readonly blockSelect = output<TimelineBlock>()

  /** Fin d'axe : maintenant + ~5 %, arrondi au tick suivant */
  protected readonly axisEnd = computed(() => {
    const step = this.tickEverySec()
    return Math.ceil((this.nowSec() * 1.04) / step) * step
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
}
