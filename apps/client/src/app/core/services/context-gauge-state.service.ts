import { computed, inject, Injectable, signal } from '@angular/core'
import { filter } from 'rxjs'
import { UsageEvent } from '@coday/model'
import { EventStreamService } from './event-stream.service'
import { ThreadStateService } from './thread-state.service'

@Injectable({ providedIn: 'root' })
export class ContextGaugeStateService {
  private readonly eventStream = inject(EventStreamService)
  private readonly threadState = inject(ThreadStateService)

  readonly ratio = signal(0)
  readonly inputTokens = signal(0)
  readonly contextWindow = signal(0)
  readonly price = signal(0)
  readonly iterations = signal(0)
  readonly cacheReadTokens = signal(0)
  readonly visible = signal(false)

  readonly level = computed(() => {
    const r = this.ratio()
    if (r < 0.6) return 'ok'
    if (r < 0.85) return 'warning'
    return 'danger'
  })

  constructor() {
    this.eventStream.events$
      .pipe(
        filter((e): e is UsageEvent => e instanceof UsageEvent),
        filter((e) => !e.threadId || e.threadId === this.threadState.getSelectedThreadId())
      )
      .subscribe((e) => {
        this.inputTokens.set(e.inputTokens)
        this.contextWindow.set(e.contextWindow)
        this.price.set(e.price)
        this.iterations.set(e.iterations)
        this.cacheReadTokens.set(e.cacheReadTokens)
        this.ratio.set(e.contextWindow > 0 ? e.inputTokens / e.contextWindow : 0)
        this.visible.set(true)
      })

    this.eventStream.connectionStatus$.pipe(filter((status) => !status.connected)).subscribe(() => {
      this.visible.set(false)
      this.ratio.set(0)
      this.inputTokens.set(0)
      this.contextWindow.set(0)
      this.price.set(0)
      this.iterations.set(0)
      this.cacheReadTokens.set(0)
    })
  }
}
