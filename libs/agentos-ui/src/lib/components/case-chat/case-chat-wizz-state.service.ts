import { inject, Injectable, signal } from '@angular/core'
import { AgentFinishedEvent, CaseEvent, CaseEventSseService, MessageEvent } from '@whoz-oss/agentos-api-client'
import { Subscription } from 'rxjs'

/**
 * Component-scoped provenance for the optional Wizz reply cue.
 *
 * The normal chat stream deliberately remains responsible for rendering history. This service
 * opens a second, live-only stream only while Wizz is enabled, then reconciles its finish IDs
 * with already displayed normal-stream events before emitting a cue.
 */
@Injectable()
export class CaseChatWizzStateService {
  private readonly caseEvents = inject(CaseEventSseService)

  readonly enabled = signal(false)
  readonly cueSequence = signal(0)

  private subscription: Subscription | null = null
  private caseId: string | null = null
  private generation = 0
  private normalEvents: CaseEvent[] = []
  private readonly pendingFinishIds = new Set<string>()
  private readonly consumedFinishIds = new Set<string>()

  toggle(caseId: string): void {
    if (this.enabled()) {
      this.disable()
    } else {
      this.enable(caseId)
    }
  }

  enable(caseId: string): void {
    if (this.enabled() && this.caseId === caseId) return
    this.closeConnection()
    this.enabled.set(true)
    this.caseId = caseId
    this.pendingFinishIds.clear()
    const generation = ++this.generation
    this.subscription = this.caseEvents.connect(caseId, { includePreviousEvents: false }).subscribe((event) => {
      if (generation !== this.generation || !this.enabled() || event.caseId !== this.caseId) return
      if (event.type !== 'AgentFinishedEvent') return
      this.pendingFinishIds.add(event.id)
      this.reconcilePending()
    })
  }

  disable(): void {
    this.enabled.set(false)
    this.pendingFinishIds.clear()
    this.closeConnection()
  }

  /** Discard unpaired live finish IDs before a normal-stream retry, while retaining the choice. */
  retry(caseId: string): void {
    if (!this.enabled()) return
    this.enableFresh(caseId)
  }

  /** A new/reopened case must never inherit the setting or an unfinished correlation. */
  reset(): void {
    this.disable()
    this.caseId = null
    this.normalEvents = []
    this.pendingFinishIds.clear()
    this.consumedFinishIds.clear()
  }

  dispose(): void {
    this.reset()
  }

  reconcile(events: CaseEvent[]): void {
    this.normalEvents = events
    this.reconcilePending()
  }

  private enableFresh(caseId: string): void {
    this.closeConnection()
    this.caseId = caseId
    this.pendingFinishIds.clear()
    const generation = ++this.generation
    this.subscription = this.caseEvents.connect(caseId, { includePreviousEvents: false }).subscribe((event) => {
      if (generation !== this.generation || !this.enabled() || event.caseId !== this.caseId) return
      if (event.type !== 'AgentFinishedEvent') return
      this.pendingFinishIds.add(event.id)
      this.reconcilePending()
    })
  }

  private reconcilePending(): void {
    if (!this.enabled() || !this.caseId) return
    for (const finishId of [...this.pendingFinishIds]) {
      if (this.consumedFinishIds.has(finishId)) {
        this.pendingFinishIds.delete(finishId)
        continue
      }
      const index = this.normalEvents.findIndex((event) => event.id === finishId)
      if (index === -1) continue
      const finish = this.normalEvents[index]
      this.pendingFinishIds.delete(finishId)
      this.consumedFinishIds.add(finishId)
      if (finish?.type === 'AgentFinishedEvent' && this.hasAnswerForFinish(index, finish)) {
        this.cueSequence.update((sequence) => sequence + 1)
      }
    }
  }

  private hasAnswerForFinish(finishIndex: number, finish: AgentFinishedEvent): boolean {
    for (let index = finishIndex - 1; index >= 0; index--) {
      const event = this.normalEvents[index]
      if (!event) continue
      if (event.type === 'AgentFinishedEvent' || event.type === 'AgentRunningEvent') return false
      if (event.type === 'MessageEvent') {
        const message = event as MessageEvent
        if (message.actor.role === 'USER') return false
        if (message.actor.role === 'AGENT' && message.actor.id === finish.agentId && this.messageText(message).trim()) {
          return true
        }
      }
    }
    return false
  }

  private messageText(message: MessageEvent): string {
    const parts = message.content ?? []
    return parts
      .filter((part) => typeof (part as { content?: unknown }).content === 'string')
      .map((part) => (part as { content: string }).content)
      .join('')
  }

  private closeConnection(): void {
    this.generation++
    this.subscription?.unsubscribe()
    this.subscription = null
  }
}
