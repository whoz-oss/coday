export interface WizzEventSource {
  addEventListener(type: string, listener: EventListener): void
  removeEventListener(type: string, listener: EventListener): void
  close(): void
  onerror: ((event: Event) => void) | null
}

export type WizzEventSourceFactory = (url: string) => WizzEventSource

interface LiveCaseEvent {
  id: string
  type: string
  caseId: string
  actor?: { id?: string; role?: string }
  content?: Array<{ type?: string; content?: string }>
  agentId?: string
  status?: string
}

/**
 * Optional, live-only observer for completed agent answers.
 *
 * It deliberately owns a separate EventSource so it never has to infer whether an event from the
 * chat stream was history or live. It has no timeline or case-state side effects.
 */
export class WizzCompletionObserver {
  private source: WizzEventSource | null = null
  private sourceHandler: EventListener | null = null
  private readonly seenIds = new Set<string>()
  private pendingAgentId: string | null = null
  private generation = 0

  constructor(private readonly createEventSource: WizzEventSourceFactory = (url) => new EventSource(url)) {}

  start(basePath: string, caseId: string, onAnswerCompleted: () => void): void {
    this.stop()
    const generation = ++this.generation
    const source = this.createEventSource(`${basePath}/api/cases/${caseId}/events?includePreviousEvents=false`)
    const handler: EventListener = (event) => {
      if (generation !== this.generation || source !== this.source) return
      this.consume(event as MessageEvent<string>, caseId, onAnswerCompleted)
    }

    this.source = source
    this.sourceHandler = handler
    source.addEventListener('case-event', handler)
    source.onerror = () => {
      if (generation === this.generation && source === this.source) this.pendingAgentId = null
    }
  }

  stop(): void {
    this.generation++
    if (this.source && this.sourceHandler) this.source.removeEventListener('case-event', this.sourceHandler)
    if (this.source) {
      this.source.onerror = null
      this.source.close()
    }
    this.source = null
    this.sourceHandler = null
    this.seenIds.clear()
    this.pendingAgentId = null
  }

  private consume(message: MessageEvent<string>, expectedCaseId: string, onAnswerCompleted: () => void): void {
    let event: LiveCaseEvent
    try {
      event = JSON.parse(message.data) as LiveCaseEvent
    } catch {
      return
    }
    if (!event.id || event.caseId !== expectedCaseId || this.seenIds.has(event.id)) return
    this.seenIds.add(event.id)

    switch (event.type) {
      case 'AgentRunningEvent':
      case 'AgentSelectedEvent':
        this.pendingAgentId = null
        return
      case 'MessageEvent':
        if (event.actor?.role === 'AGENT' && event.actor.id && this.hasText(event.content)) {
          this.pendingAgentId = event.actor.id
        } else if (event.actor?.role === 'USER') {
          this.pendingAgentId = null
        }
        return
      case 'AgentFinishedEvent':
        if (event.agentId && event.agentId === this.pendingAgentId) onAnswerCompleted()
        this.pendingAgentId = null
        return
      case 'AnswerEvent':
      case 'ErrorEvent':
        this.pendingAgentId = null
        return
      case 'CaseStatusEvent':
        if (event.status === 'IDLE' || event.status === 'KILLED' || event.status === 'ERROR') this.pendingAgentId = null
        return
      default:
        return
    }
  }

  private hasText(content: LiveCaseEvent['content']): boolean {
    return !!content?.some((part) => part.type === 'Text' && typeof part.content === 'string' && part.content.trim())
  }
}
