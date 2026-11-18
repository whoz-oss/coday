import { CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'

/**
 * Conservatively identifies a completed live response. Transient Thinking/TextChunk events are
 * the only evidence that a response was generated on this SSE connection; durable replay alone
 * must never arm a Wizz effect.
 */
export class WizzResponseTracker {
  private sawLiveEvidence = false
  private candidateAgentId: string | null = null

  reset(): void {
    this.sawLiveEvidence = false
    this.candidateAgentId = null
  }

  consume(event: CaseEvent): boolean {
    switch (event.type) {
      case 'AgentSelectedEvent':
      case 'AgentRunningEvent':
        this.reset()
        return false
      case 'ThinkingEvent':
      case 'TextChunkEvent':
        this.sawLiveEvidence = true
        return false
      case 'MessageEvent':
        return this.consumeMessage(event as CaseMessageEvent)
      case 'AgentFinishedEvent': {
        const matches = this.matchesAgent((event as { agentId?: string }).agentId)
        this.reset() // A completion always consumes the candidate, including while Wizz is off.
        return matches
      }
      case 'AnswerEvent':
      case 'ErrorEvent':
        this.reset()
        return false
      case 'CaseStatusEvent': {
        const status = (event as { status?: string }).status
        if (status === 'IDLE' || status === 'KILLED' || status === 'ERROR') this.reset()
        return false
      }
      default:
        return false
    }
  }

  private consumeMessage(event: CaseMessageEvent): boolean {
    if (event.actor.role === 'USER') {
      this.reset()
      return false
    }
    if (event.actor.role !== 'AGENT' || !this.sawLiveEvidence || !this.hasText(event)) return false
    this.candidateAgentId = this.normalise(event.actor.id)
    return false
  }

  private hasText(event: CaseMessageEvent): boolean {
    return (event.content ?? []).some(
      (part) => 'content' in part && typeof part.content === 'string' && part.content.trim().length > 0
    )
  }

  private matchesAgent(agentId: string | undefined): boolean {
    const candidate = this.candidateAgentId
    return candidate !== null && candidate === this.normalise(agentId)
  }

  private normalise(id: string | undefined): string | null {
    const trimmed = id?.trim()
    return trimmed ? trimmed.toLowerCase() : null
  }
}
