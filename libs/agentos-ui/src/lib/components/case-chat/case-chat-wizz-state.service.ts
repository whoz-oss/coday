import { DestroyRef, Injectable, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { CaseEvent, CaseEventSseService, MessageEvent as CaseMessageEvent } from '@whoz-oss/agentos-api-client'
import { Subscription } from 'rxjs'

const MAX_PENDING_FINISHES = 100

/** Finish ids whose run contains a durable, non-empty answer from the finishing agent. */
export function completedAnswerFinishIds(events: readonly CaseEvent[]): Set<string> {
  const completed = new Set<string>()
  let candidate: CaseMessageEvent | null = null

  for (const event of events) {
    if (event.type === 'AgentRunningEvent' || event.type === 'AgentSelectedEvent' || event.type === 'ErrorEvent') {
      candidate = null
      continue
    }
    if (event.type === 'MessageEvent') {
      const message = event as CaseMessageEvent
      if (message.actor.role === 'USER') {
        candidate = null
      } else if (message.actor.role === 'AGENT') {
        const text = message.content
          ?.filter((part): part is { content: string } => 'content' in part)
          .map((part) => part.content)
          .join('')
          .trim()
        candidate = text ? message : null
      }
      continue
    }
    if (
      event.type === 'AnswerEvent' ||
      (event.type === 'CaseStatusEvent' && (event.status === 'KILLED' || event.status === 'ERROR'))
    ) {
      candidate = null
      continue
    }
    if (event.type === 'AgentFinishedEvent') {
      if (candidate?.actor.id === event.agentId) completed.add(event.id)
      candidate = null
    }
  }
  return completed
}

/** Component-scoped opt-in provenance/correlation state for the Wizz presentation cue. */
@Injectable()
export class CaseChatWizzStateService {
  private readonly eventsSse = inject(CaseEventSseService)
  private readonly destroyRef = inject(DestroyRef)
  private source: Subscription | null = null
  private caseId: string | null = null
  private generation = 0
  private readonly pendingFinishIds = new Set<string>()
  private readonly consumedFinishIds = new Set<string>()
  private mainFinishIds = new Set<string>()
  private eligibleFinishIds = new Set<string>()

  readonly enabled = signal(false)
  /** Incremented once per eligible, correlated finish; consumed by the component only. */
  readonly cue = signal(0)

  setCase(caseId: string): void {
    if (this.caseId === caseId) return
    this.caseId = caseId
    this.reset()
  }

  setMainEvents(events: readonly CaseEvent[]): void {
    this.mainFinishIds = new Set(events.filter((event) => event.type === 'AgentFinishedEvent').map((event) => event.id))
    this.eligibleFinishIds = completedAnswerFinishIds(events)
    this.reconcile()
  }

  toggle(caseId: string): void {
    if (this.enabled()) {
      this.disable()
      return
    }
    this.setCase(caseId)
    this.enabled.set(true)
    const activation = ++this.generation
    this.source = this.eventsSse
      .connect(caseId, { includePreviousEvents: false })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => {
          if (activation !== this.generation || event.type !== 'AgentFinishedEvent' || event.caseId !== this.caseId) return
          this.pendingFinishIds.add(event.id)
          while (this.pendingFinishIds.size > MAX_PENDING_FINISHES) {
            this.pendingFinishIds.delete(this.pendingFinishIds.values().next().value as string)
          }
          this.reconcile()
        },
        // Wizz is best-effort: an auxiliary stream failure must leave the chat untouched.
        error: () => this.clearPending(),
      })
  }

  disable(): void {
    this.enabled.set(false)
    this.generation++
    this.source?.unsubscribe()
    this.source = null
    this.clearPending()
  }

  reset(): void {
    this.disable()
    this.mainFinishIds.clear()
    this.eligibleFinishIds.clear()
    this.consumedFinishIds.clear()
  }

  terminal(): void {
    this.disable()
  }

  private reconcile(): void {
    for (const finishId of [...this.pendingFinishIds]) {
      if (this.consumedFinishIds.has(finishId) || !this.enabled()) {
        this.pendingFinishIds.delete(finishId)
      } else if (this.mainFinishIds.has(finishId)) {
        this.pendingFinishIds.delete(finishId)
        this.consumedFinishIds.add(finishId)
        if (this.eligibleFinishIds.has(finishId)) this.cue.update((value) => value + 1)
      }
    }
  }

  private clearPending(): void {
    this.pendingFinishIds.clear()
  }
}
