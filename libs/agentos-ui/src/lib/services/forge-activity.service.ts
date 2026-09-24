import { inject, Injectable, NgZone, OnDestroy, signal } from '@angular/core'
import { CaseEvent } from '@whoz-oss/agentos-api-client'

@Injectable({ providedIn: 'root' })
export class ForgeActivityService implements OnDestroy {
  private readonly zone = inject(NgZone)

  readonly activeCaseId = signal<string | null>(null)
  readonly events = signal<CaseEvent[]>([])
  readonly caseStatus = signal<string>('IDLE')
  readonly streamingText = signal<string>('')
  readonly connected = signal<boolean>(false)
  /** Infos Jira du ticket actif — persisté pour éviter le rechargement à chaque navigation. */
  readonly ticketInfo = signal<{
    ticketId: string
    summary: string
    epicKey: string | null
    epicSummary: string | null
  } | null>(null)

  private eventSource: EventSource | null = null
  private currentCaseId: string | null = null

  connect(caseId: string, basePath: string): void {
    if (this.currentCaseId === caseId) return
    this.disconnect()
    this.currentCaseId = caseId
    this.activeCaseId.set(caseId)
    this.events.set([])
    this.caseStatus.set('PENDING')
    this.streamingText.set('')
    this.connected.set(true)

    const url = basePath + '/api/cases/' + caseId + '/events'
    this.eventSource = this.zone.runOutsideAngular(() => new EventSource(url))

    const handler = (msg: MessageEvent) => {
      try {
        const event = JSON.parse(msg.data) as CaseEvent
        this.zone.run(() => {
          if (event.type === 'TextChunkEvent') {
            const chunk = (event as unknown as { chunk?: string }).chunk
            if (chunk) this.streamingText.update((prev) => prev + chunk)
            return
          }
          if (event.type === 'AgentFinishedEvent') {
            this.streamingText.set('')
          }
          if (event.type === 'CaseStatusEvent') {
            const status = (event as unknown as { status: string }).status
            this.caseStatus.set(status)
            if (status === 'KILLED' || status === 'ERROR') {
              this.connected.set(false)
              this.eventSource?.close()
              this.eventSource = null
            }
          }
          this.events.update((prev) => (prev.some((e) => e.id === event.id) ? prev : [...prev, event]))
        })
      } catch {
        // ignore malformed SSE frames
      }
    }

    const eventNames = [
      'MessageEvent',
      'CaseStatusEvent',
      'AgentSelectedEvent',
      'AgentRunningEvent',
      'AgentFinishedEvent',
      'ThinkingEvent',
      'TextChunkEvent',
      'ToolRequestEvent',
      'ToolResponseEvent',
      'IntentionGeneratedEvent',
      'WarnEvent',
      'ErrorEvent',
      'QuestionEvent',
      'AnswerEvent',
    ] as const

    for (const name of eventNames) {
      this.eventSource.addEventListener(name, handler as EventListenerOrEventListenerObject)
    }

    this.eventSource.onerror = () => {
      this.zone.run(() => this.connected.set(false))
    }
  }

  disconnect(): void {
    this.eventSource?.close()
    this.eventSource = null
    this.currentCaseId = null
    this.connected.set(false)
    this.ticketInfo.set(null)
  }

  ngOnDestroy(): void {
    this.disconnect()
  }
}
