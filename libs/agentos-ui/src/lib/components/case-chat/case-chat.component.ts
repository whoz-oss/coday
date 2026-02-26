import { HttpClient } from '@angular/common/http'
import { Component, computed, inject, NgZone, OnDestroy, OnInit, signal } from '@angular/core'
import { ActivatedRoute } from '@angular/router'
import { CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent } from '@whoz-oss/design-system'

/**
 * CaseChatComponent — real-time chat view for an active case.
 *
 * Connexion SSE directe sur /api/agentos/api/cases/:caseId/events.
 * Accumule tous les CaseEvent reçus, affiche les MessageEvent,
 * indique le thinking quand des events non-terminaux arrivent.
 */
@Component({
  selector: 'agentos-case-chat',
  standalone: true,
  imports: [IconButtonComponent],
  templateUrl: './case-chat.component.html',
  styleUrl: './case-chat.component.scss',
})
export class CaseChatComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute)
  private readonly http = inject(HttpClient)
  private readonly zone = inject(NgZone)

  private readonly caseId = this.route.snapshot.params['caseId'] as string
  private readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private eventSource: EventSource | null = null

  protected readonly events = signal<CaseEvent[]>([])
  protected inputValue = signal('')
  protected isRunning = signal(false)

  protected readonly messages = computed(() => this.events().filter((e): e is CaseMessageEvent => e.type === 'MESSAGE'))

  protected get canSend(): boolean {
    return !!this.inputValue().trim() && !this.isRunning()
  }

  ngOnInit(): void {
    this.connectSse()
  }

  ngOnDestroy(): void {
    this.eventSource?.close()
  }

  private connectSse(): void {
    const url = `/api/agentos/api/cases/${this.caseId}/events`
    this.eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

    // NOTE: the backend sends named SSE events ("event: MessageEvent", "event: CaseStatusEvent", ...)
    // In that case, `onmessage` is NOT called. We must subscribe to named events.
    const handler = (msg: globalThis.MessageEvent<string>) => {
      try {
        const event = JSON.parse(msg.data) as CaseEvent
        this.zone.run(() => {
          this.events.update((prev) => [...prev, event])
          // minimal running heuristic: running unless we explicitly receive STOPPED
          if (event.type === 'STATUS') {
            const status = (event as unknown as { status?: string }).status
            this.isRunning.set(status === 'RUNNING')
          } else {
            this.isRunning.set(true)
          }
        })
      } catch {
        console.warn('[CaseChat] Failed to parse SSE event', msg.data)
      }
    }

    // handle the different event names we see in the SSE stream
    this.eventSource.addEventListener('MessageEvent', handler)
    this.eventSource.addEventListener('CaseStatusEvent', handler)
    this.eventSource.addEventListener('AgentSelectedEvent', handler)
    this.eventSource.addEventListener('AgentRunningEvent', handler)
    this.eventSource.addEventListener('AgentFinishedEvent', handler)
    this.eventSource.addEventListener('ThinkingEvent', handler)
    this.eventSource.addEventListener('TextChunkEvent', handler)

    this.eventSource.onerror = () => {
      this.zone.run(() => this.isRunning.set(false))
    }
  }

  protected onInput(event: Event): void {
    this.inputValue.set((event.target as HTMLTextAreaElement).value)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      this.submit()
    }
  }

  protected submit(): void {
    if (!this.canSend) return
    const content = this.inputValue().trim()
    this.inputValue.set('')
    this.isRunning.set(true)

    this.http
      .post(`/api/agentos/api/cases/${this.caseId}/messages`, {
        content,
        userId: 'default-user',
      })
      .subscribe({
        error: (err) => {
          console.error('[CaseChat] Failed to send message', err)
          this.isRunning.set(false)
        },
      })
  }

  protected stop(): void {
    this.http.post(`/api/agentos/api/cases/${this.caseId}/stop`, {}).subscribe({
      next: () => this.isRunning.set(false),
      error: (err) => console.error('[CaseChat] Failed to stop case', err),
    })
  }

  protected extractText(event: CaseMessageEvent): string {
    return (
      event.content
        ?.filter((c): c is import('@whoz-oss/agentos-api-client').Text => 'content' in c)
        .map((c) => c.content)
        .join('') ?? ''
    )
  }

  protected readonly _namespaceId = this.namespaceId
}
