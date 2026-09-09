import { Injectable, inject, OnDestroy, NgZone } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { signal } from '@angular/core'
import { Configuration, QuestionEvent } from '@whoz-oss/agentos-api-client'

export const AGENTOS_OAUTH_CALLBACK_STORAGE_KEY = 'agentos_oauth_callback'

/**
 * OAuthAgentosService — manages the OAuth popup flow for AgentOS QuestionEvent with
 * questionType === 'OAUTH_AUTHORIZE'.
 *
 * Security: the authorization code NEVER transits through this service. The popup
 * (OAuthCallbackComponent) posts the code directly to the backend. This service
 * only receives the outcome (success/failure) via postMessage or localStorage.
 *
 * Flow:
 * 1. CaseChatComponent receives a QuestionEvent (OAUTH_AUTHORIZE) via SSE.
 * 2. It calls `setPendingQuestion(event)` to expose it to the inline panel.
 * 3. The user clicks "Authorize" → `openPopup(event)` is called from click handler.
 * 4. Popup opens to the URL in `event.question`.
 * 5. Provider redirects to /agentos/oauth/callback?code=...&state=...
 * 6. OAuthCallbackComponent posts { code, state } to POST /api/oauth/callback (backend).
 * 7. OAuthCallbackComponent sends { success: true } or { success: false, error } to parent.
 * 8. This service receives the outcome and posts an AnswerEvent to close the QuestionEvent.
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
    // Drain any result that arrived before this service was ready
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

  /**
   * Post an answer to a QuestionEvent (for FREE_TEXT, SINGLE_CHOICE, OPEN_CHOICE).
   * Called directly by the question-panel component.
   */
  answerQuestion(questionEvent: QuestionEvent, answer: string): void {
    this.postAnswer(questionEvent, answer)
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
  // Result reception (from popup via postMessage or localStorage)
  //
  // The popup has already posted the authorization code to the backend.
  // We only receive { success: boolean, error?: string } — never the code or state.
  // ---------------------------------------------------------------------------

  private handlePopupMessage(event: MessageEvent): void {
    if (event.origin !== window.location.origin) {
      return // Reject cross-origin messages
    }
    const data = event.data
    if (!data || typeof data.success !== 'boolean') {
      return // Not an OAuth result message
    }
    console.log('[OAuthAgentos] Received postMessage result:', data.success)
    this.zone.run(() => this.processResult(data))
  }

  private handleStorageEvent(event: StorageEvent): void {
    if (event.key !== AGENTOS_OAUTH_CALLBACK_STORAGE_KEY || !event.newValue) return
    console.log('[OAuthAgentos] Received localStorage result fallback')
    this.zone.run(() => this.drainLocalStorage())
  }

  private drainLocalStorage(): void {
    const raw = localStorage.getItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY)
    if (!raw) return
    localStorage.removeItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY)
    try {
      const data = JSON.parse(raw)
      if (typeof data.success === 'boolean') {
        this.processResult(data)
      }
    } catch (err) {
      console.error('[OAuthAgentos] Failed to parse localStorage OAuth result:', err)
    }
  }

  /**
   * Process the outcome received from the popup.
   *
   * The popup has already posted the authorization code to the backend
   * (POST /api/oauth/callback). We only receive the result (success/failure)
   * — the code and state never leave the popup window.
   *
   * Our job here is to close the QuestionEvent in the case timeline by
   * posting an AnswerEvent.
   */
  private processResult(result: { success: boolean; error?: string }): void {
    this.stopPopupMonitoring()

    const questionEvent = this.pendingQuestion()
    this.pendingQuestion.set(null)

    if (!questionEvent) {
      console.warn('[OAuthAgentos] Received result but no pending question')
      return
    }

    if (result.success) {
      console.log('[OAuthAgentos] OAuth succeeded — posting AnswerEvent')
      this.postAnswer(questionEvent, 'OAuth authorization completed')
    } else {
      console.warn('[OAuthAgentos] OAuth failed:', result.error)
      this.postAnswer(questionEvent, `OAuth authorization failed: ${result.error ?? 'unknown error'}`)
    }
  }

  // ---------------------------------------------------------------------------
  // AnswerEvent posting
  // ---------------------------------------------------------------------------

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
}
