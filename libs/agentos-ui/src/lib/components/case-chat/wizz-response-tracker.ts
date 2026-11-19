import { ActorRoleEnum, CaseEvent, CaseStatusEventStatusEnum } from '@whoz-oss/agentos-api-client'

/**
 * Recognises a completed answer observed live on one SSE connection.
 *
 * Durable events are replayed whenever the stream reconnects, whereas ThinkingEvent and
 * TextChunkEvent are transient. Requiring the transient observation keeps this decorative
 * effect out of the conversation history without maintaining a second event history.
 */
export class WizzResponseTracker {
  private observedLiveActivity = false
  private candidate: { caseId: string; messageId: string; actorId: string } | null = null

  observe(event: CaseEvent): string | null {
    if (event.type === 'ThinkingEvent') {
      this.observedLiveActivity = true
      return null
    }

    if (event.type === 'TextChunkEvent') {
      if (event.chunk.trim().length > 0) this.observedLiveActivity = true
      return null
    }

    if (event.type === 'AgentSelectedEvent' || event.type === 'AgentRunningEvent') {
      this.reset()
      return null
    }

    if (event.type === 'CaseStatusEvent') {
      if (
        event.status === CaseStatusEventStatusEnum.IDLE ||
        event.status === CaseStatusEventStatusEnum.KILLED ||
        event.status === CaseStatusEventStatusEnum.ERROR
      ) {
        this.reset()
      }
      return null
    }

    if (event.type === 'MessageEvent') {
      if (event.actor.role === ActorRoleEnum.USER) {
        this.reset()
      } else if (event.actor.role === ActorRoleEnum.AGENT && this.observedLiveActivity && hasTextContent(event)) {
        // A turn can emit several answer messages. The completion still consumes them as one turn.
        this.candidate = { caseId: event.caseId, messageId: event.id, actorId: event.actor.id }
      }
      return null
    }

    if (event.type !== 'AgentFinishedEvent') return null

    const candidate = this.candidate
    this.reset()
    return candidate && candidate.caseId === event.caseId && candidate.actorId === event.agentId ? candidate.messageId : null
  }

  reset(): void {
    this.observedLiveActivity = false
    this.candidate = null
  }
}

function hasTextContent(event: Extract<CaseEvent, { type: 'MessageEvent' }>): boolean {
  return event.content.some(
    (part) => 'content' in part && typeof part.content === 'string' && part.content.trim().length > 0
  )
}
