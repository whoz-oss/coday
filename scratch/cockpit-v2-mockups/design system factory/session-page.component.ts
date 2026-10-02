import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { DatePipe } from '@angular/common'
import { MatButtonModule } from '@angular/material/button'
import { MatExpansionModule } from '@angular/material/expansion'
import { MatIconModule } from '@angular/material/icon'
import { FactoryStore } from '../../core/factory.store'
import { TimelineBlock } from '../../core/models'
import { ShellState } from '../../core/shell-state'
import { DurationPipe, TokensPipe, UsdPipe } from '../../shared/pipes/format.pipes'
import { MetricChipComponent } from '../../shared/ui/metric-chip.component'
import { StatusChipComponent } from '../../shared/ui/status-chip.component'
import { AgentTimelineComponent } from './agent-timeline/agent-timeline.component'
import { EventLogComponent } from './event-log/event-log.component'

@Component({
  selector: 'sf-session-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatExpansionModule,
    MatIconModule,
    StatusChipComponent,
    MetricChipComponent,
    AgentTimelineComponent,
    EventLogComponent,
    UsdPipe,
    DurationPipe,
    TokensPipe,
  ],
  templateUrl: './session-page.component.html',
  styleUrl: './session-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionPageComponent {
  /** Paramètre de route :runId (withComponentInputBinding) */
  readonly runId = input.required<string>()

  private readonly store = inject(FactoryStore)
  private readonly shell = inject(ShellState)

  protected readonly session = computed(() => this.store.session(this.runId()))
  protected readonly selectedBlock = signal('build')

  constructor() {
    effect(() => {
      const s = this.session()
      this.shell.crumbs.set([
        { label: 'Sandboxes', link: '/sandboxes' },
        { label: s?.sandbox ?? '…', link: '/sandboxes', mono: true },
        { label: this.runId(), mono: true },
      ])
    })
  }

  protected selectBlock(b: TimelineBlock): void {
    this.selectedBlock.set(b.label)
  }

  protected stop(): void {
    console.log('stop run', this.runId())
  }
}
