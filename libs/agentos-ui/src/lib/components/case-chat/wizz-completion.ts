import { AgentFinishedEvent, CaseEvent, MessageEvent } from '@whoz-oss/agentos-api-client'

/**
 * Correlates a non-replayed global completion with its persisted case timeline.
 *
 * The case SSE endpoint replays history and cannot establish that a completion is new. The
 * global stream can, but it does not carry the answer context. This deliberately keeps those
 * responsibilities separate and emits only after both sources agree.
 */
export class WizzCompletionCorrelator {
  private readonly seenCompletionIds = new Set<string>()
  private readonly pendingCompletions = new Map<string, AgentFinishedEvent>()

  constructor(
    private readonly caseId: string,
    private readonly namespaceId: string
  ) {}

  /** Records a completion observed on the live global stream, even when Wizz is disabled. */
  observeLiveCompletion(completion: AgentFinishedEvent, events: CaseEvent[], enabled: boolean): string[] {
    if (!this.belongsToCurrentCase(completion) || this.seenCompletionIds.has(completion.id)) return []
    this.seenCompletionIds.add(completion.id)
    if (!enabled) return []

    this.pendingCompletions.set(completion.id, completion)
    return this.reconcile(events)
  }

  /** Re-checks live candidates after the per-case stream receives another persisted event. */
  reconcile(events: CaseEvent[]): string[] {
    const eligible: string[] = []
    for (const [id, completion] of this.pendingCompletions) {
      const finishIndex = events.findIndex((event) => event.id === id)
      if (finishIndex === -1) continue

      this.pendingCompletions.delete(id)
      if (this.executionHasAgentAnswer(events, finishIndex, completion.agentId)) eligible.push(id)
    }
    return eligible
  }

  /** Cancels candidates without forgetting globally observed ids, so enabling is never retroactive. */
  clearPending(): void {
    this.pendingCompletions.clear()
  }

  private belongsToCurrentCase(event: AgentFinishedEvent): boolean {
    return event.caseId === this.caseId && event.namespaceId === this.namespaceId && !!event.id && !!event.agentId
  }

  private executionHasAgentAnswer(events: CaseEvent[], finishIndex: number, agentId: string): boolean {
    for (let index = finishIndex - 1; index >= 0; index--) {
      const event = events[index]
      if (!event) continue
      if (
        event.type === 'AgentRunningEvent' ||
        event.type === 'AgentFinishedEvent' ||
        (event.type === 'MessageEvent' && (event as MessageEvent).actor.role === 'USER')
      ) {
        break
      }
      if (event.type === 'MessageEvent') {
        const message = event as MessageEvent
        if (message.actor.role === 'AGENT' && message.actor.id === agentId) return true
      }
    }
    return false
  }
}
