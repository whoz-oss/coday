import { Injectable, inject } from '@angular/core'
import { OAuthRequestEvent, OAuthCallbackEvent } from '@coday/model'
import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'
import { filter } from 'rxjs/operators'
import { WINDOW } from '../tokens/window'

@Injectable({
  providedIn: 'root',
})
export class OAuthService {
  private readonly window = inject(WINDOW)
  private eventStream = inject(EventStreamService)
  private messageApi = inject(MessageApiService)
  private projectState = inject(ProjectStateService)
  private threadState = inject(ThreadStateService)

  private pendingStates = new Map<string, string>() // state -> integrationName
  private popupCheckInterval: ReturnType<typeof setInterval> | null = null

  constructor() {
    // Listen to OAuthRequestEvent
    this.eventStream.events$
      .pipe(filter((e): e is OAuthRequestEvent => e.type === 'oauth_request'))
      .subscribe((event) => this.handleOAuthRequest(event))

    // Listen to popup messages (postMessage)
    this.window.addEventListener('message', (event) => this.handlePopupMessage(event))
  }

  /**
   * Monitor popup to detect when it closes
   */
  private startPopupMonitoring(popup: Window, state: string): void {
    // Clean up previous interval if it exists
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }

    // Check every 500ms if popup is closed
    this.popupCheckInterval = setInterval(() => {
      if (popup.closed) {
        console.log('[OAuth Service] Popup closed by user')
        clearInterval(this.popupCheckInterval!)
        this.popupCheckInterval = null

        // Check if state is still pending (no callback received)
        if (this.pendingStates.has(state)) {
          console.warn('[OAuth Service] OAuth cancelled - popup closed without completing authentication')

          // Get integrationName BEFORE deleting
          const integrationName = this.pendingStates.get(state)
          this.pendingStates.delete(state)

          // Send OAuthCallbackEvent with user_cancelled error
          const projectName = this.projectState.getSelectedProjectId()
          const threadId = this.threadState.getSelectedThreadId()

          if (projectName && threadId && integrationName) {
            const cancelCallbackEvent = new OAuthCallbackEvent({
              state,
              integrationName,
              error: 'user_cancelled',
              errorDescription: 'User closed the popup without completing authentication',
            })

            this.messageApi.sendMessage(projectName, threadId, cancelCallbackEvent).subscribe({
              next: () => console.log('[OAuth Service] Cancellation sent to backend'),
              error: (err) => console.error('[OAuth Service] Failed to send cancellation:', err),
            })
          }
        }
      }
    }, 500)
  }

  private handleOAuthRequest(event: OAuthRequestEvent): void {
    console.log('[OAuth Service] Received OAuthRequestEvent:', event.integrationName, event.state)

    // Store state for validation
    this.pendingStates.set(event.state, event.integrationName)
    console.log('[OAuth Service] Pending states:', Array.from(this.pendingStates.keys()))

    // Open centered popup
    const width = 600
    const height = 900
    const left = this.window.screenX + (this.window.outerWidth - width) / 2
    const top = this.window.screenY + (this.window.outerHeight - height) / 2

    // Add 'popup=yes' to force popup behavior and bring to front
    const features = `width=${width},height=${height},left=${left},top=${top},popup=yes,resizable=yes,scrollbars=yes`
    const popup = this.window.open(event.authUrl, 'oauth_popup', features)

    console.log('[OAuth Service] Popup opened:', !!popup)

    // Force focus on popup (helps with fullscreen mode on macOS)
    if (popup) {
      popup.focus()
      this.startPopupMonitoring(popup, event.state)
    }
  }

  private handlePopupMessage(event: MessageEvent): void {
    console.log('[OAuth Service] Received postMessage:', event.origin, event.data)

    // Check origin (basic security)
    if (event.origin !== this.window.location.origin) {
      console.warn('[OAuth Service] Rejected message from different origin:', event.origin)
      return
    }

    const { code, state, error, errorDescription } = event.data || {}

    // Handle OAuth errors (e.g., access_denied)
    if (error) {
      console.log('[OAuth Service] OAuth error received:', error, errorDescription)

      const integrationName = this.pendingStates.get(state)
      if (integrationName) {
        this.pendingStates.delete(state)

        // Stop popup monitoring
        if (this.popupCheckInterval) {
          clearInterval(this.popupCheckInterval)
          this.popupCheckInterval = null
        }

        // Send OAuthCallbackEvent with error
        const projectName = this.projectState.getSelectedProjectId()
        const threadId = this.threadState.getSelectedThreadId()

        if (projectName && threadId) {
          const errorCallbackEvent = new OAuthCallbackEvent({
            state,
            integrationName,
            error,
            errorDescription,
          })

          this.messageApi.sendMessage(projectName, threadId, errorCallbackEvent).subscribe({
            next: () => console.log('[OAuth Service] Error callback sent to backend'),
            error: (err) => console.error('[OAuth Service] Failed to send error callback:', err),
          })
        }
      }
      return
    }

    if (!code || !state) {
      console.warn('[OAuth Service] Message missing code or state:', event.data)
      return
    }

    console.log('[OAuth Service] Processing callback - code:', code, 'state:', state)

    const integrationName = this.pendingStates.get(state)
    if (!integrationName) {
      console.warn(
        '[OAuth Service] Unknown state received:',
        state,
        'Pending states:',
        Array.from(this.pendingStates.keys())
      )
      return
    }

    console.log('[OAuth Service] Found integration:', integrationName)

    // Clean up state and stop popup monitoring
    this.pendingStates.delete(state)
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
      this.popupCheckInterval = null
    }

    // Create and send event to backend
    const callbackEvent = new OAuthCallbackEvent({ code, state, integrationName })

    const projectName = this.projectState.getSelectedProjectId()
    const threadId = this.threadState.getSelectedThreadId()

    console.log('[OAuth Service] Project:', projectName, 'Thread:', threadId)

    if (projectName && threadId) {
      console.log('[OAuth Service] Sending callback to backend...')
      this.messageApi.sendMessage(projectName, threadId, callbackEvent).subscribe({
        next: () => console.log('[OAuth Service] Callback sent to backend successfully'),
        error: (err) => console.error('[OAuth Service] Failed to send callback:', err),
      })
    } else {
      console.error('[OAuth Service] No project/thread selected for OAuth callback')
    }
  }
}
