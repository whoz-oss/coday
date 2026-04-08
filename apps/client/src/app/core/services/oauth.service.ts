import { Injectable, inject, OnDestroy } from '@angular/core'
import { OAuthRequestEvent, OAuthCallbackEvent } from '@coday/model'
import { BehaviorSubject } from 'rxjs'
import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { filter } from 'rxjs/operators'
import { OAUTH_CALLBACK_STORAGE_KEY } from '../../components/oauth-callback/oauth-callback.component'

@Injectable({
  providedIn: 'root',
})
export class OAuthService implements OnDestroy {
  private readonly eventStream = inject(EventStreamService)
  private readonly messageApi = inject(MessageApiService)

  private readonly pendingStates = new Map<string, string>() // state -> integrationName
  private popupCheckInterval: ReturnType<typeof setInterval> | null = null
  private popupClosedAt: number | null = null
  private static readonly POPUP_CLOSE_GRACE_MS = 2000

  /** Emits the current pending OAuth request, or null when none is pending. */
  readonly pendingRequest$ = new BehaviorSubject<OAuthRequestEvent | null>(null)

  // Store the handler references so we can remove them on destroy
  private readonly messageHandler = (event: MessageEvent) => this.handlePopupMessage(event)
  private readonly storageHandler = (event: StorageEvent) => this.handleStorageEvent(event)

  constructor() {
    // Listen to OAuthRequestEvent — store it for the inline panel instead of opening popup directly
    this.eventStream.events$
      .pipe(filter((e): e is OAuthRequestEvent => e.type === 'oauth_request'))
      .subscribe((event) => this.handleOAuthRequest(event))

    // Listen to popup messages (postMessage)
    window.addEventListener('message', this.messageHandler)

    // Listen to localStorage events (fallback for when window.opener is null)
    window.addEventListener('storage', this.storageHandler)

    // Process any callback that arrived before this service was ready
    this.drainLocalStorage()
  }

  ngOnDestroy(): void {
    window.removeEventListener('message', this.messageHandler)
    window.removeEventListener('storage', this.storageHandler)
    this.pendingRequest$.complete()
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }
  }

  /**
   * Monitor popup to detect when it closes
   */
  private startPopupMonitoring(popup: Window, state: string): void {
    // Clean up previous interval if it exists
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }

    // Check every 500ms if popup is closed.
    // A grace period of POPUP_CLOSE_GRACE_MS is applied after detecting closure:
    // the OAuth callback page (OAuthCallbackComponent) needs time to bootstrap Angular,
    // send the postMessage, and have it processed — before we conclude it's a cancellation.
    this.popupCheckInterval = setInterval(() => {
      if (popup.closed) {
        if (this.popupClosedAt === null) {
          this.popupClosedAt = Date.now()
          console.log('[OAuth Service] Popup closed, waiting for postMessage grace period...')
          return
        }

        const elapsed = Date.now() - this.popupClosedAt
        if (elapsed < OAuthService.POPUP_CLOSE_GRACE_MS) {
          // Still within grace period — wait for postMessage to arrive
          return
        }

        console.log('[OAuth Service] Grace period elapsed, popup closed by user')
        clearInterval(this.popupCheckInterval!)
        this.popupCheckInterval = null
        this.popupClosedAt = null

        // Check if state is still pending (no callback received)
        if (this.pendingStates.has(state)) {
          console.warn('[OAuth Service] OAuth cancelled - popup closed without completing authentication')

          // Get integrationName BEFORE deleting
          const integrationName = this.pendingStates.get(state)
          this.pendingStates.delete(state)

          // Send OAuthCallbackEvent with user_cancelled error
          if (integrationName) {
            const cancelCallbackEvent = new OAuthCallbackEvent({
              state,
              integrationName,
              error: 'user_cancelled',
              errorDescription: 'User closed the popup without completing authentication',
            })

            this.messageApi.sendMessage(cancelCallbackEvent).subscribe({
              next: () => console.log('[OAuth Service] Cancellation sent to backend'),
              error: (err) => console.error('[OAuth Service] Failed to send cancellation:', err),
            })
          }
        }
      } else {
        // Popup is still open (navigating) — reset grace period if it was set
        if (this.popupClosedAt !== null) {
          console.log('[OAuth Service] Popup reopened/navigating, resetting grace period')
          this.popupClosedAt = null
        }
      }
    }, 500)
  }

  /**
   * Open the OAuth popup from a direct user gesture (click on the inline panel button).
   * Must be called from a click event handler so browsers allow the popup.
   * If the popup is blocked (window.open returns null), the panel remains visible
   * so the user can try again or cancel.
   */
  openPopup(event: OAuthRequestEvent): void {
    console.log('[OAuth Service] Opening popup from user gesture:', event.integrationName)

    const width = 600
    const height = 900
    const left = window.screenX + (window.outerWidth - width) / 2
    const top = window.screenY + (window.outerHeight - height) / 2

    const features = `width=${width},height=${height},left=${left},top=${top},popup=yes,resizable=yes,scrollbars=yes`
    const popup = window.open(event.authUrl, 'oauth_popup', features)

    console.log('[OAuth Service] Popup opened:', !!popup)

    if (popup) {
      popup.focus()
      this.startPopupMonitoring(popup, event.state)
      // Hide the inline panel only once the popup is confirmed open
      this.pendingRequest$.next(null)
    } else {
      // Popup was blocked — keep the panel visible so the user can retry or cancel
      console.warn('[OAuth Service] Popup was blocked by the browser')
    }
  }

  /**
   * Cancel the pending OAuth request (user dismissed the inline panel).
   */
  cancelRequest(event: OAuthRequestEvent): void {
    console.log('[OAuth Service] OAuth cancelled by user via inline panel')
    this.pendingStates.delete(event.state)
    this.pendingRequest$.next(null)

    const cancelCallbackEvent = new OAuthCallbackEvent({
      state: event.state,
      integrationName: event.integrationName,
      error: 'user_cancelled',
      errorDescription: 'User dismissed the authorization panel',
    })

    this.messageApi.sendMessage(cancelCallbackEvent).subscribe({
      next: () => console.log('[OAuth Service] Cancellation sent to backend'),
      error: (err) => console.error('[OAuth Service] Failed to send cancellation:', err),
    })
  }

  private handleOAuthRequest(event: OAuthRequestEvent): void {
    console.log('[OAuth Service] Received OAuthRequestEvent:', event.integrationName, event.state)

    // Store state for validation
    this.pendingStates.set(event.state, event.integrationName)
    console.log('[OAuth Service] Pending states:', Array.from(this.pendingStates.keys()))

    // Expose the request to the inline panel — popup will be opened from user click
    this.pendingRequest$.next(event)
  }

  private handlePopupMessage(event: MessageEvent): void {
    console.log('[OAuth Service] Received postMessage:', event.origin, event.data)

    // Check origin (basic security)
    if (event.origin !== window.location.origin) {
      console.warn('[OAuth Service] Rejected message from different origin:', event.origin)
      return
    }

    this.processCallbackData(event.data)
  }

  /**
   * Handle the storage event — picks up OAuth callbacks written to localStorage
   * by OAuthCallbackComponent when window.opener is null.
   */
  private handleStorageEvent(event: StorageEvent): void {
    if (event.key !== OAUTH_CALLBACK_STORAGE_KEY || !event.newValue) return
    console.log('[OAuth Service] Received localStorage callback fallback')
    this.drainLocalStorage()
  }

  /**
   * Read and process any pending OAuth callback stored in localStorage.
   * Called on init and on each storage event for the callback key.
   */
  private drainLocalStorage(): void {
    const raw = localStorage.getItem(OAUTH_CALLBACK_STORAGE_KEY)
    if (!raw) return

    // Remove immediately to prevent double-processing
    localStorage.removeItem(OAUTH_CALLBACK_STORAGE_KEY)
    console.log('[OAuth Service] Processing localStorage OAuth callback fallback')

    try {
      const data = JSON.parse(raw)
      this.processCallbackData(data)
    } catch (err) {
      console.error('[OAuth Service] Failed to parse localStorage OAuth callback:', err)
    }
  }

  /**
   * Core callback processing logic — shared by postMessage and localStorage paths.
   */
  private processCallbackData(data: {
    code?: string
    state?: string
    error?: string
    errorDescription?: string
  }): void {
    const { code, state, error, errorDescription } = data ?? {}

    // Handle OAuth errors (e.g., access_denied)
    if (error) {
      console.log('[OAuth Service] OAuth error received:', error, errorDescription)

      const integrationName = this.pendingStates.get(state!)
      if (integrationName) {
        this.pendingStates.delete(state!)

        // Stop popup monitoring
        if (this.popupCheckInterval) {
          clearInterval(this.popupCheckInterval)
          this.popupCheckInterval = null
          this.popupClosedAt = null
        }

        // Send OAuthCallbackEvent with error
        const errorCallbackEvent = new OAuthCallbackEvent({
          state,
          integrationName,
          error,
          errorDescription,
        })

        this.messageApi.sendMessage(errorCallbackEvent).subscribe({
          next: () => console.log('[OAuth Service] Error callback sent to backend'),
          error: (err) => console.error('[OAuth Service] Failed to send error callback:', err),
        })
      }
      return
    }

    if (!code) {
      console.warn('[OAuth Service] Message missing code:', data)
      return
    }

    console.log('[OAuth Service] Processing callback - code:', code, 'state:', state)

    // Look up integration by state, with fallback for providers that don't return state (e.g. HubSpot)
    let integrationName = state ? this.pendingStates.get(state) : undefined
    let resolvedState = state

    if (!integrationName && this.pendingStates.size === 1) {
      // Safe fallback: provider omitted state but we have exactly one pending flow
      const firstEntry = this.pendingStates.entries().next().value as [string, string] | undefined
      if (firstEntry) {
        const [onlyState, onlyIntegration] = firstEntry
        console.warn('[OAuth Service] State missing from callback, using sole pending flow:', onlyIntegration)
        integrationName = onlyIntegration
        resolvedState = onlyState
      }
    }

    if (!integrationName) {
      console.warn(
        '[OAuth Service] Unknown or ambiguous state:',
        state,
        'Pending states:',
        Array.from(this.pendingStates.keys())
      )
      return
    }

    console.log('[OAuth Service] Found integration:', integrationName)

    // Clean up state and stop popup monitoring
    this.pendingStates.delete(resolvedState!)
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
      this.popupCheckInterval = null
      this.popupClosedAt = null
    }

    // Create and send event to backend (use resolvedState so the backend receives the original state value)
    const callbackEvent = new OAuthCallbackEvent({ code, state: resolvedState, integrationName })

    console.log('[OAuth Service] Sending callback to backend...')
    this.messageApi.sendMessage(callbackEvent).subscribe({
      next: () => console.log('[OAuth Service] Callback sent to backend successfully'),
      error: (err) => console.error('[OAuth Service] Failed to send callback:', err),
    })
  }
}
