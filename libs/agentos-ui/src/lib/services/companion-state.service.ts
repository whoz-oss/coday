import { DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { CaseControllerService, CaseEvent } from '@whoz-oss/agentos-api-client'
import { CaseStateService } from './case-state.service'
import { CompanionPipService } from './companion-pip.service'
import { GlobalCaseEventSseService } from './global-case-event-sse.service'

// ---------------------------------------------------------------------------
// Notification types (shared with CompanionComponent)
// ---------------------------------------------------------------------------

interface BaseNotification {
  id: string
  caseId: string
  namespaceId: string
}

export interface ConfirmationNotification extends BaseNotification {
  kind: 'confirmation'
  blocking: true
  toolName: string
  inputPreview: string
}

export interface QuestionNotification extends BaseNotification {
  kind: 'question'
  blocking: true
  agentName: string
  question: string
  options: string[]
}

export interface FinishedNotification extends BaseNotification {
  kind: 'finished'
  blocking: false
  agentName: string
}

export interface ErrorNotification extends BaseNotification {
  kind: 'error'
  blocking: false
  message: string
}

export type CompanionNotification =
  | ConfirmationNotification
  | QuestionNotification
  | FinishedNotification
  | ErrorNotification

export interface CaseGroup {
  caseId: string
  namespaceId: string
  /** Resolved asynchronously — starts as the short caseId fallback. */
  caseTitle: string
  notifications: CompanionNotification[]
}

// ---------------------------------------------------------------------------

const INPUT_PREVIEW_MAX_CHARS = 160

function truncate(text: string, maxChars: number): string {
  return text.length > maxChars ? `${text.slice(0, maxChars)}\u2026` : text
}

/**
 * Singleton service that owns the companion notification state and runs in the
 * MAIN document context (not inside the PiP window).
 *
 * Responsibilities:
 * - Subscribe to the global SSE stream
 * - Filter sub-cases, buffer and resolve case titles
 * - Expose `groups` signal consumed by both the main app and the PiP CompanionComponent
 * - Drive the PiP window lifecycle: open when notifications arrive, close when empty
 *
 * CompanionComponent becomes a pure renderer — it reads `groups` from this service
 * and calls back into it for dismiss/approve/deny actions.
 *
 * This separation solves the chicken-and-egg problem where the SSE subscription
 * previously lived inside CompanionComponent (which only exists inside the PiP window
 * and therefore cannot trigger the initial window open).
 */
@Injectable({ providedIn: 'root' })
export class CompanionStateService {
  private readonly destroyRef = inject(DestroyRef)
  private readonly sse = inject(GlobalCaseEventSseService)
  private readonly caseController = inject(CaseControllerService)
  private readonly caseState = inject(CaseStateService)
  private readonly companionPip = inject(CompanionPipService)

  /** Notification groups ordered by first-event arrival, one group per caseId. */
  readonly groups = signal<CaseGroup[]>([])

  private readonly dismissTimers = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly rootCache = new Map<string, string>()
  private readonly pendingByCase = new Map<string, CompanionNotification[]>()

  private sseStarted = false

  constructor() {}

  /**
   * Start the SSE subscription. Idempotent — safe to call multiple times.
   * Called by CaseShellComponent.ngOnInit so the stream is active as long
   * as the shell is mounted, regardless of whether the PiP window is open.
   */
  startSse(): void {
    if (this.sseStarted) return
    this.sseStarted = true

    this.sse
      .connect()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (event) => this.onEvent(event),
        error: (err) => console.error('[CompanionState] event stream error', err),
      })
  }

  // ---------------------------------------------------------------------------
  // Event handling
  // ---------------------------------------------------------------------------

  private onEvent(event: CaseEvent): void {
    switch (event.type) {
      case 'PendingConfirmationEvent':
        this.routeNotification({
          id: event.id,
          caseId: event.caseId,
          namespaceId: event.namespaceId,
          kind: 'confirmation',
          blocking: true,
          toolName: event.toolName,
          inputPreview: truncate(event.inputJson, INPUT_PREVIEW_MAX_CHARS),
        })
        this.companionPip.playAlertBeep()
        break

      case 'ConfirmationResolvedEvent':
        this.removeNotificationByEvent(event.caseId, event.pendingEventId)
        break

      case 'QuestionEvent':
        this.routeNotification({
          id: event.id,
          caseId: event.caseId,
          namespaceId: event.namespaceId,
          kind: 'question',
          blocking: true,
          agentName: event.agentName,
          question: event.question,
          options: event.questionType === 'FREE_TEXT' ? [] : (event.options ?? []),
        })
        this.companionPip.playAlertBeep()
        break

      case 'AnswerEvent':
        this.removeNotificationByEvent(event.caseId, event.questionId)
        break

      case 'AgentFinishedEvent':
        this.routeNotification({
          id: event.id,
          caseId: event.caseId,
          namespaceId: event.namespaceId,
          kind: 'finished',
          blocking: false,
          agentName: event.agentName,
        })
        break

      case 'ErrorEvent':
        this.routeNotification({
          id: event.id,
          caseId: event.caseId,
          namespaceId: event.namespaceId,
          kind: 'error',
          blocking: false,
          message: event.message,
        })
        break
    }
  }

  // ---------------------------------------------------------------------------
  // Sub-case filtering
  // ---------------------------------------------------------------------------

  private routeNotification(n: CompanionNotification): void {
    if (this.rootCache.has(n.caseId)) {
      this.pushToGroup(n.caseId, n.namespaceId, this.rootCache.get(n.caseId)!, n)
      return
    }

    const fromState = this.caseState.cases().find((c) => c.id === n.caseId)
    if (fromState) {
      if (fromState.parentCaseId) return
      const title = fromState.title ?? n.caseId.slice(0, 8)
      this.rootCache.set(n.caseId, title)
      this.pushToGroup(n.caseId, n.namespaceId, title, n)
      return
    }

    const buf = this.pendingByCase.get(n.caseId) ?? []
    if (!buf.some((x) => x.id === n.id)) buf.push(n)
    this.pendingByCase.set(n.caseId, buf)

    if (buf.length === 1) {
      this.caseController
        .getByIdCase(n.caseId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (c) => {
            const pending = this.pendingByCase.get(n.caseId) ?? []
            this.pendingByCase.delete(n.caseId)
            if (c.parentCaseId) return
            const title = c.title ?? n.caseId.slice(0, 8)
            this.rootCache.set(n.caseId, title)
            for (const buffered of pending) {
              this.pushToGroup(n.caseId, n.namespaceId, title, buffered)
            }
          },
          error: () => {
            const pending = this.pendingByCase.get(n.caseId) ?? []
            this.pendingByCase.delete(n.caseId)
            const title = n.caseId.slice(0, 8)
            this.rootCache.set(n.caseId, title)
            for (const buffered of pending) {
              this.pushToGroup(n.caseId, n.namespaceId, title, buffered)
            }
          },
        })
    }
  }

  // ---------------------------------------------------------------------------
  // Group management
  // ---------------------------------------------------------------------------

  private pushToGroup(groupCaseId: string, namespaceId: string, caseTitle: string, n: CompanionNotification): void {
    this.groups.update((prev) => {
      const existing = prev.find((g) => g.caseId === groupCaseId)
      if (existing) {
        if (existing.notifications.some((x) => x.id === n.id)) return prev
        return prev.map((g) => (g.caseId === groupCaseId ? { ...g, notifications: [...g.notifications, n] } : g))
      }
      return [...prev, { caseId: groupCaseId, namespaceId, caseTitle, notifications: [n] }]
    })
  }

  removeNotificationByEvent(eventCaseId: string, notifId: string): void {
    if (this.rootCache.has(eventCaseId)) {
      this.removeNotification(eventCaseId, notifId)
    }
  }

  removeNotification(caseId: string, notifId: string): void {
    const timer = this.dismissTimers.get(notifId)
    if (timer !== undefined) {
      clearTimeout(timer)
      this.dismissTimers.delete(notifId)
    }
    this.groups.update((prev) =>
      prev
        .map((g) =>
          g.caseId === caseId ? { ...g, notifications: g.notifications.filter((x) => x.id !== notifId) } : g
        )
        .filter((g) => g.notifications.length > 0)
    )
  }

  dismissGroup(caseId: string): void {
    const group = this.groups().find((g) => g.caseId === caseId)
    if (group) {
      for (const n of group.notifications) {
        const timer = this.dismissTimers.get(n.id)
        if (timer !== undefined) {
          clearTimeout(timer)
          this.dismissTimers.delete(n.id)
        }
      }
    }
    this.groups.update((prev) => prev.filter((g) => g.caseId !== caseId))
  }
}
