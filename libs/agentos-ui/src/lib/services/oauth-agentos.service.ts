import { Injectable, inject, OnDestroy, NgZone } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { signal } from '@angular/core'
import { Configuration, QuestionEvent } from '@whoz-oss/agentos-api-client'

export const AGENTOS_OAUTH_CALLBACK_STORAGE_KEY = 'agentos_oauth_callback'

/**
 * OAuthAgentosService — manages the OAuth popup flow for AgentOS QuestionEvent with
 * questionType === 'OAUTH_AUTHORIZE'.
 *
 * Flow:
 * 1. CaseChatComponent receives a QuestionEvent (OAUTH_AUTHORIZE) via SSE.
 * 2. It calls `setPendingQuestion(event)` to expose it to the inline panel.
 * 3. The user clicks "Authorize" in the panel → `openPopup(event)` is called from click handler.
 * 4. Popup opens to the URL in `event.question`.
 * 5. Popup redirects to /agentos/oauth/callback?code=...&state=...
 * 6. OAuthCallbackComponent sends postMessage (or localStorage fallback).
 * 7. This service receives the callback, posts to POST /api/oauth/callback (resolves backend future)
 *    then posts to POST /api/cases/{caseId}/messages with answerToEventId (creates AnswerEvent).
 */
@Injectable({
  providedIn: 'root',
})
export class OAuthAgentosService implements OnDestroy {
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)
  private readonly zone = inject(NgZone)

  private popupCheckInterval: ReturnType<typeof setInterval> | null = null
  private popupClosedAt: number | null = null
  private static readonly POPUP_CLOSE_GRACE_MS = 2000

  /** Currently pending OAuth QuestionEvent, or null when none is pending. */
  readonly pendingQuestion = signal<QuestionEvent | null>(null)

  private readonly messageHandler = (event: MessageEvent) => this.handlePopupMessage(event)
  private readonly storageHandler = (event: StorageEvent) => this.handleStorageEvent(event)

  constructor() {
    window.addEventListener('message', this.messageHandler)
    window.addEventListener('storage', this.storageHandler)
    // Drain any callback that arrived before this service was ready
    this.drainLocalStorage()
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.messageHandler)
    window.removeEventListener('storage', this.storageHandler)
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }
  }

  /**
   * Register a pending OAUTH_AUTHORIZE QuestionEvent.
   * Called by CaseChatComponent when it receives such an event via SSE.
   */
  setPendingQuestion(event: QuestionEvent): void {
    console.log('[OAuthAgentos] Pending OAuth question registered:', event.id)
    this.pendingQuestion.set(event)
  }

  /**
   * Open the OAuth popup from a direct user gesture (click on the inline panel button).
   * Must be called from a click event handler so browsers allow the popup.
   */
  openPopup(event: QuestionEvent): void {
    console.log('[OAuthAgentos] Opening popup from user gesture:', event.id)
    const authUrl = event.question // The question field holds the full authorization URL

    const width = 600
    const height = 900
    const left = window.screenX + (window.outerWidth - width) / 2
    const top = window.screenY + (window.outerHeight - height) / 2
    const features = `width=${width},height=${height},left=${left},top=${top},popup=yes,resizable=yes,scrollbars=yes`

    const popup = window.open(authUrl, 'agentos_oauth_popup', features)

    if (popup) {
      popup.focus()
      this.startPopupMonitoring(popup, event)
      // Hide the inline panel once popup is confirmed open
      this.pendingQuestion.set(null)
    } else {
      // Popup blocked — keep the panel visible so the user can retry
      console.warn('[OAuthAgentos] Popup was blocked by the browser')
    }
  }

  /**
   * Cancel the pending OAuth question (user dismissed the inline panel).
   */
  cancelRequest(): void {
    console.log('[OAuthAgentos] OAuth cancelled by user via inline panel')
    this.pendingQuestion.set(null)
    this.stopPopupMonitoring()
  }

  // ---------------------------------------------------------------------------
  // Popup monitoring
  // ---------------------------------------------------------------------------

  private startPopupMonitoring(popup: Window, questionEvent: QuestionEvent): void {
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }
    this.popupClosedAt = null

    this.popupCheckInterval = setInterval(() => {
      if (!popup.closed) {
        // Still open — reset grace period if set
        if (this.popupClosedAt !== null) {
          this.popupClosedAt = null
        }
        return
      }

      if (this.popupClosedAt === null) {
        this.popupClosedAt = Date.now()
        console.log('[OAuthAgentos] Popup closed, waiting for postMessage grace period...')
        return
      }

      const elapsed = Date.now() - this.popupClosedAt
      if (elapsed < OAuthAgentosService.POPUP_CLOSE_GRACE_MS) {
        return // Still within grace period
      }

      console.log('[OAuthAgentos] Grace period elapsed — popup closed without completing auth')
      this.stopPopupMonitoring()

      // If there is still a pending question signal it was user-cancelled
      // (the postMessage path clears pendingQuestion before we get here)
      const pending = this.pendingQuestion()
      if (pending?.id === questionEvent.id) {
        console.warn('[OAuthAgentos] OAuth cancelled — popup closed without completing')
        this.pendingQuestion.set(null)
      }
    }, 500)
  }

  private stopPopupMonitoring(): void {
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
      this.popupCheckInterval = null
    }
    this.popupClosedAt = null
  }

  // ---------------------------------------------------------------------------
  // Callback reception
  // ---------------------------------------------------------------------------

  private handlePopupMessage(event: MessageEvent): void {
    if (event.origin !== window.location.origin) {
      return // Reject cross-origin messages
    }
    const data = event.data
    if (!data || (!data.code && !data.error)) {
      return // Not an OAuth callback message
    }
    console.log('[OAuthAgentos] Received postMessage callback')
    this.zone.run(() => this.processCallbackData(data))
  }

  private handleStorageEvent(event: StorageEvent): void {
    if (event.key !== AGENTOS_OAUTH_CALLBACK_STORAGE_KEY || !event.newValue) return
    console.log('[OAuthAgentos] Received localStorage callback fallback')
    this.zone.run(() => this.drainLocalStorage())
  }

  private drainLocalStorage(): void {
    const raw = localStorage.getItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY)
    if (!raw) return
    localStorage.removeItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY)
    try {
      const data = JSON.parse(raw)
      this.processCallbackData(data)
    } catch (err) {
      console.error('[OAuthAgentos] Failed to parse localStorage OAuth callback:', err)
    }
  }

  private processCallbackData(data: {
    code?: string
    state?: string
    error?: string
    errorDescription?: string
  }): void {
    const { code, state, error } = data ?? {}
    this.stopPopupMonitoring()

    if (error) {
      console.warn('[OAuthAgentos] OAuth error received:', error)
      this.pendingQuestion.set(null)
      return
    }

    if (!code) {
      console.warn('[OAuthAgentos] Callback missing code')
      return
    }

    // Grab the pending question before clearing it
    const questionEvent = this.pendingQuestion()
    this.pendingQuestion.set(null)

    console.log('[OAuthAgentos] Processing OAuth callback — posting to /api/oauth/callback')

    // Step 1: post to /api/oauth/callback to resolve the backend CompletableFuture
    this.http.post(`${this.config.basePath}/api/oauth/callback`, { code, state }).subscribe({
      next: () => {
        console.log('[OAuthAgentos] /api/oauth/callback resolved successfully')
        // Step 2: post AnswerEvent via /api/cases/{caseId}/messages if we have the QuestionEvent
        if (questionEvent) {
          this.postAnswer(questionEvent, 'OAuth authorization completed')
        }
      },
      error: (err) => {
        console.error('[OAuthAgentos] Failed to post to /api/oauth/callback:', err)
        // Still try to post the AnswerEvent so the case timeline is consistent
        if (questionEvent) {
          this.postAnswer(questionEvent, 'OAuth authorization completed')
        }
      },
    })
  }

  private postAnswer(questionEvent: QuestionEvent, content: string): void {
    this.http
      .post(`${this.config.basePath}/api/cases/${questionEvent.caseId}/messages`, {
        content,
        answerToEventId: questionEvent.id,
      })
      .subscribe({
        next: () => console.log('[OAuthAgentos] AnswerEvent posted successfully'),
        error: (err) => console.error('[OAuthAgentos] Failed to post AnswerEvent:', err),
      })
  }

  /**
   * Post an answer to a QuestionEvent (for FREE_TEXT, SINGLE_CHOICE, OPEN_CHOICE).
   * Called directly by the question-panel component.
   */
  answerQuestion(questionEvent: QuestionEvent, answer: string): void {
    this.postAnswer(questionEvent, answer)
  }
}
