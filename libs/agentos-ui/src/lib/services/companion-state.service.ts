import { DestroyRef, inject, Injectable, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Case, CaseControllerService, CaseEvent } from '@whoz-oss/agentos-api-client'
import { Observable, of } from 'rxjs'
import { catchError, map, switchMap } from 'rxjs/operators'
import { CaseStateService } from './case-state.service'
import { BrowserNotificationService } from './browser-notification.service'
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

interface ResolvedRootTarget {
  rootCaseId: string
  rootTitle: string
  isSubCase: boolean
}

// ---------------------------------------------------------------------------

const INPUT_PREVIEW_MAX_CHARS = 160
const MAX_ANCESTOR_HOPS = 10

function truncate(text: string, maxChars: number): string {
  return text.length > maxChars ? `${text.slice(0, maxChars)}\u2026` : text
}

/**
 * Singleton service that owns the companion notification state and runs in the
 * MAIN document context (not inside the PiP window).
 *
 * Responsibilities:
 * - Subscribe to the global SSE stream
 * - Route sub-case blocking notifications to their root parent case group while
 *   filtering out non-blocking sub-case notifications
 * - Buffer and resolve root case titles
 * - Expose `groups` signal consumed by both the main app and the PiP CompanionComponent
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
  private readonly browserNotifications = inject(BrowserNotificationService)

  /** Notification groups ordered by first-event arrival, one group per root caseId. */
  readonly groups = signal<CaseGroup[]>([])

  private readonly dismissTimers = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly rootCache = new Map<string, ResolvedRootTarget>()
  private readonly pendingByCase = new Map<string, CompanionNotification[]>()

  private sseStarted = false

  /**
   * Start the SSE subscription. Idempotent — safe to call multiple times.
   * Called at app startup so the stream is active regardless of whether the PiP window is open.
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
  // Sub-case routing (Option B: blocking sub-case events attach to parent group)
  // ---------------------------------------------------------------------------

  private routeNotification(n: CompanionNotification): void {
    const cached = this.rootCache.get(n.caseId)
    if (cached) {
      if (cached.isSubCase && !n.blocking) return
      this.pushToGroup(cached.rootCaseId, n.namespaceId, cached.rootTitle, n)
      return
    }

    const syncTarget = this.tryResolveSync(n.caseId)
    if (syncTarget) {
      this.rootCache.set(n.caseId, syncTarget)
      if (syncTarget.isSubCase && !n.blocking) return
      this.pushToGroup(syncTarget.rootCaseId, n.namespaceId, syncTarget.rootTitle, n)
      return
    }

    const buf = this.pendingByCase.get(n.caseId) ?? []
    if (!buf.some((x) => x.id === n.id)) buf.push(n)
    this.pendingByCase.set(n.caseId, buf)

    if (buf.length === 1) {
      this.resolveRootAsync(n.caseId, false, 0)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe((target) => {
          const pending = this.pendingByCase.get(n.caseId) ?? []
          this.pendingByCase.delete(n.caseId)
          this.rootCache.set(n.caseId, target)
          for (const buffered of pending) {
            if (target.isSubCase && !buffered.blocking) continue
            this.pushToGroup(target.rootCaseId, buffered.namespaceId, target.rootTitle, buffered)
          }
        })
    }
  }

  /**
   * Attempts to resolve the root case synchronously from `CaseStateService.cases()`
   * and `rootCache`. Returns null if any ancestor in the chain is not yet in memory.
   */
  private tryResolveSync(caseId: string): ResolvedRootTarget | null {
    const cases = this.caseState.cases()
    let currentId = caseId
    let isSubCase = false

    for (let depth = 0; depth < MAX_ANCESTOR_HOPS; depth++) {
      const cached = this.rootCache.get(currentId)
      if (cached) {
        return {
          rootCaseId: cached.rootCaseId,
          rootTitle: cached.rootTitle,
          isSubCase: isSubCase || cached.isSubCase,
        }
      }

      const found = cases.find((c) => c.id === currentId)
      if (!found) return null

      if (!found.parentCaseId) {
        const rootCaseId = found.id ?? currentId
        const rootTitle = found.title ?? rootCaseId.slice(0, 8)
        return { rootCaseId, rootTitle, isSubCase }
      }

      isSubCase = true
      currentId = found.parentCaseId
    }

    return { rootCaseId: currentId, rootTitle: currentId.slice(0, 8), isSubCase }
  }

  /**
   * Walks up the `parentCaseId` chain via `CaseControllerService.getByIdCase` (using
   * in-memory state/cache whenever available) until it reaches the root case.
   */
  private resolveRootAsync(caseId: string, isSubCase: boolean, depth: number): Observable<ResolvedRootTarget> {
    if (depth >= MAX_ANCESTOR_HOPS) {
      return of({ rootCaseId: caseId, rootTitle: caseId.slice(0, 8), isSubCase })
    }

    const sync = this.tryResolveSync(caseId)
    if (sync) {
      return of({
        rootCaseId: sync.rootCaseId,
        rootTitle: sync.rootTitle,
        isSubCase: isSubCase || sync.isSubCase,
      })
    }

    return this.caseController.getByIdCase(caseId).pipe(
      switchMap((c: Case): Observable<ResolvedRootTarget> => {
        if (!c.parentCaseId) {
          const rootCaseId = c.id ?? caseId
          const rootTitle = c.title ?? rootCaseId.slice(0, 8)
          return of({ rootCaseId, rootTitle, isSubCase })
        }
        return this.resolveRootAsync(c.parentCaseId, true, depth + 1)
      }),
      map((target): ResolvedRootTarget => ({ ...target, isSubCase: isSubCase || target.isSubCase })),
      catchError((): Observable<ResolvedRootTarget> => of({ rootCaseId: caseId, rootTitle: caseId.slice(0, 8), isSubCase }))
    )
  }

  // ---------------------------------------------------------------------------
  // Group management
  // ---------------------------------------------------------------------------

  private pushToGroup(groupCaseId: string, namespaceId: string, caseTitle: string, n: CompanionNotification): void {
    let added = false
    this.groups.update((prev) => {
      const existing = prev.find((g) => g.caseId === groupCaseId)
      if (existing) {
        if (existing.notifications.some((x) => x.id === n.id)) return prev
        added = true
        return prev.map((g) => (g.caseId === groupCaseId ? { ...g, notifications: [...g.notifications, n] } : g))
      }
      added = true
      return [...prev, { caseId: groupCaseId, namespaceId, caseTitle, notifications: [n] }]
    })

    // When the Companion PiP window is closed, fall back to a native browser notification.
    if (added && !this.companionPip.isOpen()) {
      this.browserNotifications.notify(groupCaseId, namespaceId, caseTitle, n)
    }
  }

  removeNotificationByEvent(eventCaseId: string, notifId: string): void {
    const pending = this.pendingByCase.get(eventCaseId)
    if (pending) {
      const filtered = pending.filter((x) => x.id !== notifId)
      if (filtered.length > 0) {
        this.pendingByCase.set(eventCaseId, filtered)
      } else {
        this.pendingByCase.delete(eventCaseId)
      }
    }

    const target = this.rootCache.get(eventCaseId)
    this.removeNotification(target?.rootCaseId ?? eventCaseId, notifId)
  }

  removeNotification(caseId: string, notifId: string): void {
    this.browserNotifications.close(notifId)
    const timer = this.dismissTimers.get(notifId)
    if (timer !== undefined) {
      clearTimeout(timer)
      this.dismissTimers.delete(notifId)
    }
    this.groups.update((prev) =>
      prev
        .map((g) =>
          g.caseId === caseId || g.notifications.some((x) => x.id === notifId)
            ? { ...g, notifications: g.notifications.filter((x) => x.id !== notifId) }
            : g
        )
        .filter((g) => g.notifications.length > 0)
    )
  }

  dismissGroup(caseId: string): void {
    const group = this.groups().find((g) => g.caseId === caseId)
    if (group) {
      this.browserNotifications.closeMany(group.notifications.map((n) => n.id))
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
