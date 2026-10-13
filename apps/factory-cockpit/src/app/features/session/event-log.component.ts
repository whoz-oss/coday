import { ChangeDetectionStrategy, Component, computed, effect, input, signal, viewChild } from '@angular/core'
import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling'
import { MatButtonToggleModule } from '@angular/material/button-toggle'
import { MatCheckboxModule } from '@angular/material/checkbox'
import { MatIconModule } from '@angular/material/icon'
import { RunEvent, RunEventType } from '../../core/models'

type Filter = 'all' | RunEventType

/** Event log for a phase, filterable, virtualised, with live-follow support. */
@Component({
  selector: 'sf-event-log',
  imports: [ScrollingModule, MatButtonToggleModule, MatCheckboxModule, MatIconModule],
  templateUrl: './event-log.component.html',
  styleUrl: './event-log.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EventLogComponent {
  readonly events = input.required<RunEvent[]>()
  readonly running = input(false)
  readonly activityLabel = input('')

  protected readonly filter = signal<Filter>('all')
  protected readonly follow = signal(true)
  protected readonly filters: { value: Filter; label: string }[] = [
    { value: 'all', label: 'All' },
    { value: 'thinking', label: 'thinking' },
    { value: 'tool_call', label: 'tool_call' },
    { value: 'agent_message', label: 'agent_message' },
  ]

  protected readonly visible = computed(() => {
    const f = this.filter()
    return f === 'all' ? this.events() : this.events().filter((e) => e.type === f)
  })

  private readonly viewport = viewChild(CdkVirtualScrollViewport)

  constructor() {
    // Live follow: scroll to bottom on each new event
    effect(() => {
      const count = this.visible().length
      const vp = this.viewport()
      if (this.follow() && vp && count) {
        queueMicrotask(() => vp.scrollToIndex(count - 1, 'smooth'))
      }
    })
  }
}
