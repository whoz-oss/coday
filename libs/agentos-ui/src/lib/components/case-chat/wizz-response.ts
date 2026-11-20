import { CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'

/**
 * Conservative, component-local eligibility for the optional Wizz feedback.
 *
 * Only transient live activity can arm a completion. Persisted event replay therefore cannot
 * manufacture eligibility, and callers remain responsible for feeding only fresh SSE frames.
 */
export class WizzResponse {
  private enabled = false
  private armed = false
  private runningAgentId: string | null = null
  private candidateAgentId: string | null = null

  setEnabled(enabled: boolean): void {
    this.enabled = enabled
    if (!enabled) this.reset()
  }

  reset(): void {
    this.armed = false
    this.runningAgentId = null
    this.candidateAgentId = null
  }

  consume(event: CaseEvent, messageText = ''): boolean {
    if (this.isRunBoundary(event)) {
      this.reset()
      if (event.type === 'AgentSelectedEvent' || event.type === 'AgentRunningEvent') {
        this.runningAgentId = (event as { agentId?: string }).agentId ?? null
      }
      return false
    }

    if (event.type === 'ThinkingEvent' || event.type === 'TextChunkEvent') {
      if (this.enabled) this.armed = true
      return false
    }

    if (event.type === 'MessageEvent') {
      const message = event as CaseMessageEvent
      if (
        this.enabled &&
        this.armed &&
        message.actor.role === 'AGENT' &&
        messageText.trim().length > 0 &&
        (!this.runningAgentId || message.actor.id === this.runningAgentId)
      ) {
        this.candidateAgentId = message.actor.id
      }
      return false
    }

    if (event.type !== 'AgentFinishedEvent') return false

    const agentId = (event as { agentId?: string }).agentId
    const eligible =
      this.enabled &&
      this.armed &&
      !!this.candidateAgentId &&
      this.candidateAgentId === agentId &&
      (!this.runningAgentId || this.runningAgentId === agentId)
    this.reset()
    return eligible
  }

  private isRunBoundary(event: CaseEvent): boolean {
    if (event.type === 'AgentSelectedEvent' || event.type === 'AgentRunningEvent') return true
    if (event.type === 'ErrorEvent') return true
    if (event.type === 'AnswerEvent') return true
    if (event.type === 'MessageEvent') return (event as CaseMessageEvent).actor.role === 'USER'
    return event.type === 'CaseStatusEvent' && ['IDLE', 'KILLED', 'ERROR'].includes((event as { status: string }).status)
  }
}
