import { Injectable, inject } from '@angular/core'
import { OAuthRequestEvent, OAuthCallbackEvent } from '@coday/coday-events'
import { EventStreamService } from './event-stream.service'
import { MessageApiService } from './message-api.service'
import { ProjectStateService } from './project-state.service'
import { ThreadStateService } from './thread-state.service'
import { filter } from 'rxjs/operators'

@Injectable({
  providedIn: 'root',
})
export class OAuthService {
  private eventStream = inject(EventStreamService)
  private messageApi = inject(MessageApiService)
  private projectState = inject(ProjectStateService)
  private threadState = inject(ThreadStateService)

  private pendingStates = new Map<string, string>() // state -> integrationName
  private popupCheckInterval: ReturnType<typeof setInterval> | null = null

  constructor() {
    // Écouter les OAuthRequestEvent
    this.eventStream.events$
      .pipe(filter((e): e is OAuthRequestEvent => e.type === 'oauth_request'))
      .subscribe((event) => this.handleOAuthRequest(event))

    // Écouter les messages de la popup (postMessage)
    window.addEventListener('message', (event) => this.handlePopupMessage(event))
  }

  /**
   * Surveiller la popup pour détecter sa fermeture
   */
  private startPopupMonitoring(popup: Window, state: string): void {
    // Nettoyer l'intervalle précédent si existant
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
    }

    // Vérifier toutes les 500ms si la popup est fermée
    this.popupCheckInterval = setInterval(() => {
      if (popup.closed) {
        console.log('[OAuth Service] Popup closed by user')
        clearInterval(this.popupCheckInterval!)
        this.popupCheckInterval = null

        // Vérifier si le state est toujours pending (pas de callback reçu)
        if (this.pendingStates.has(state)) {
          console.warn('[OAuth Service] OAuth cancelled - popup closed without completing authentication')

          // Récupérer integrationName AVANT de delete
          const integrationName = this.pendingStates.get(state)
          this.pendingStates.delete(state)

          // Envoyer un OAuthCallbackEvent avec erreur user_cancelled
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

    // Stocker le state pour validation
    this.pendingStates.set(event.state, event.integrationName)
    console.log('[OAuth Service] Pending states:', Array.from(this.pendingStates.keys()))

    // Ouvrir popup centrée
    const width = 600
    const height = 900
    const left = window.screenX + (window.outerWidth - width) / 2
    const top = window.screenY + (window.outerHeight - height) / 2

    const popup = window.open(event.authUrl, 'oauth_popup', `width=${width},height=${height},left=${left},top=${top}`)

    console.log('[OAuth Service] Popup opened:', !!popup)

    // Détecter la fermeture de la popup
    if (popup) {
      this.startPopupMonitoring(popup, event.state)
    }
  }

  private handlePopupMessage(event: MessageEvent): void {
    console.log('[OAuth Service] Received postMessage:', event.origin, event.data)

    // Vérifier l'origine (sécurité basique)
    if (event.origin !== window.location.origin) {
      console.warn('[OAuth Service] Rejected message from different origin:', event.origin)
      return
    }

    const { code, state, error, errorDescription } = event.data || {}

    // Gérer les erreurs OAuth (ex: access_denied)
    if (error) {
      console.log('[OAuth Service] OAuth error received:', error, errorDescription)

      const integrationName = this.pendingStates.get(state)
      if (integrationName) {
        this.pendingStates.delete(state)

        // Arrêter le monitoring de la popup
        if (this.popupCheckInterval) {
          clearInterval(this.popupCheckInterval)
          this.popupCheckInterval = null
        }

        // Envoyer un OAuthCallbackEvent avec l'erreur
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

    // Nettoyer le state et arrêter le monitoring de la popup
    this.pendingStates.delete(state)
    if (this.popupCheckInterval) {
      clearInterval(this.popupCheckInterval)
      this.popupCheckInterval = null
    }

    // Créer et envoyer l'event au backend
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
