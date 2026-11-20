import { DestroyRef, Injectable, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import {
  AgentFinishedEvent,
  AgentRunningEvent,
  CaseEvent,
  MessageEvent as CaseMessageEvent,
} from '@whoz-oss/agentos-api-client'
import { Subject } from 'rxjs'
import { GlobalStreamService } from '../../services/global-stream.service'

const MAX_PENDING_FINISHES = 20

/**
 * Transient, component-scoped correlation for the Wizz completion cue.
 *
 * The case stream replays history, so it can never initiate a cue. Only the non-replaying global
 * stream can nominate a completion; the local timeline then proves that the exact run rendered an
 * agent answer. The two streams may arrive in either order.
 */
@Injectable()
export class WizzStateService {
  private readonly globalStream = inject(GlobalStreamService)
  private readonly destroyRef = inject(DestroyRef)

  readonly enabled = signal(false)
  readonly completed$ = new Subject<void>()

  private caseId: string | null = null
  private namespaceId: string | null = null
  private timeline: CaseEvent[] = []
  private readonly pendingFinishIds = new Set<string>()
  private readonly knownFinishIds = new Set<string>()
  private readonly consumedFinishIds = new Set<string>()

  constructor() {
    this.globalStream.caseEvents$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
      if (event.type !== 'AgentFinishedEvent') return
      this.acceptLiveFinish(event)
    })
    // A reconnect has no replay on the global stream. Do not hold a half-correlated visual cue
    // across that recovery boundary.
    this.globalStream.connected$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.discardPending())
  }

  setContext(caseId: string, namespaceId: string): void {
    this.caseId = caseId
    this.namespaceId = namespaceId
    this.timeline = []
    this.pendingFinishIds.clear()
    this.knownFinishIds.clear()
    this.consumedFinishIds.clear()
    this.enabled.set(false)
  }

  acceptTimeline(events: CaseEvent[]): void {
    this.timeline = events
    this.reconcilePending()
  }

  toggle(): void {
    const next = !this.enabled()
    this.enabled.set(next)
    this.pendingFinishIds.clear()
    if (next) {
      // Never turn a completion already on screen into a delayed visual notification.
      for (const event of this.timeline) {
        if (event.type === 'AgentFinishedEvent') this.knownFinishIds.add(event.id)
      }
    }
  }

  /** Drops unresolved live work after a stream recovery without changing the user's choice. */
  discardPending(): void {
    this.pendingFinishIds.clear()
  }

  reset(): void {
    this.pendingFinishIds.clear()
    this.knownFinishIds.clear()
    this.consumedFinishIds.clear()
    this.timeline = []
    this.enabled.set(false)
  }

  private acceptLiveFinish(event: AgentFinishedEvent): void {
    if (
      !this.enabled() ||
      event.caseId !== this.caseId ||
      event.namespaceId !== this.namespaceId ||
      this.knownFinishIds.has(event.id) ||
      this.consumedFinishIds.has(event.id)
    ) {
      return
    }

    this.pendingFinishIds.add(event.id)
    while (this.pendingFinishIds.size > MAX_PENDING_FINISHES) {
      const oldest = this.pendingFinishIds.values().next().value as string | undefined
      if (!oldest) break
      this.pendingFinishIds.delete(oldest)
    }
    this.reconcilePending()
  }

  private reconcilePending(): void {
    if (!this.enabled()) return
    for (const finishId of [...this.pendingFinishIds]) {
      const finishIndex = this.timeline.findIndex(
        (event) => event.id === finishId && event.type === 'AgentFinishedEvent'
      )
      if (finishIndex < 0) continue

      this.pendingFinishIds.delete(finishId)
      this.knownFinishIds.add(finishId)
      this.consumedFinishIds.add(finishId)
      const finish = this.timeline[finishIndex] as AgentFinishedEvent
      if (this.runHasVisibleAgentAnswer(finishIndex, finish)) this.completed$.next()
    }
  }

  private runHasVisibleAgentAnswer(finishIndex: number, finish: AgentFinishedEvent): boolean {
    for (let index = finishIndex - 1; index >= 0; index--) {
      const event = this.timeline[index]!
      if (event.type === 'AgentRunningEvent') {
        const running = event as AgentRunningEvent
        if (running.agentId === finish.agentId) break
        // A different agent started after this one: do not borrow its answer.
        return false
      }
      if (event.type === 'MessageEvent') {
        const message = event as CaseMessageEvent
        if (message.actor.role === 'USER') break
        if (
          message.actor.role === 'AGENT' &&
          message.actor.id === finish.agentId &&
          this.messageText(message).trim()
        ) {
          return true
        }
      }
    }
    return false
  }

  private messageText(message: CaseMessageEvent): string {
    return (
      message.content
        ?.filter((part): part is import('@whoz-oss/agentos-api-client').Text => 'content' in part)
        .map((part) => part.content)
        .join('') ?? ''
    )
  }
}
