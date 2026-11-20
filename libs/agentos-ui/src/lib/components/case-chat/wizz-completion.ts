import { CaseEvent, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'

/**
 * Keeps the deliberately conservative evidence needed to celebrate a live answer.
 * Persisted history does not include Thinking/TextChunk events, so no evidence means no pulse.
 */
export class WizzCompletionTracker {
  private liveEvidence = false
  private candidateAgentId: string | null = null

  reset(): void {
    this.liveEvidence = false
    this.candidateAgentId = null
  }

  /** Returns true only for one eligible AgentFinishedEvent. */
  accept(event: CaseEvent, messageText: (message: CaseMessageEvent) => string): boolean {
    switch (event.type) {
      case 'ThinkingEvent':
        this.liveEvidence = true
        return false
      case 'TextChunkEvent':
        if ((event as unknown as { chunk?: string }).chunk?.trim()) this.liveEvidence = true
        return false
      case 'MessageEvent': {
        const message = event as CaseMessageEvent
        if (message.actor.role === 'USER') {
          this.reset()
        } else if (message.actor.role === 'AGENT' && this.liveEvidence && messageText(message).trim()) {
          this.candidateAgentId = message.actor.id
        }
        return false
      }
      case 'AgentRunningEvent':
      case 'AgentSelectedEvent':
      case 'ErrorEvent':
      case 'QuestionEvent':
      case 'AnswerEvent':
        this.reset()
        return false
      case 'CaseStatusEvent':
        if (['IDLE', 'KILLED', 'ERROR'].includes(String(event.status))) this.reset()
        return false
      case 'AgentFinishedEvent': {
        const shouldPulse = this.liveEvidence && this.candidateAgentId === event.agentId
        this.reset()
        return shouldPulse
      }
      default:
        return false
    }
  }
}
