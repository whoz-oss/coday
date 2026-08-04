import { Component, OnInit, inject } from '@angular/core'
import { HttpClient } from '@angular/common/http'
import { ActivatedRoute } from '@angular/router'
import { Configuration } from '@whoz-oss/agentos-api-client'
import { AGENTOS_OAUTH_CALLBACK_STORAGE_KEY } from '../../services/oauth-agentos.service'

/**
 * OAuthCallbackComponent — lightweight popup receiver for AgentOS OAuth flows.
 *
 * Rendered at /agentos/oauth/callback in a popup window opened by OAuthAgentosService.
 *
 * Security: the authorization code is sensitive and must NEVER leave this popup via
 * postMessage or localStorage. Instead, this component posts the code directly to
 * the backend (`POST /api/oauth/callback`) and only communicates the outcome
 * (success/failure) to the parent window.
 *
 * Flow:
 * 1. Extract code/state/error from query params
 * 2. If code is present: POST to /api/oauth/callback { code, state } from THIS popup
 * 3. Send only { success: true } or { success: false, error: '...' } to parent
 * 4. Auto-close after 1 second
 */
@Component({
  selector: 'agentos-oauth-callback',
  template: `<p style="font-family: sans-serif; padding: 2rem; text-align: center">{{ message }}</p>`,
})
export class OAuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly http = inject(HttpClient)
  private readonly config = inject(Configuration)

  protected message = 'Authentication in progress…'

  ngOnInit(): void {
    const code = this.route.snapshot.queryParamMap.get('code')
    const state = this.route.snapshot.queryParamMap.get('state')
    const error = this.route.snapshot.queryParamMap.get('error')
    const errorDescription = this.route.snapshot.queryParamMap.get('error_description')

    if (error) {
      console.warn('[AgentOS OAuth Callback] OAuth error:', error, errorDescription)
      this.notifyParentAndClose({
        success: false,
        error: errorDescription || error,
      })
      return
    }

    if (!code) {
      console.error('[AgentOS OAuth Callback] Missing authorization code')
      this.message = 'OAuth callback error: missing authorization code.'
      this.notifyParentAndClose({
        success: false,
        error: 'Missing authorization code',
      })
      return
    }

    // Post the code directly to the backend from this popup.
    // The code never leaves this window via postMessage or localStorage.
    this.http.post(`${this.config.basePath}/api/oauth/callback`, { code, state }).subscribe({
      next: () => {
        console.log('[AgentOS OAuth Callback] Backend callback resolved')
        this.notifyParentAndClose({ success: true })
      },
      error: (err) => {
        console.error('[AgentOS OAuth Callback] Backend callback failed:', err)
        this.notifyParentAndClose({
          success: false,
          error: err?.error?.message || err?.message || 'Token exchange failed',
        })
      },
    })
  }

  /**
   * Send ONLY the outcome (success/failure) to the parent window — never the code or state.
   * Falls back to localStorage when window.opener is unavailable (cross-origin popup).
   */
  private notifyParentAndClose(result: { success: boolean; error?: string }): void {
    this.message = result.success
      ? 'Authentication complete. You can close this window.'
      : `Authentication failed: ${result.error ?? 'unknown error'}. You can close this window.`

    if (window.opener) {
      try {
        window.opener.postMessage(result, window.location.origin)
      } catch (err) {
        console.error('[AgentOS OAuth Callback] postMessage failed:', err)
        localStorage.setItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify(result))
      }
    } else {
      console.warn('[AgentOS OAuth Callback] No window.opener — localStorage fallback')
      localStorage.setItem(AGENTOS_OAUTH_CALLBACK_STORAGE_KEY, JSON.stringify(result))
    }

    setTimeout(() => window.close(), 1000)
  }
}
