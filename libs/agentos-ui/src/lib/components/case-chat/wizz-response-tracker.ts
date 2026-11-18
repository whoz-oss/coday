import { AgentFinishedEvent, CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'

/**
 * Conservatively identifies an observed live response completion.
 *
 * The SSE protocol has no replay boundary, so durable events alone cannot safely trigger a
 * decorative effect. A transient frame followed by a non-empty agent message and its matching
 * completion is the deliberately narrow evidence accepted by the product decision.
 */
export class WizzResponseTracker {
  private hasTransientEvidence = false
  private agentId: string | null = null

  consume(event: CaseEvent, messageText: string | null = null): boolean {
    if (event.type === 'ThinkingEvent' || event.type === 'TextChunkEvent') {
      this.hasTransientEvidence = true
      return false
    }

    if (event.type === 'AgentRunningEvent' || event.type === 'AgentSelectedEvent') {
      this.reset()
      return false
    }

    if (event.type === 'AgentFinishedEvent') {
      const qualifies = this.agentId !== null && this.sameId(this.agentId, (event as AgentFinishedEvent).agentId)
      this.reset()
      return qualifies
    }

    if (event.type === 'MessageEvent') {
      const message = event as CaseMessageEvent
      if (message.actor.role === 'USER') {
        this.reset()
      } else if (message.actor.role === 'AGENT' && this.hasTransientEvidence && messageText?.trim() && !this.agentId) {
        this.agentId = message.actor.id
      }
      return false
    }

    if (
      event.type === 'AnswerEvent' ||
      event.type === 'ErrorEvent' ||
      (event.type === 'CaseStatusEvent' && ['IDLE', 'KILLED', 'ERROR'].includes(String(event.status)))
    ) {
      this.reset()
    }
    return false
  }

  reset(): void {
    this.hasTransientEvidence = false
    this.agentId = null
  }

  private sameId(left: string, right: string): boolean {
    return left.toLowerCase() === right.toLowerCase()
  }
}
