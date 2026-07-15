import { Component, OnInit, inject } from '@angular/core'
import { ActivatedRoute } from '@angular/router'
import { AGENTOS_OAUTH_CALLBACK_STORAGE_KEY } from '../../services/oauth-agentos.service'

/**
 * OAuthCallbackComponent — lightweight popup receiver for AgentOS OAuth flows.
 *
 * Rendered at /agentos/oauth/callback in a popup window opened by OAuthAgentosService.
 * Extracts code/state/error from query params, sends them to the parent window
 * via postMessage, falls back to localStorage when window.opener is unavailable,
 * then closes itself after 1 second.
 */
@Component({
  selector: 'agentos-oauth-callback',
  template: `<p style="font-family: sans-serif; padding: 2rem; text-align: center;">{{ message }}</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)

  protected message = 'Authentication in progress…'

  ngOnInit(): void {
    console.log('[AgentOS OAuth Callback] Component initialized')

    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')
    const error = this.route.snapshot.queryParamMap.get('error')
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description')

    console.log('[AgentOS OAuth Callback] code:', code, 'state:', state, 'error:', error, 'opener:', !!window.opener)

    if (error) {
      console.log('[AgentOS OAuth Callback] OAuth error received:', error, errorDescription)
      this.sendAndClose({ error, state: state ?? undefined, errorDescription: errorDescription ?? undefined })
      return
    }

    if (!code) {
      console.error('[AgentOS OAuth Callback] Missing authorization code')
      this.message = 'OAuth callback error: Missing authorization code.'
      return
    }

    this.sendAndClose({ code, state: state ?? undefined })
  }

  private sendAndClose(data: { code?: string; state?: string; error?: string; errorDescription?: string }): void {
    if (data.error) {
      this.message = 'Authentication failed. You can close this window.'
    } else {
      this.message = 'Authentication complete. You can close this window.'
    }

    if (!window.opener) {
      console.warn('[AgentOS OAuth Callback] No window.opener — using localStorage fallback')
      localStorage.setItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify(data))
      setTimeout(() => window.close(), 1000)
      return
    }

    try {
      window.opener.postMessage(data, window.location.origin)
      console.log('[AgentOS OAuth Callback] postMessage sent successfully')
    } catch (err) {
      console.error('[AgentOS OAuth Callback] Failed to send postMessage:', err)
      // Last-resort fallback
      localStorage.setItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify(data))
    }

    setTimeout(() => {
      console.log('[AgentOS OAuth Callback] Closing popup')
      window.close()
    }, 1000)
  }
}
